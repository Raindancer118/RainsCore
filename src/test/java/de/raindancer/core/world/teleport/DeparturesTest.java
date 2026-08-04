package de.raindancer.core.world.teleport;

import de.raindancer.core.world.safety.Spot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Standing still for a few seconds before being sent somewhere.
 *
 * <p>Every rule here was written twice before this class existed — once in the teleport requests and
 * once in homes, byte for byte down to the {@code sameBlock} helper — so this is the one of them.
 * The Bukkit half is {@code Travel}; this is the half that decides, and it is the half a test can
 * reach.
 *
 * <h2>Why "the same block" and not "the same position"</h2>
 * Because a player standing perfectly still is not still. Breathing, a mob pushing past, a boat
 * rocking and the client's own idle animation all move them by fractions of a block, and a warm-up
 * measured on the exact position is one nobody can ever complete.
 */
class DeparturesTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private static final Spot HOME = new Spot("world", 100, 64, 200);

    private Departures departures;

    @BeforeEach
    void setUp() {
        departures = new Departures();
    }

    @Nested
    @DisplayName("setting off")
    class Beginning {

        @Test
        @DisplayName("a warm-up is remembered, with the seconds it was given")
        void itIsPending() {
            assertThat(departures.begin(ALICE, HOME, 3, "spawn")).isPresent();

            assertThat(departures.isLeaving(ALICE)).isTrue();
            assertThat(departures.pending(ALICE).orElseThrow().secondsLeft()).isEqualTo(3);
            assertThat(departures.pending(ALICE).orElseThrow().what()).isEqualTo("spawn");
        }

        @Test
        @DisplayName("a second one is refused rather than replacing the first")
        void oneAtATime() {
            // Replacing would let somebody type the command again to restart the countdown from
            // wherever they now are, which is a warm-up that can be walked through.
            departures.begin(ALICE, HOME, 3, "spawn");

            assertThat(departures.begin(ALICE, HOME.offset(5, 0, 5), 3, "mine")).isEmpty();
            assertThat(departures.pending(ALICE).orElseThrow().what()).isEqualTo("spawn");
        }

        @Test
        @DisplayName("somebody already going somewhere is refused, whatever the second trip is")
        void oneJourneyAtATimeIsTheRule() {
            // Travel asks isLeaving before it looks at the warm-up at all, and this is the state
            // that makes it necessary. An instant trip taken during a warm-up used to go straight
            // through, and then the warm-up finished and teleported them a second time — to where
            // the first warp pointed, seconds after they had arrived somewhere else.
            departures.begin(ALICE, HOME, 3, "spawn");

            assertThat(departures.isLeaving(ALICE))
                    .as("this is the flag an instant trip has to consult before it goes")
                    .isTrue();
        }

        @Test
        @DisplayName("no warm-up at all is not a departure to remember")
        void zeroSecondsIsNotPending() {
            // The caller goes straight there. Remembering a warm-up of zero would mean a movement
            // event could cancel a teleport that has already happened.
            assertThat(departures.begin(ALICE, HOME, 0, "spawn")).isEmpty();
            assertThat(departures.isLeaving(ALICE)).isFalse();
        }

        @Test
        @DisplayName("nonsense is refused")
        void refusesNonsense() {
            assertThat(departures.begin(null, HOME, 3, "spawn")).isEmpty();
            assertThat(departures.begin(ALICE, null, 3, "spawn")).isEmpty();
            assertThat(departures.begin(ALICE, HOME, -1, "spawn")).isEmpty();
            assertThat(departures.pendingCount()).isZero();
        }

        @Test
        @DisplayName("two players wait separately")
        void theyAreKeptApart() {
            departures.begin(ALICE, HOME, 3, "spawn");
            departures.begin(BOB, HOME, 5, "mine");

            assertThat(departures.pending(ALICE).orElseThrow().secondsLeft()).isEqualTo(3);
            assertThat(departures.pending(BOB).orElseThrow().secondsLeft()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("counting down")
    class Ticking {

        @Test
        @DisplayName("each tick takes a second off, and the last one arrives")
        void itCountsDown() {
            departures.begin(ALICE, HOME, 3, "spawn");

            assertThat(departures.tick(ALICE)).isEqualTo(Departures.Tick.WAITING);
            assertThat(departures.pending(ALICE).orElseThrow().secondsLeft()).isEqualTo(2);

            assertThat(departures.tick(ALICE)).isEqualTo(Departures.Tick.WAITING);
            assertThat(departures.tick(ALICE)).isEqualTo(Departures.Tick.ARRIVED);
        }

        @Test
        @DisplayName("arriving forgets the departure, so the tick cannot fire twice")
        void arrivingIsFinal() {
            // A repeating task that fires once more before it is cancelled would teleport somebody
            // twice — and on the second one they are already at the destination, so it looks like
            // the plugin randomly moving people about.
            departures.begin(ALICE, HOME, 1, "spawn");

            assertThat(departures.tick(ALICE)).isEqualTo(Departures.Tick.ARRIVED);
            assertThat(departures.isLeaving(ALICE)).isFalse();
            assertThat(departures.tick(ALICE)).isEqualTo(Departures.Tick.NOTHING_PENDING);
        }

        @Test
        @DisplayName("ticking somebody who is not going anywhere says so")
        void nothingToTick() {
            assertThat(departures.tick(ALICE)).isEqualTo(Departures.Tick.NOTHING_PENDING);
            assertThat(departures.tick(null)).isEqualTo(Departures.Tick.NOTHING_PENDING);
        }
    }

    @Nested
    @DisplayName("moving away")
    class Moving {

        @Test
        @DisplayName("shifting about inside the same block is still standing still")
        void breathingIsNotWalking() {
            // Measured on the block rather than the position, because a player standing perfectly
            // still is not still: breathing, a mob pushing past and the idle animation all move
            // them by fractions of a block.
            departures.begin(ALICE, HOME, 3, "spawn");

            assertThat(departures.hasMoved(ALICE, HOME)).isFalse();
            assertThat(departures.isLeaving(ALICE))
                    .as("and it must not have cancelled anything on the way to answering")
                    .isTrue();
        }

        @Test
        @DisplayName("a block sideways is moving")
        void walkingIsWalking() {
            departures.begin(ALICE, HOME, 3, "spawn");

            assertThat(departures.hasMoved(ALICE, HOME.offset(1, 0, 0))).isTrue();
        }

        @Test
        @DisplayName("a block up or down is moving too")
        void jumpingIsMoving() {
            departures.begin(ALICE, HOME, 3, "spawn");

            assertThat(departures.hasMoved(ALICE, HOME.offset(0, 1, 0)))
                    .as("a player who has stepped up onto a block has moved off the spot")
                    .isTrue();
        }

        @Test
        @DisplayName("another world is moving, however close the numbers look")
        void aPortalIsMoving() {
            departures.begin(ALICE, HOME, 3, "spawn");

            assertThat(departures.hasMoved(ALICE, new Spot("nether", 100, 64, 200))).isTrue();
        }

        @Test
        @DisplayName("somebody who is not going anywhere has not moved away from anything")
        void nobodyIsNotMoving() {
            assertThat(departures.hasMoved(ALICE, HOME)).isFalse();
            assertThat(departures.hasMoved(null, HOME)).isFalse();
            assertThat(departures.hasMoved(ALICE, null)).isFalse();
        }

        @Test
        @DisplayName("asking does not cancel: the caller decides what moving costs")
        void askingIsFree() {
            // Some callers forgive a step — a warp taken from a boat, say. Cancelling inside the
            // question would take that decision away from every one of them.
            departures.begin(ALICE, HOME, 3, "spawn");

            departures.hasMoved(ALICE, HOME.offset(3, 0, 3));

            assertThat(departures.isLeaving(ALICE)).isTrue();
        }
    }

    @Nested
    @DisplayName("giving up")
    class Cancelling {

        @Test
        @DisplayName("cancelling forgets it, and says whether there was one")
        void itCancels() {
            departures.begin(ALICE, HOME, 3, "spawn");

            assertThat(departures.cancel(ALICE)).isTrue();
            assertThat(departures.isLeaving(ALICE)).isFalse();
            assertThat(departures.cancel(ALICE))
                    .as("so a caller can tell 'they were going somewhere and now are not' from "
                            + "'nothing was happening', and only say something in the first case")
                    .isFalse();
        }

        @Test
        @DisplayName("somebody who logged out takes their warm-up with them")
        void loggingOutForgets() {
            // The same operation as giving up, deliberately — see the note on cancel. Two names for
            // one thing is where half the callers end up using the one that was not fixed.
            departures.begin(ALICE, HOME, 3, "spawn");

            departures.cancel(ALICE);

            assertThat(departures.pendingCount())
                    .as("a warm-up left behind by somebody who quit is an entry per player who has "
                            + "ever been on the server — which has happened twice in this repository")
                    .isZero();
        }

        @Test
        @DisplayName("everything can be dropped at once, for a plugin shutting down")
        void everythingCanBeDropped() {
            departures.begin(ALICE, HOME, 3, "spawn");
            departures.begin(BOB, HOME, 3, "mine");

            departures.clear();

            assertThat(departures.pendingCount()).isZero();
        }
    }

    @Nested
    @DisplayName("asked by several threads at once")
    class Concurrently {

        /** How many racers, and how many times over. */
        private static final int RACERS = 64;
        private static final int ROUNDS = 300;

        /**
         * Runs one race and counts how many racers were told yes.
         *
         * <p>Run {@link #ROUNDS} times by each test below, and every round has to answer one. A
         * single round proves nothing: a check-then-act version put in place of the real one still
         * gave the right answer most of the time, because the window between the check and the act
         * is nanoseconds wide and the scheduler has to land inside it. The rounds are what make it
         * land.
         *
         * <p>One thread per racer, and virtual so that costs nothing. A fixed pool smaller than the
         * field leaves the tasks that never started out of the latch and the ones that did blocked
         * on it for ever, which is not a failing test but a hanging build.
         */
        private int race(java.util.function.IntSupplier oneRacer) throws Exception {
            CountDownLatch ready = new CountDownLatch(RACERS);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger yeses = new AtomicInteger();

            try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int racer = 0; racer < RACERS; racer++) {
                    threads.submit(() -> {
                        ready.countDown();
                        try {
                            go.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        yeses.addAndGet(oneRacer.getAsInt());
                    });
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                go.countDown();
                threads.shutdown();
                assertThat(threads.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            }
            return yeses.get();
        }

        @Test
        @DisplayName("simultaneous commands start exactly one warm-up, every round")
        void onlyOneBegins() throws Exception {
            // On Folia the same player's events can reach two region threads. Two warm-ups for one
            // player means two teleports, and the second arrives at a place chosen before the first.
            for (int round = 0; round < ROUNDS; round++) {
                departures.clear();

                int started = race(() ->
                        departures.begin(ALICE, HOME, 3, "spawn").isPresent() ? 1 : 0);

                assertThat(started)
                        .as("round %d: %d of %d racers were each given their own warm-up",
                                round, started, RACERS)
                        .isEqualTo(1);
            }
        }

        @Test
        @DisplayName("simultaneous ticks arrive exactly once, every round")
        void onlyOneArrives() throws Exception {
            for (int round = 0; round < ROUNDS; round++) {
                departures.clear();
                departures.begin(ALICE, HOME, 1, "spawn");

                int arrived = race(() ->
                        departures.tick(ALICE) == Departures.Tick.ARRIVED ? 1 : 0);

                assertThat(arrived)
                        .as("round %d: two threads both seeing the last second is two teleports "
                                + "for one warp", round)
                        .isEqualTo(1);
            }
        }
    }
}
