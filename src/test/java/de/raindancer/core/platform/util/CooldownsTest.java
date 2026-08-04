package de.raindancer.core.platform.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one thing every cooldown in this codebase has got wrong at least once.
 *
 * <p>Reading the last use and then writing it, as two steps, lets two requests arriving together
 * both see the old value and both be allowed — a double-click or a macro getting a free go past the
 * cooldown. On Folia those really are two threads. So the check and the record are one operation
 * here, and {@link Concurrently} is the test that would have caught it.
 */
class CooldownsTest {

    /** Milliseconds, moved by hand — a cooldown test that sleeps is a cooldown test nobody runs. */
    private final AtomicLong now = new AtomicLong(1_000);

    private Cooldowns<UUID> lasting(Duration between) {
        Cooldowns<UUID> cooldowns = new Cooldowns<>(now::get);
        cooldowns.every(between);
        return cooldowns;
    }

    @Nested
    @DisplayName("with no cooldown set")
    class SwitchedOff {

        @Test
        @DisplayName("everything is allowed, every time")
        void nothingIsEverRefused() {
            Cooldowns<UUID> cooldowns = new Cooldowns<>(now::get);
            UUID player = UUID.randomUUID();

            assertThat(cooldowns.tryUse(player)).isTrue();
            assertThat(cooldowns.tryUse(player)).isTrue();
            assertThat(cooldowns.tryUse(player)).isTrue();
        }

        @Test
        @DisplayName("nothing is remembered, so nothing can leak")
        void nobodyIsTracked() {
            Cooldowns<UUID> cooldowns = new Cooldowns<>(now::get);
            cooldowns.tryUse(UUID.randomUUID());

            assertThat(cooldowns.tracked())
                    .as("an off cooldown that still filled a map would grow by an entry per player "
                            + "for a feature the server has switched off")
                    .isZero();
        }

        @Test
        @DisplayName("zero and a negative duration mean off, not instant")
        void zeroIsOff() {
            Cooldowns<UUID> zero = lasting(Duration.ZERO);
            Cooldowns<UUID> backwards = lasting(Duration.ofSeconds(-5));

            assertThat(zero.every()).isEmpty();
            assertThat(backwards.every()).isEmpty();
        }

        @Test
        @DisplayName("nothing is ever left to wait for")
        void nothingRemains() {
            Cooldowns<UUID> cooldowns = new Cooldowns<>(now::get);
            UUID player = UUID.randomUUID();
            cooldowns.tryUse(player);

            assertThat(cooldowns.remaining(player)).isEmpty();
            assertThat(cooldowns.isReady(player)).isTrue();
        }
    }

    @Nested
    @DisplayName("with one set")
    class Waiting {

        @Test
        @DisplayName("the first go is allowed and the second is not")
        void theSecondOneWaits() {
            Cooldowns<UUID> cooldowns = lasting(Duration.ofSeconds(30));
            UUID player = UUID.randomUUID();

            assertThat(cooldowns.tryUse(player)).isTrue();
            assertThat(cooldowns.tryUse(player)).isFalse();
        }

        @Test
        @DisplayName("it is over when the clock says so")
        void timePasses() {
            Cooldowns<UUID> cooldowns = lasting(Duration.ofSeconds(30));
            UUID player = UUID.randomUUID();
            cooldowns.tryUse(player);

            now.addAndGet(29_999);
            assertThat(cooldowns.tryUse(player)).isFalse();

            now.addAndGet(1);
            assertThat(cooldowns.tryUse(player))
                    .as("exactly the cooldown has passed, which is long enough")
                    .isTrue();
        }

        @Test
        @DisplayName("a refusal does not restart the wait")
        void beingRefusedCostsNothing() {
            // Otherwise somebody clicking a greyed button never gets through at all, and the plugin
            // looks broken to precisely the people trying hardest to use it.
            Cooldowns<UUID> cooldowns = lasting(Duration.ofSeconds(30));
            UUID player = UUID.randomUUID();
            cooldowns.tryUse(player);

            now.addAndGet(20_000);
            assertThat(cooldowns.tryUse(player)).isFalse();

            now.addAndGet(10_000);
            assertThat(cooldowns.tryUse(player))
                    .as("thirty seconds after the *use*, not after the last refusal")
                    .isTrue();
        }

