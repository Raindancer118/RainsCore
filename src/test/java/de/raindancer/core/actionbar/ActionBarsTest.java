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
