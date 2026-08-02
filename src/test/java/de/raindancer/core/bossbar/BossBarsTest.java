package de.raindancer.core.bossbar;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Boss bars, and how many of them a player should have to look at.
 *
 * <h2>Why this is not the same problem as the action bar</h2>
 * A player has one action bar and one sidebar, so those are winner-takes-all. Boss bars <em>stack</em>:
 * the client will happily draw six of them down the top of the screen until there is no screen left.
 * So the question here is not "who wins" but "how many, and which" — a cap, and a ranking to decide
 * what fills it. Everything below that cap is shown; everything above it waits, and appears the
 * moment something ahead of it goes away.
 *
 * <h2>The shared bar</h2>
 * The other thing the action bar never had to deal with. A ghast flight's bar belongs to the flight,
 * not to a player: everybody aboard sees the same one, people get on and off mid-journey, and the
 * bug that taught this lesson was that somebody who got off kept a bar that never moved again. So a
 * bar has an audience, membership changes are ordinary, and taking the bar away takes it away from
 * everyone still holding it.
 */
class BossBarsTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());
    private static final UUID CAROL = UUID.nameUUIDFromBytes("carol".getBytes());

    private FakeViewers viewers;
    private BossBars bars;

    @BeforeEach
    void setUp() {
        viewers = new FakeViewers();
        bars = new BossBars(viewers);
    }

    private List<String> shownTo(UUID player) {
        return viewers.showing.getOrDefault(player, Set.of()).stream()
                .map(bar -> PlainTextComponentSerializer.plainText().serialize(bar.name()))
                .toList();
    }

    private static BarStyle style(String title) {
        return BarStyle.of(Component.text(title));
    }

    // ------------------------------------------------------------------ one bar

    @Nested
    @DisplayName("showing a bar")
    class Showing {

        @Test
        @DisplayName("appears at once, with what was asked for")
        void showsAtOnce() {
            bars.show(ALICE, "ghasts", style("Flight to the market").progress(0.25f),
                    BarPriority.NORMAL);
            assertThat(shownTo(ALICE)).containsExactly("Flight to the market");
        }

        @Test
        @DisplayName("updating changes the bar in place rather than making a second one")
        void updatesInPlace() {
            bars.show(ALICE, "ghasts", style("Boarding").progress(0f), BarPriority.NORMAL);
            bars.show(ALICE, "ghasts", style("Cruising").progress(0.5f), BarPriority.NORMAL);

            assertThat(shownTo(ALICE)).containsExactly("Cruising");
            assertThat(viewers.distinctBars())
                    .as("a bar rebuilt on every update flickers and loses its animation")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("progress, colour and overlay all reach the bar")
        void carriesItsLook() {
            bars.show(ALICE, "ghasts", style("Flight")
                            .progress(0.75f)
                            .colour(BossBar.Color.BLUE)
                            .overlay(BossBar.Overlay.NOTCHED_10),
                    BarPriority.NORMAL);

            BossBar shown = viewers.showing.get(ALICE).iterator().next();
            assertThat(shown.progress()).isEqualTo(0.75f);
            assertThat(shown.color()).isEqualTo(BossBar.Color.BLUE);
            assertThat(shown.overlay()).isEqualTo(BossBar.Overlay.NOTCHED_10);
        }

        @Test
        @DisplayName("progress is kept inside 0..1, because Adventure throws outside it")
        void clampsProgress() {
            assertThatCode(() -> {
                bars.show(ALICE, "a", style("Over").progress(4f), BarPriority.NORMAL);
                bars.show(ALICE, "b", style("Under").progress(-2f), BarPriority.NORMAL);
            }).doesNotThrowAnyException();

            assertThat(viewers.showing.get(ALICE))
                    .allSatisfy(bar -> assertThat(bar.progress()).isBetween(0f, 1f));
        }

        @Test
        @DisplayName("clearing takes it away")
        void clears() {
            bars.show(ALICE, "ghasts", style("Flight"), BarPriority.NORMAL);
            bars.clear(ALICE, "ghasts");
            assertThat(shownTo(ALICE)).isEmpty();
        }

        @Test
        @DisplayName("an unchanged bar is not re-sent")
        void doesNotRepeatItself() {
            BarStyle same = style("Flight").progress(0.5f);
            bars.show(ALICE, "ghasts", same, BarPriority.NORMAL);
            int before = bars.updateCount();
            bars.show(ALICE, "ghasts", same, BarPriority.NORMAL);
            assertThat(bars.updateCount() - before)
                    .as("a flight ticking its bar must not cost a packet a tick when nothing moved")
                    .isZero();
        }
    }

    // ------------------------------------------------------------------ the cap

    @Nested
    @DisplayName("when several plugins want a bar")
    class Stacking {

        @Test
        @DisplayName("more than one is shown, because boss bars stack")
        void showsSeveral() {
            bars.show(ALICE, "ghasts", style("Flight"), BarPriority.NORMAL);
            bars.show(ALICE, "claims", style("Entering a claim"), BarPriority.NORMAL);
            assertThat(shownTo(ALICE)).containsExactlyInAnyOrder("Flight", "Entering a claim");
        }

        @Test
        @DisplayName("no more than the cap, whatever the plugins ask for")
        void stopsAtTheCap() {
            for (int owner = 0; owner < BossBars.MAX_VISIBLE + 3; owner++) {
                bars.show(ALICE, "owner-" + owner, style("Bar " + owner), BarPriority.NORMAL);
            }
            assertThat(shownTo(ALICE))
                    .as("the screen is not a list; past a handful nobody reads any of them")
                    .hasSize(BossBars.MAX_VISIBLE);
        }

        @Test
        @DisplayName("the highest priorities fill the cap")
        void priorityDecidesWhoIsShown() {
            for (int owner = 0; owner < BossBars.MAX_VISIBLE; owner++) {
                bars.show(ALICE, "low-" + owner, style("Low " + owner), BarPriority.LOW);
            }
            bars.show(ALICE, "urgent", style("The world is closing"), BarPriority.CRITICAL);

            assertThat(shownTo(ALICE))
                    .hasSize(BossBars.MAX_VISIBLE)
                    .contains("The world is closing");
        }

        @Test
        @DisplayName("a bar pushed out comes back when something ahead of it goes")
        void waitsItsTurn() {
            bars.show(ALICE, "waiting", style("Waiting"), BarPriority.LOW);
            for (int owner = 0; owner < BossBars.MAX_VISIBLE; owner++) {
                bars.show(ALICE, "high-" + owner, style("High " + owner), BarPriority.HIGH);
            }
            assertThat(shownTo(ALICE)).doesNotContain("Waiting");

            bars.clear(ALICE, "high-0");

            assertThat(shownTo(ALICE))
                    .as("it was never withdrawn, only crowded out")
                    .contains("Waiting");
        }

        @Test
        @DisplayName("one owner has one bar per player")
        void oneBarPerOwner() {
            bars.show(ALICE, "ghasts", style("First"), BarPriority.NORMAL);
            bars.show(ALICE, "ghasts", style("Second"), BarPriority.NORMAL);
            assertThat(shownTo(ALICE)).containsExactly("Second");
        }
    }

    // ------------------------------------------------------------------ shared bars

    /**
     * The ghast-flight case: one bar, an audience that changes while it runs.
     */
    @Nested
    @DisplayName("a bar shared by several players")
    class Shared {

        @Test
        @DisplayName("everybody in the audience sees the same bar")
        void everybodySeesIt() {
            bars.showShared("ghasts", "flight-7", List.of(ALICE, BOB),
                    style("Flight to the market"), BarPriority.NORMAL);

            assertThat(shownTo(ALICE)).containsExactly("Flight to the market");
            assertThat(shownTo(BOB)).containsExactly("Flight to the market");
            assertThat(viewers.distinctBars())
                    .as("one bar, not one per passenger")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("somebody who joins mid-journey is given it")
        void latecomersGetIt() {
            bars.showShared("ghasts", "flight-7", List.of(ALICE),
                    style("Flight"), BarPriority.NORMAL);
            bars.showShared("ghasts", "flight-7", List.of(ALICE, BOB),
                    style("Flight"), BarPriority.NORMAL);

            assertThat(shownTo(BOB)).containsExactly("Flight");
        }

        /**
         * The bug this exists to prevent: somebody who got off kept a bar that never moved again,
         * because nothing took it away from them when they left the audience.
         */
        @Test
        @DisplayName("somebody who leaves mid-journey has it taken away")
        void leaversLoseIt() {
            bars.showShared("ghasts", "flight-7", List.of(ALICE, BOB),
                    style("Flight"), BarPriority.NORMAL);
            bars.showShared("ghasts", "flight-7", List.of(ALICE),
                    style("Flight"), BarPriority.NORMAL);

            assertThat(shownTo(ALICE)).containsExactly("Flight");
            assertThat(shownTo(BOB))
                    .as("a bar nobody updates any more is worse than no bar")
                    .isEmpty();
        }

        @Test
        @DisplayName("clearing it takes it from everybody still holding it")
        void clearingTakesItFromEverybody() {
            bars.showShared("ghasts", "flight-7", List.of(ALICE, BOB, CAROL),
                    style("Flight"), BarPriority.NORMAL);
            bars.clearShared("ghasts", "flight-7");

            assertThat(shownTo(ALICE)).isEmpty();
            assertThat(shownTo(BOB)).isEmpty();
            assertThat(shownTo(CAROL)).isEmpty();
        }

        @Test
        @DisplayName("two shared bars from one plugin are told apart by their id")
        void severalSharedBarsCoexist() {
            bars.showShared("ghasts", "flight-7", List.of(ALICE), style("Flight 7"),
                    BarPriority.NORMAL);
            bars.showShared("ghasts", "flight-8", List.of(BOB), style("Flight 8"),
                    BarPriority.NORMAL);

            assertThat(shownTo(ALICE)).containsExactly("Flight 7");
            assertThat(shownTo(BOB)).containsExactly("Flight 8");

            bars.clearShared("ghasts", "flight-7");
            assertThat(shownTo(BOB)).containsExactly("Flight 8");
        }

        @Test
        @DisplayName("an audience of nobody takes the bar away rather than leaving it hanging")
        void anEmptyAudienceEndsIt() {
            bars.showShared("ghasts", "flight-7", List.of(ALICE), style("Flight"),
                    BarPriority.NORMAL);
            bars.showShared("ghasts", "flight-7", List.of(), style("Flight"), BarPriority.NORMAL);

            assertThat(shownTo(ALICE)).isEmpty();
            assertThat(bars.sharedBars()).isZero();
        }
    }

    // ------------------------------------------------------------------ housekeeping

    @Nested
    @DisplayName("housekeeping")
    class Housekeeping {

        @Test
        @DisplayName("a player who logs out is forgotten, and taken out of shared audiences")
        void forgetsPlayers() {
            bars.show(ALICE, "claims", style("Claim"), BarPriority.NORMAL);
            bars.showShared("ghasts", "flight-7", List.of(ALICE, BOB), style("Flight"),
                    BarPriority.NORMAL);

            bars.forget(ALICE);

            assertThat(bars.trackedPlayers()).doesNotContain(ALICE);
            assertThat(shownTo(BOB))
                    .as("one passenger leaving must not end everybody else's flight")
                    .containsExactly("Flight");
        }

        @Test
        @DisplayName("shutting down takes every bar away")
        void closesEverything() {
            bars.show(ALICE, "claims", style("Claim"), BarPriority.NORMAL);
            bars.showShared("ghasts", "flight-7", List.of(BOB), style("Flight"),
                    BarPriority.NORMAL);

            bars.shutdown();

            assertThat(shownTo(ALICE)).isEmpty();
            assertThat(shownTo(BOB)).isEmpty();
            assertThat(bars.trackedPlayers()).isEmpty();
            assertThat(bars.sharedBars()).isZero();
        }

        @Test
        @DisplayName("clearing something that was never shown is harmless")
        void clearingNothingIsFine() {
            assertThatCode(() -> {
                bars.clear(ALICE, "nobody");
                bars.clearShared("nobody", "nothing");
                bars.forget(CAROL);
            }).doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------ misuse

    @Nested
    @DisplayName("misuse")
    class Misuse {

        @Test
        @DisplayName("nulls are refused rather than thrown")
        void refusesNulls() {
            assertThatCode(() -> {
                bars.show(null, "ghasts", style("x"), BarPriority.NORMAL);
                bars.show(ALICE, null, style("x"), BarPriority.NORMAL);
                bars.show(ALICE, "  ", style("x"), BarPriority.NORMAL);
                bars.show(ALICE, "ghasts", null, BarPriority.NORMAL);
                bars.showShared(null, null, null, null, null);
            }).doesNotThrowAnyException();
            assertThat(bars.trackedPlayers()).isEmpty();
        }

        @Test
        @DisplayName("a viewer that throws costs that player, not everybody else's bar")
        void survivesABrokenViewer() {
            viewers.failFor = BOB;
            bars.showShared("ghasts", "flight-7", List.of(ALICE, BOB), style("Flight"),
                    BarPriority.NORMAL);
            assertThat(shownTo(ALICE)).containsExactly("Flight");
        }
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    @DisplayName("showing from many threads at once never throws")
    void isSafeFromEveryThread() throws Exception {
        int threads = 8;
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int thread = 0; thread < threads; thread++) {
                int id = thread;
                pool.submit(() -> {
                    go.await();
                    for (int round = 0; round < 150; round++) {
                        bars.show(ALICE, "owner-" + id,
                                style("Bar " + id).progress(round / 150f), BarPriority.NORMAL);
                        bars.showShared("ghasts", "flight-" + id, List.of(ALICE, BOB),
                                style("Flight " + id), BarPriority.NORMAL);
                    }
                    bars.clear(ALICE, "owner-" + id);
                    bars.clearShared("ghasts", "flight-" + id);
                    return null;
                });
            }
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(viewers.failures).isEmpty();
        assertThat(shownTo(ALICE)).isEmpty();
        assertThat(shownTo(BOB)).isEmpty();
    }

    // ------------------------------------------------------------------ the fake client

    /** Stands in for the players: remembers which bars each is currently being shown. */
    private static final class FakeViewers implements BarViewers {
        private final Map<UUID, Set<BossBar>> showing = new LinkedHashMap<>();
        private final List<String> failures = new ArrayList<>();
        /** Every bar instance ever shown, by identity — so "was it rebuilt?" can be asked. */
        private final Set<BossBar> everShown =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        private UUID failFor;

        synchronized int distinctBars() {
            return everShown.size();
        }

        @Override
        public synchronized void show(UUID player, BossBar bar) {
            if (player.equals(failFor)) {
                failures.add("show " + player);
                throw new IllegalStateException("the player went away");
            }
            everShown.add(bar);
            showing.computeIfAbsent(player, key -> new LinkedHashSet<>()).add(bar);
        }

        @Override
        public synchronized void hide(UUID player, BossBar bar) {
            Set<BossBar> open = showing.get(player);
            if (open != null) {
                open.remove(bar);
                if (open.isEmpty()) {
                    showing.remove(player);
                }
            }
        }

    }
}