        @Test
        @DisplayName("one player waiting does not make anybody else wait")
        void theyAreKeptApart() {
            Cooldowns<UUID> cooldowns = lasting(Duration.ofSeconds(30));
            UUID one = UUID.randomUUID();
            UUID other = UUID.randomUUID();

            assertThat(cooldowns.tryUse(one)).isTrue();
            assertThat(cooldowns.tryUse(other)).isTrue();
            assertThat(cooldowns.tryUse(one)).isFalse();
        }

        @Test
        @DisplayName("how long is left, for the line that says so")
        void itSaysHowLongIsLeft() {
            Cooldowns<UUID> cooldowns = lasting(Duration.ofSeconds(30));
            UUID player = UUID.randomUUID();
            cooldowns.tryUse(player);

            now.addAndGet(10_000);
            assertThat(cooldowns.remaining(player)).contains(Duration.ofSeconds(20));

            now.addAndGet(20_000);
            assertThat(cooldowns.remaining(player))
                    .as("over is empty, not zero — a message saying 'wait 0s' is a message that lies")
                    .isEmpty();
        }

        @Test
        @DisplayName("asking whether somebody is ready does not use up their go")
        void askingIsFree() {
            // What a screen does to grey a button. If asking recorded, opening the menu would put the
            // player on cooldown for a warp they never took.
            Cooldowns<UUID> cooldowns = lasting(Duration.ofSeconds(30));
            UUID player = UUID.randomUUID();

            assertThat(cooldowns.isReady(player)).isTrue();
            assertThat(cooldowns.isReady(player)).isTrue();
            assertThat(cooldowns.tryUse(player))
                    .as("two speculative asks must not have spent the one real go")
                    .isTrue();
        }

