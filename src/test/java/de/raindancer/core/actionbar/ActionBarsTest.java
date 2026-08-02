package de.raindancer.core.actionbar;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who gets the action bar when more than one plugin wants it.
 *
 * <h2>Why this is arbitration and not a {@code sendActionBar} wrapper</h2>
 * The action bar is one slot per player. Today the ghast lines write flight commentary to it on a
 * tick, the claims write "you have entered Raindancer118's claim" to it on a move, and neither knows
 * the other exists — so whoever wrote last wins for a few frames and the player sees a flicker
 * between two messages. Wrapping {@code sendActionBar} would not change that by one line.
 *
 * <p>So the helper owns the slot. A message has an owner, a priority and a lifetime; the highest
 * priority live message is the one shown; and it is re-sent on a tick because the client fades it
 * after about three seconds. Everything here is decided by {@link ActionBars} without touching a
 * server, which is what lets the rules be tested rather than played.
 */
class ActionBarsTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private RecordingSink sink;
    private AtomicLong clock;
    private ActionBars bars;

    @BeforeEach
    void setUp() {
        sink = new RecordingSink();
        clock = new AtomicLong(1_000L);
        bars = new ActionBars(sink, clock::get);
    }

    private void advance(Duration by) {
        clock.addAndGet(by.toMillis());
    }

    private String shown(UUID player) {
        Component last = sink.last.get(player);
        return last == null ? null : PlainTextComponentSerializer.plainText().serialize(last);
    }

    // ------------------------------------------------------------------ the basics

    @Nested
    @DisplayName("showing one message")
    class One {

        @Test
        @DisplayName("appears immediately rather than at the next tick")
        void showsAtOnce() {
            bars.show(ALICE, "claims", Component.text("You have entered a claim"),
                    Duration.ofSeconds(5), ActionBarPriority.NORMAL);
            assertThat(shown(ALICE)).isEqualTo("You have entered a claim");
        }

        @Test
        @DisplayName("is re-sent on a tick, because the client fades it after about three seconds")
        void isResent() {
            bars.show(ALICE, "claims", Component.text("still here"), Duration.ofSeconds(10),
                    ActionBarPriority.NORMAL);
            sink.reset();

            advance(Duration.ofSeconds(1));
            bars.tick();
            advance(Duration.ofSeconds(1));
            bars.tick();

            assertThat(sink.sends).hasSize(2);
            assertThat(shown(ALICE)).isEqualTo("still here");
        }

        @Test
        @DisplayName("stops being shown once its time is up")
        void expires() {
            bars.show(ALICE, "claims", Component.text("brief"), Duration.ofSeconds(3),
                    ActionBarPriority.NORMAL);
            sink.reset();

            advance(Duration.ofSeconds(4));
            bars.tick();

            assertThat(shown(ALICE)).isEmpty();
            assertThat(bars.isShowingAnything(ALICE)).isFalse();
        }

        @Test
        @DisplayName("an expiring message clears the bar exactly once, not on every tick after")
        void clearsOnlyOnce() {
            bars.show(ALICE, "claims", Component.text("brief"), Duration.ofSeconds(3),
                    ActionBarPriority.NORMAL);
            advance(Duration.ofSeconds(4));
            bars.tick();
            sink.reset();

            bars.tick();
            bars.tick();

            assertThat(sink.sends)
                    .as("a player with nothing to show must cost nothing per tick")
                    .isEmpty();
        }

        @Test
        @DisplayName("a message with no duration stays until it is taken away")
        void staysWhenPermanent() {
            bars.show(ALICE, "flight", Component.text("cruising"), ActionBars.UNTIL_CLEARED,
                    ActionBarPriority.NORMAL);
            advance(Duration.ofHours(2));
            bars.tick();
            assertThat(shown(ALICE)).isEqualTo("cruising");

            bars.clear(ALICE, "flight");
            assertThat(shown(ALICE)).isEmpty();
        }
    }

    // ------------------------------------------------------------------ arbitration

    @Nested
    @DisplayName("when two plugins want the bar")
    class Arbitration {

        @Test
        @DisplayName("the higher priority wins, whichever asked first")
        void higherPriorityWins() {
            bars.show(ALICE, "flight", Component.text("cruising"), ActionBars.UNTIL_CLEARED,
                    ActionBarPriority.LOW);
            bars.show(ALICE, "claims", Component.text("you may not build here"),
                    Duration.ofSeconds(3), ActionBarPriority.HIGH);
            assertThat(shown(ALICE)).isEqualTo("you may not build here");

            // ...and the reverse order gives the same answer.
            setUp();
            bars.show(ALICE, "claims", Component.text("you may not build here"),
                    Duration.ofSeconds(3), ActionBarPriority.HIGH);
            bars.show(ALICE, "flight", Component.text("cruising"), ActionBars.UNTIL_CLEARED,
                    ActionBarPriority.LOW);
            assertThat(shown(ALICE)).isEqualTo("you may not build here");
        }

        @Test
        @DisplayName("the low-priority message comes back when the high one expires")
        void fallsBackWhenTheWinnerExpires() {
            bars.show(ALICE, "flight", Component.text("cruising"), ActionBars.UNTIL_CLEARED,
                    ActionBarPriority.LOW);
            bars.show(ALICE, "claims", Component.text("you may not build here"),
                    Duration.ofSeconds(3), ActionBarPriority.HIGH);

            advance(Duration.ofSeconds(4));
            bars.tick();

            assertThat(shown(ALICE))
                    .as("the flight did not stop just because something interrupted it")
                    .isEqualTo("cruising");
        }

        @Test
        @DisplayName("equal priority: the most recent wins, so an answer replaces an answer")
        void mostRecentWinsOnATie() {
            bars.show(ALICE, "homes", Component.text("home set"), Duration.ofSeconds(5),
                    ActionBarPriority.NORMAL);
            advance(Duration.ofMillis(10));
            bars.show(ALICE, "tpa", Component.text("request sent"), Duration.ofSeconds(5),
                    ActionBarPriority.NORMAL);
            assertThat(shown(ALICE)).isEqualTo("request sent");
        }

        @Test
        @DisplayName("one owner has one message: showing again replaces its own, not another's")
        void oneMessagePerOwner() {
            bars.show(ALICE, "flight", Component.text("boarding"), ActionBars.UNTIL_CLEARED,
                    ActionBarPriority.LOW);
            bars.show(ALICE, "flight", Component.text("cruising"), ActionBars.UNTIL_CLEARED,
                    ActionBarPriority.LOW);
            bars.clear(ALICE, "flight");

            assertThat(bars.isShowingAnything(ALICE))
                    .as("two shows from one owner must not leave a second message behind")
                    .isFalse();
        }

        @Test
        @DisplayName("clearing an owner that never showed anything is harmless")
        void clearingNothingIsFine() {
            bars.clear(ALICE, "nobody");
            assertThat(sink.sends).isEmpty();
        }

        @Test
        @DisplayName("players do not see each other's messages")
        void playersAreIndependent() {
            bars.show(ALICE, "claims", Component.text("alice's"), Duration.ofSeconds(5),
                    ActionBarPriority.NORMAL);
            bars.show(BOB, "claims", Component.text("bob's"), Duration.ofSeconds(5),
                    ActionBarPriority.NORMAL);
            assertThat(shown(ALICE)).isEqualTo("alice's");
            assertThat(shown(BOB)).isEqualTo("bob's");
        }
    }

    // ------------------------------------------------------------------ not flickering

    @Nested
    @DisplayName("what is actually sent")
    class Traffic {

        @Test
        @DisplayName("showing the same text twice does not send it twice")
        void doesNotRepeatItself() {
            bars.show(ALICE, "flight", Component.text("cruising"), ActionBars.UNTIL_CLEARED,
                    ActionBarPriority.NORMAL);
            sink.reset();
            bars.show(ALICE, "flight", Component.text("cruising"), ActionBars.UNTIL_CLEARED,
                    ActionBarPriority.NORMAL);
            assertThat(sink.sends)
                    .as("an unchanged bar is re-sent by the tick, not by every caller that repeats itself")
                    .isEmpty();
        }

        @Test
        @DisplayName("a changed message is sent at once, without waiting for the tick")
        void sendsAChange() {
            bars.show(ALICE, "flight", Component.text("boarding"), ActionBars.UNTIL_CLEARED,
                    ActionBarPriority.NORMAL);
            sink.reset();
            bars.show(ALICE, "flight", Component.text("cruising"), ActionBars.UNTIL_CLEARED,
                    ActionBarPriority.NORMAL);
            assertThat(sink.sends).hasSize(1);
            assertThat(shown(ALICE)).isEqualTo("cruising");
        }

        @Test
        @DisplayName("a player who left is forgotten, so the map does not grow for ever")
        void forgetsPlayers() {
            bars.show(ALICE, "claims", Component.text("hello"), ActionBars.UNTIL_CLEARED,
                    ActionBarPriority.NORMAL);
            assertThat(bars.trackedPlayers()).containsExactly(ALICE);

            bars.forget(ALICE);

            assertThat(bars.trackedPlayers()).isEmpty();
            assertThat(bars.isShowingAnything(ALICE)).isFalse();
        }

        @Test
        @DisplayName("a player whose last message expired is forgotten by the tick itself")
        void tidiesUpAfterItself() {
            bars.show(ALICE, "claims", Component.text("brief"), Duration.ofSeconds(1),
                    ActionBarPriority.NORMAL);
            advance(Duration.ofSeconds(2));
            bars.tick();
            bars.tick();
            assertThat(bars.trackedPlayers())
                    .as("nothing is left behind for a player with nothing to show")
                    .isEmpty();
        }
    }

    // ------------------------------------------------------------------ countdowns

    /**
     * The thing the action bar is actually for: something that matters only for the second it is on
     * screen, and that redraws itself while it does.
     */
    @Nested
    @DisplayName("a countdown")
    class Countdowns {

        private void teleportIn(Duration total) {
            bars.countdown(ALICE, "tpa", total, ActionBarPriority.NORMAL,
                    remaining -> Component.text("Teleporting in " + Math.ceilDiv(remaining, 1000)));
        }

        @Test
        @DisplayName("shows its first frame at once")
        void startsImmediately() {
            teleportIn(Duration.ofSeconds(5));
            assertThat(shown(ALICE)).isEqualTo("Teleporting in 5");
        }

        @Test
        @DisplayName("counts down as the ticks go by, without the caller re-sending it")
        void countsDown() {
            teleportIn(Duration.ofSeconds(5));

            advance(Duration.ofSeconds(2));
            bars.tick();
            assertThat(shown(ALICE)).isEqualTo("Teleporting in 3");

            advance(Duration.ofSeconds(2));
            bars.tick();
            assertThat(shown(ALICE)).isEqualTo("Teleporting in 1");
        }

        @Test
        @DisplayName("clears itself when it reaches zero")
        void endsOnItsOwn() {
            teleportIn(Duration.ofSeconds(5));
            advance(Duration.ofSeconds(5));
            bars.tick();

            assertThat(shown(ALICE)).isEmpty();
            assertThat(bars.isShowingAnything(ALICE)).isFalse();
        }

        @Test
        @DisplayName("can be called off early, which is what /tpcancel needs")
        void canBeCancelled() {
            teleportIn(Duration.ofSeconds(5));
            bars.clear(ALICE, "tpa");
            assertThat(bars.isShowingAnything(ALICE)).isFalse();
        }

        @Test
        @DisplayName("a redraw that has not changed the text is not sent again")
        void doesNotFlickerBetweenSeconds() {
            teleportIn(Duration.ofSeconds(5));
            sink.reset();

            // Two ticks inside the same second: the text is identical both times.
            advance(Duration.ofMillis(100));
            bars.tick();
            advance(Duration.ofMillis(100));
            bars.tick();

            assertThat(sink.sends)
                    .as("a countdown redrawn twice in one second must not send twice")
                    .isEmpty();
        }

        @Test
        @DisplayName("it loses to a refusal, and comes back when the refusal has gone")
        void yieldsToSomethingUrgent() {
            teleportIn(Duration.ofSeconds(10));
            bars.show(ALICE, "claims", Component.text("You may not build here"),
                    Duration.ofSeconds(2), ActionBarPriority.HIGH);
            assertThat(shown(ALICE)).isEqualTo("You may not build here");

            advance(Duration.ofSeconds(3));
            bars.tick();
            assertThat(shown(ALICE)).isEqualTo("Teleporting in 7");
        }

        @Test
        @DisplayName("a frame that throws costs that frame, not the tick")
        void survivesABrokenFrame() {
            bars.countdown(ALICE, "tpa", Duration.ofSeconds(5), ActionBarPriority.NORMAL,
                    remaining -> {
                        throw new IllegalStateException("bad template");
                    });
            bars.show(BOB, "claims", Component.text("fine"), Duration.ofSeconds(5),
                    ActionBarPriority.NORMAL);

            bars.tick();

            assertThat(shown(BOB)).isEqualTo("fine");
            assertThat(bars.isShowingAnything(ALICE))
                    .as("a countdown that cannot draw itself is dropped rather than left broken")
                    .isFalse();
        }

        @Test
        @DisplayName("no frame builder, no countdown")
        void refusesANullFrame() {
            bars.countdown(ALICE, "tpa", Duration.ofSeconds(5), ActionBarPriority.NORMAL, null);
            assertThat(bars.isShowingAnything(ALICE)).isFalse();
        }

        @Test
        @DisplayName("a countdown of no length is over before it starts")
        void refusesAZeroLength() {
            teleportIn(Duration.ZERO);
            assertThat(bars.isShowingAnything(ALICE)).isFalse();
        }
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    @DisplayName("showing from many threads at once never loses a message or throws")
    void isSafeFromEveryThread() throws Exception {
        int threads = 8;
        int each = 250;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads + 1)) {
            for (int thread = 0; thread < threads; thread++) {
                int id = thread;
                pool.submit(() -> {
                    start.await();
                    for (int round = 0; round < each; round++) {
                        bars.show(ALICE, "owner-" + id, Component.text("t" + id + "-" + round),
                                Duration.ofSeconds(30), ActionBarPriority.NORMAL);
                    }
                    return null;
                });
            }
            // ...while the tick runs, which is what happens on a real server.
            pool.submit(() -> {
                start.await();
                for (int round = 0; round < 500; round++) {
                    bars.tick();
                }
                return null;
            });
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(sink.failures).isEmpty();
        assertThat(bars.isShowingAnything(ALICE)).isTrue();
    }

    /**
     * The orphaned-slot race.
     *
     * <p>{@code show} takes a {@code Slot} out of the map with {@code computeIfAbsent} and only then
     * takes its lock. Between those two steps another thread can clear the last message, find the
     * slot idle and drop it from the map. The first thread then writes into a slot nothing points at
     * any more: the player is sent the message, so they see it — but {@code tick()} never visits
     * that slot again, so it is never refreshed and never expires. The message fades after about
     * three seconds and never comes back, and the manager believes nothing is being shown.
     *
     * <p>The invariant that catches it without needing the interleaving to be forced: if the player
     * has been sent something other than an empty line, the manager must agree that something is
     * being shown.
     */
    @Test
    @DisplayName("a clear racing a show never leaves a message nothing owns")
    void neverOrphansASlot() throws Exception {
        for (int round = 0; round < 2_000; round++) {
            setUp();
            bars.show(ALICE, "seed", Component.text("seed"), ActionBars.UNTIL_CLEARED,
                    ActionBarPriority.LOW);

            CountDownLatch go = new CountDownLatch(1);
            Thread clearer = new Thread(() -> {
                awaitQuietly(go);
                bars.clear(ALICE, "seed");
            });
            Thread shower = new Thread(() -> {
                awaitQuietly(go);
                bars.show(ALICE, "tpa", Component.text("Teleporting in 3"),
                        Duration.ofSeconds(5), ActionBarPriority.NORMAL);
            });
            clearer.start();
            shower.start();
            go.countDown();
            clearer.join();
            shower.join();

            String last = shown(ALICE);
            boolean somethingIsOnScreen = last != null && !last.isEmpty();
            assertThat(bars.isShowingAnything(ALICE))
                    .as("round %d: the player was last sent '%s', so the manager has to know about "
                            + "it — otherwise nothing will ever refresh or expire it", round, last)
                    .isEqualTo(somethingIsOnScreen);
        }
    }

    /**
     * The same race with the ticker as the other thread, rather than a clearing plugin.
     *
     * <p>The interleaving is different enough to be worth its own test: {@code computeIfAbsent}
     * creates a brand-new empty slot, {@code tick()} reaches it before the writer has put anything
     * in it, finds nothing to show and reaps it — and the writer then populates a slot that has
     * already been dropped. A countdown started that way renders its first frame, freezes, and fades.
     */
    @Test
    @DisplayName("a tick racing the very first show never leaves a message nothing owns")
    void neverOrphansABrandNewSlot() throws Exception {
        for (int round = 0; round < 2_000; round++) {
            setUp();

            CountDownLatch go = new CountDownLatch(1);
            Thread ticker = new Thread(() -> {
                awaitQuietly(go);
                bars.tick();
            });
            Thread shower = new Thread(() -> {
                awaitQuietly(go);
                bars.countdown(ALICE, "tpa", Duration.ofSeconds(5), ActionBarPriority.NORMAL,
                        remaining -> Component.text("Teleporting in " + Math.ceilDiv(remaining, 1000)));
            });
            ticker.start();
            shower.start();
            go.countDown();
            ticker.join();
            shower.join();

            assertThat(bars.isShowingAnything(ALICE))
                    .as("round %d: the countdown was started, so it has to be tracked — otherwise it "
                            + "renders one frame and freezes", round)
                    .isTrue();
            assertThat(bars.trackedPlayers()).as("round %d", round).contains(ALICE);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ misuse

    @Nested
    @DisplayName("misuse")
    class Misuse {

        @Test
        @DisplayName("a null message clears that owner rather than showing nothing")
        void nullIsAClear() {
            bars.show(ALICE, "flight", Component.text("cruising"), ActionBars.UNTIL_CLEARED,
                    ActionBarPriority.NORMAL);
            bars.show(ALICE, "flight", null, ActionBars.UNTIL_CLEARED, ActionBarPriority.NORMAL);
            assertThat(bars.isShowingAnything(ALICE)).isFalse();
        }

        @Test
        @DisplayName("a nameless owner is refused rather than becoming a shared bucket")
        void refusesABlankOwner() {
            bars.show(ALICE, "  ", Component.text("who am I"), ActionBars.UNTIL_CLEARED,
                    ActionBarPriority.NORMAL);
            assertThat(bars.isShowingAnything(ALICE)).isFalse();
        }

        @Test
        @DisplayName("a null player is ignored rather than throwing inside a listener")
        void ignoresANullPlayer() {
            bars.show(null, "claims", Component.text("nobody"), ActionBars.UNTIL_CLEARED,
                    ActionBarPriority.NORMAL);
            assertThat(bars.trackedPlayers()).isEmpty();
        }

        @Test
        @DisplayName("a negative duration is treated as already expired, not as for ever")
        void refusesNegativeDurations() {
            bars.show(ALICE, "claims", Component.text("oops"), Duration.ofSeconds(-5),
                    ActionBarPriority.NORMAL);
            assertThat(bars.isShowingAnything(ALICE)).isFalse();
        }

        @Test
        @DisplayName("a sink that throws costs that one send, not the tick")
        void survivesABrokenSink() {
            sink.explode = true;
            bars.show(ALICE, "claims", Component.text("boom"), Duration.ofSeconds(5),
                    ActionBarPriority.NORMAL);
            bars.show(BOB, "claims", Component.text("fine"), Duration.ofSeconds(5),
                    ActionBarPriority.NORMAL);
            bars.tick();
            assertThat(sink.failures).isNotEmpty();
        }
    }

    /** Stands in for the server: remembers what each player was last sent. */
    private static final class RecordingSink implements ActionBarSink {
        private final Map<UUID, Component> last = new LinkedHashMap<>();
        private final List<UUID> sends = new ArrayList<>();
        private final List<UUID> failures = new ArrayList<>();
        private boolean explode;

        @Override
        public synchronized void send(UUID player, Component message) {
            if (explode) {
                failures.add(player);
                throw new IllegalStateException("the player logged out mid-send");
            }
            last.put(player, message);
            sends.add(player);
        }

        synchronized void reset() {
            sends.clear();
        }
    }
}
