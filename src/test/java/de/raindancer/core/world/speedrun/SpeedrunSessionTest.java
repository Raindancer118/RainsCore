package de.raindancer.core.world.speedrun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A live run: {@link SpeedrunSession#finish} guarantees "once", exactly like
 * {@code Achievements.award} — see its javadoc for why the check-then-write shape is the bug and
 * one atomic step is the fix.
 */
class SpeedrunSessionTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    @Test
    @DisplayName("a session needs at least one participant")
    void rejectsAnEmptyRoster() {
        assertThatCode(() -> new SpeedrunSession(Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new SpeedrunSession(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("participants() is an unmodifiable view of what was given")
    void participantsAreCopiedAndUnmodifiable() {
        Set<UUID> given = new java.util.HashSet<>(Set.of(ALICE));
        SpeedrunSession session = new SpeedrunSession(given);
        given.add(BOB);   // mutating the caller's set afterwards must not reach the session

        assertThat(session.participants()).containsExactly(ALICE);
        assertThatCode(() -> session.participants().add(BOB))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Nested
    @DisplayName("starting")
    class Starting {

        @Test
        void movesToRunningAndStartsTheTimer() {
            SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
            session.start();

            assertThat(session.state()).isEqualTo(SpeedrunState.RUNNING);
        }

        @Test
        @DisplayName("a second start() does not restart the clock")
        void secondStartIsANoOp() throws InterruptedException {
            SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
            session.start();
            Thread.sleep(20);
            Duration before = session.elapsed();
            session.start();   // must not reset elapsed() back towards zero

            assertThat(session.elapsed()).isGreaterThanOrEqualTo(before);
            assertThat(session.state()).isEqualTo(SpeedrunState.RUNNING);
        }
    }

    @Nested
    @DisplayName("pausing for an empty roster")
    class Pausing {

        @Test
        void pauseAndResumeStopAndRestartTheClock() throws InterruptedException {
            SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
            session.start();
            Thread.sleep(20);
            session.pauseForEmptyRoster();
            Duration atPause = session.elapsed();
            assertThat(session.state()).isEqualTo(SpeedrunState.PAUSED);

            Thread.sleep(30);
            // Still paused: elapsed() must not have moved.
            assertThat(session.elapsed()).isEqualTo(atPause);

            session.resume();
            assertThat(session.state()).isEqualTo(SpeedrunState.RUNNING);
            Thread.sleep(20);
            assertThat(session.elapsed()).isGreaterThan(atPause);
        }

        @Test
        @DisplayName("pausing a session that has not started is a no-op")
        void cannotPauseBeforeStarting() {
            SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
            session.pauseForEmptyRoster();

            assertThat(session.state()).isEqualTo(SpeedrunState.NOT_STARTED);
        }

        @Test
        @DisplayName("pausing a finished session is a no-op")
        void cannotPauseAfterFinishing() {
            SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
            session.start();
            session.finish("manual");

            session.pauseForEmptyRoster();

            assertThat(session.state()).isEqualTo(SpeedrunState.FINISHED);
        }

        @Test
        @DisplayName("resuming a session that is not paused is a no-op")
        void cannotResumeWhenNotPaused() {
            SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
            session.start();

            session.resume();

            assertThat(session.state()).isEqualTo(SpeedrunState.RUNNING);
        }
    }

    @Nested
    @DisplayName("finishing")
    class Finishing {

        @Test
        @DisplayName("a second sequential call is a silent no-op")
        void sequentialCallsFinishOnlyOnce() {
            SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
            List<SpeedrunOutcome> announced = new CopyOnWriteArrayList<>();
            session.onFinish(announced::add);
            session.start();

            session.finish("first");
            session.finish("second");

            assertThat(announced).hasSize(1);
            assertThat(session.outcome()).isPresent();
            assertThat(session.outcome().get().reason()).isEqualTo("first");
        }

        @Test
        @DisplayName("finish() is atomic under concurrent callers — exactly one outcome sticks")
        void concurrentCallsFinishOnlyOnce() throws InterruptedException {
            SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
            AtomicInteger announcements = new AtomicInteger();
            session.onFinish(outcome -> announcements.incrementAndGet());
            session.start();

            int threads = 16;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            try {
                for (int i = 0; i < threads; i++) {
                    int id = i;
                    pool.submit(() -> {
                        ready.countDown();
                        try {
                            go.await();
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        session.finish("racer-" + id);
                    });
                }
                ready.await();
                go.countDown();
            } finally {
                pool.shutdown();
                assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(announcements.get()).isEqualTo(1);
            assertThat(session.state()).isEqualTo(SpeedrunState.FINISHED);
            // Whichever reason won, it must not change under repeated reads afterwards.
            String reason = session.outcome().orElseThrow().reason();
            assertThat(session.outcome().orElseThrow().reason()).isEqualTo(reason);
        }

        @Test
        @DisplayName("a listener that throws does not stop the others or the finish itself")
        void aBrokenListenerDoesNotStopTheOthers() {
            SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
            List<String> seen = new CopyOnWriteArrayList<>();
            session.onFinish(outcome -> {
                throw new RuntimeException("boom");
            });
            session.onFinish(outcome -> seen.add(outcome.reason()));
            session.start();

            assertThatCode(() -> session.finish("done")).doesNotThrowAnyException();
            assertThat(seen).containsExactly("done");
        }

        @Test
        @DisplayName("addEndCondition arms it, and finish() disarms every condition exactly once")
        void endConditionsAreArmedAndDisarmed() {
            SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
            RecordingCondition first = new RecordingCondition();
            RecordingCondition second = new RecordingCondition();
            session.addEndCondition(first);
            session.addEndCondition(second);

            assertThat(first.armedWith).isSameAs(session);
            assertThat(second.armedWith).isSameAs(session);
            assertThat(first.disarmed).isFalse();

            session.start();
            session.finish("done");
            session.finish("done again");   // must not disarm a second time

            assertThat(first.disarmCount).isEqualTo(1);
            assertThat(second.disarmCount).isEqualTo(1);
        }

        @Test
        @DisplayName("a condition that throws while disarming does not stop the others")
        void aBrokenConditionDoesNotStopOtherDisarms() {
            SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
            RecordingCondition broken = new RecordingCondition() {
                @Override
                public void disarm() {
                    super.disarm();
                    throw new RuntimeException("could not unregister");
                }
            };
            RecordingCondition fine = new RecordingCondition();
            session.addEndCondition(broken);
            session.addEndCondition(fine);
            session.start();

            assertThatCode(() -> session.finish("done")).doesNotThrowAnyException();
            assertThat(fine.disarmCount).isEqualTo(1);
        }
    }

    /** A hand-rolled {@link SpeedrunEndCondition} that just records what happened to it. */
    private static class RecordingCondition implements SpeedrunEndCondition {
        SpeedrunSession armedWith;
        boolean disarmed;
        int disarmCount;

        @Override
        public void arm(SpeedrunSession session) {
            this.armedWith = session;
        }

        @Override
        public void disarm() {
            disarmed = true;
            disarmCount++;
        }
    }
}