        @Test
        @DisplayName("a cooldown can be started without asking")
        void itCanBeStartedOutright() {
            // For the caller that has already decided — an arrival that succeeded, say, where the
            // asking happened before the teleport and the recording has to happen after it.
            Cooldowns<UUID> cooldowns = lasting(Duration.ofSeconds(30));
            UUID player = UUID.randomUUID();

            cooldowns.start(player);

            assertThat(cooldowns.isReady(player)).isFalse();
            assertThat(cooldowns.remaining(player)).contains(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("shortening the cooldown releases whoever is already waiting")
        void changingItAppliesAtOnce() {
            // The owner lowered it in the settings. Keeping the old figure for whoever is mid-wait is
            // the sort of thing reported as "the config does not work".
            Cooldowns<UUID> cooldowns = lasting(Duration.ofSeconds(30));
            UUID player = UUID.randomUUID();
            cooldowns.tryUse(player);

            now.addAndGet(5_000);
            cooldowns.every(Duration.ofSeconds(3));

            assertThat(cooldowns.tryUse(player)).isTrue();
        }
    }

    @Nested
    @DisplayName("forgetting")
    class Forgetting {

        @Test
        @DisplayName("somebody forgotten starts fresh")
        void forgettingClearsTheWait() {
            Cooldowns<UUID> cooldowns = lasting(Duration.ofSeconds(30));
            UUID player = UUID.randomUUID();
            cooldowns.tryUse(player);

            cooldowns.forget(player);

            assertThat(cooldowns.tryUse(player)).isTrue();
        }

        @Test
        @DisplayName("null is not a player and does not throw")
        void nullIsIgnored() {
            Cooldowns<UUID> cooldowns = lasting(Duration.ofSeconds(30));

            assertThat(cooldowns.tryUse(null)).isTrue();
            assertThat(cooldowns.remaining(null)).isEmpty();
            assertThat(cooldowns.isReady(null)).isTrue();
            cooldowns.forget(null);
            cooldowns.start(null);

            assertThat(cooldowns.tracked())
                    .as("a null key must not become an entry that nothing can ever forget")
                    .isZero();
        }

        @Test
        @DisplayName("a sweep drops what has expired and keeps what has not")
        void sweepingBoundsTheMap() {
            // forget() on quit is the real path. This is the insurance for the entries it misses —
            // a player who was never seen to leave, on a server that has been up for a month.
            Cooldowns<UUID> cooldowns = lasting(Duration.ofSeconds(30));
            UUID gone = UUID.randomUUID();
            UUID recent = UUID.randomUUID();
            cooldowns.tryUse(gone);

            now.addAndGet(31_000);
            cooldowns.tryUse(recent);
            cooldowns.sweep();

            assertThat(cooldowns.tracked()).isEqualTo(1);
            assertThat(cooldowns.isReady(recent)).isFalse();
        }

        @Test
        @DisplayName("a sweep with no cooldown set empties it")
        void sweepingWhenOffClearsEverything() {
            Cooldowns<UUID> cooldowns = lasting(Duration.ofSeconds(30));
            cooldowns.tryUse(UUID.randomUUID());

            cooldowns.every(null);
            cooldowns.sweep();

            assertThat(cooldowns.tracked()).isZero();
        }
    }

    @Nested
    @DisplayName("asked by several threads at once")
    class Concurrently {

        /**
         * A clock that takes a moment to answer.
         *
         * <p>Without this the test proves nothing. A read-then-write cooldown was put in place of the
         * real one and a hundred virtual threads released together still let exactly one through —
         * the window between the read and the write is a few nanoseconds wide and the scheduler
         * simply never landed in it. A test that cannot fail is worse than no test, because it is
         * read as cover.
         *
         * <p>Every caller asks the clock <em>before</em> the read-modify-write, so a pause here puts
         * all the racers past that point together and holds the window open for a millisecond. The
         * correct implementation does not care: its check and its record are one operation whatever
         * the clock does.
         */
        private java.util.function.LongSupplier aSlowClock() {
            return () -> {
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
                return now.get();
            };
        }

        @Test
        @DisplayName("exactly one of a hundred simultaneous goes is allowed")
        void onlyOneGetsThrough() throws Exception {
            // The bug this class exists to make impossible to write again: read-then-write lets every
            // thread see the same old value and every one of them be allowed.
            Cooldowns<UUID> cooldowns = new Cooldowns<>(aSlowClock());
            cooldowns.every(Duration.ofSeconds(30));
            UUID player = UUID.randomUUID();

            int racers = 100;
            CountDownLatch ready = new CountDownLatch(racers);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger allowed = new AtomicInteger();

            // One thread per racer, or the ones that never start leave the latch un-counted and the
            // ones that did are still blocked on it — which is a test that hangs the whole build.
            // Virtual, so a hundred of them cost nothing.
            try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int racer = 0; racer < racers; racer++) {
                    threads.submit(() -> {
                        ready.countDown();
                        try {
                            go.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (cooldowns.tryUse(player)) {
                            allowed.incrementAndGet();
                        }
                    });
                }
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                go.countDown();
                threads.shutdown();
                assertThat(threads.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(allowed.get())
                    .as("a hundred clicks in the same millisecond are one go, not a hundred")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a hundred different players each get their one go")
        void everybodyGetsTheirOwn() throws Exception {
            Cooldowns<UUID> cooldowns = lasting(Duration.ofSeconds(30));
            List<UUID> players = java.util.stream.Stream.generate(UUID::randomUUID)
                    .limit(100).toList();
            AtomicInteger allowed = new AtomicInteger();

            // One thread per racer, or the ones that never start leave the latch un-counted and the
            // ones that did are still blocked on it — which is a test that hangs the whole build.
            // Virtual, so a hundred of them cost nothing.
            try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
                for (UUID player : players) {
                    threads.submit(() -> {
                        if (cooldowns.tryUse(player)) {
                            allowed.incrementAndGet();
                        }
                    });
                }
                threads.shutdown();
                assertThat(threads.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(allowed.get()).isEqualTo(players.size());
        }
    }
}
