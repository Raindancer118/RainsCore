package de.raindancer.core.ui.scoreboard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The sidebar, and who owns it.
 *
 * <h2>Why this is not just a thin wrapper over the copied-in FastBoard</h2>
 * FastBoard solves the hard half — writing scoreboard packets directly, so the board does not
 * flicker, can be written from any thread, and does not fight other plugins over Bukkit's team API.
 * What it deliberately does not do is decide <em>who gets the sidebar</em>, because that is not a
 * library's business. On this server it is: a player has one sidebar, and the claims module wanting
 * to show a claim's name while a ghast flight wants to show its progress is the same collision the
 * action bar had, with the same answer — an owner, a priority, and a fallback when the winner goes
 * away.
 *
 * <p>The other half is failure. FastBoard is raw reflection into the server's internals: on a Paper
 * build it does not recognise, merely <em>touching</em> the class throws
 * {@link ExceptionInInitializerError} from a static block. Unwrapped, that takes down whatever
 * listener touched it. A scoreboard is decoration; nothing about it is worth a broken plugin. So
 * every call through here is allowed to degrade to doing nothing, loudly, in the log.
 */
class ScoreboardsTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private FakeBoards boards;
    private Scoreboards scoreboards;

    @BeforeEach
    void setUp() {
        boards = new FakeBoards();
        scoreboards = new Scoreboards(boards);
    }

    private static List<String> plain(List<Component> lines) {
        return lines.stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
    }

    private String titleFor(UUID player) {
        FakeBoard board = boards.open.get(player);
        return board == null ? null
                : PlainTextComponentSerializer.plainText().serialize(board.title);
    }

    private List<String> linesFor(UUID player) {
        FakeBoard board = boards.open.get(player);
        return board == null ? List.of() : plain(board.lines);
    }

    private static Sidebar sidebar(String title, String... lines) {
        return Sidebar.of(Component.text(title),
                java.util.Arrays.stream(lines).map(Component::text).map(Component.class::cast).toList());
    }

    // ------------------------------------------------------------------ showing one

    @Nested
    @DisplayName("showing a sidebar")
    class Showing {

        @Test
        @DisplayName("creates the board the first time and shows what was asked for")
        void showsAtOnce() {
            scoreboards.show(ALICE, "claims", sidebar("Your claim", "base", "120 blocks"),
                    ScoreboardPriority.NORMAL);

            assertThat(titleFor(ALICE)).isEqualTo("Your claim");
            assertThat(linesFor(ALICE)).containsExactly("base", "120 blocks");
            assertThat(boards.created).isEqualTo(1);
        }

        @Test
        @DisplayName("reuses the same board rather than making one per update")
        void reusesTheBoard() {
            scoreboards.show(ALICE, "claims", sidebar("Your claim", "base"),
                    ScoreboardPriority.NORMAL);
            scoreboards.show(ALICE, "claims", sidebar("Your claim", "market"),
                    ScoreboardPriority.NORMAL);

            assertThat(boards.created)
                    .as("a board rebuilt on every update is a board that flickers")
                    .isEqualTo(1);
            assertThat(linesFor(ALICE)).containsExactly("market");
        }

        @Test
        @DisplayName("an unchanged sidebar is not sent again")
        void doesNotRepeatItself() {
            Sidebar same = sidebar("Your claim", "base");
            scoreboards.show(ALICE, "claims", same, ScoreboardPriority.NORMAL);
            boards.open.get(ALICE).updates = 0;

            scoreboards.show(ALICE, "claims", same, ScoreboardPriority.NORMAL);

            assertThat(boards.open.get(ALICE).updates)
                    .as("a plugin ticking the same content must not cost a packet a tick")
                    .isZero();
        }

        @Test
        @DisplayName("clearing the last owner takes the board away entirely")
        void hidesWhenNobodyWantsIt() {
            scoreboards.show(ALICE, "claims", sidebar("Your claim", "base"),
                    ScoreboardPriority.NORMAL);
            scoreboards.clear(ALICE, "claims");

            assertThat(boards.open).doesNotContainKey(ALICE);
            assertThat(boards.deleted).containsExactly(ALICE);
        }

        @Test
        @DisplayName("players do not see each other's sidebars")
        void playersAreIndependent() {
            scoreboards.show(ALICE, "claims", sidebar("Alice"), ScoreboardPriority.NORMAL);
            scoreboards.show(BOB, "claims", sidebar("Bob"), ScoreboardPriority.NORMAL);

            assertThat(titleFor(ALICE)).isEqualTo("Alice");
            assertThat(titleFor(BOB)).isEqualTo("Bob");
        }
    }

    // ------------------------------------------------------------------ arbitration

    @Nested
    @DisplayName("when two plugins want the sidebar")
    class Arbitration {

        @Test
        @DisplayName("the higher priority wins, whichever asked first")
        void higherPriorityWins() {
            scoreboards.show(ALICE, "claims", sidebar("Your claim", "base"),
                    ScoreboardPriority.LOW);
            scoreboards.show(ALICE, "ghasts", sidebar("Flight", "to the market"),
                    ScoreboardPriority.HIGH);

            assertThat(titleFor(ALICE)).isEqualTo("Flight");

            setUp();
            scoreboards.show(ALICE, "ghasts", sidebar("Flight", "to the market"),
                    ScoreboardPriority.HIGH);
            scoreboards.show(ALICE, "claims", sidebar("Your claim", "base"),
                    ScoreboardPriority.LOW);

            assertThat(titleFor(ALICE)).isEqualTo("Flight");
        }

        @Test
        @DisplayName("what was interrupted comes back when the winner goes away")
        void fallsBackWhenTheWinnerLeaves() {
            scoreboards.show(ALICE, "claims", sidebar("Your claim", "base"),
                    ScoreboardPriority.LOW);
            scoreboards.show(ALICE, "ghasts", sidebar("Flight", "to the market"),
                    ScoreboardPriority.HIGH);

            scoreboards.clear(ALICE, "ghasts");

            assertThat(titleFor(ALICE))
                    .as("the claim did not stop existing because a flight interrupted it")
                    .isEqualTo("Your claim");
            assertThat(boards.deleted)
                    .as("the board is still wanted, so it must not have been taken away")
                    .isEmpty();
        }

        @Test
        @DisplayName("the loser can keep updating without stealing the sidebar back")
        void aHiddenOwnerStaysHidden() {
            scoreboards.show(ALICE, "ghasts", sidebar("Flight", "boarding"),
                    ScoreboardPriority.HIGH);
            scoreboards.show(ALICE, "claims", sidebar("Your claim", "base"),
                    ScoreboardPriority.LOW);
            scoreboards.show(ALICE, "claims", sidebar("Your claim", "market"),
                    ScoreboardPriority.LOW);

            assertThat(titleFor(ALICE)).isEqualTo("Flight");
        }

        @Test
        @DisplayName("one owner has one sidebar: showing again replaces its own")
        void oneSidebarPerOwner() {
            scoreboards.show(ALICE, "claims", sidebar("First"), ScoreboardPriority.NORMAL);
            scoreboards.show(ALICE, "claims", sidebar("Second"), ScoreboardPriority.NORMAL);
            scoreboards.clear(ALICE, "claims");

            assertThat(scoreboards.isShowingAnything(ALICE))
                    .as("two shows from one owner must not leave a second sidebar behind")
                    .isFalse();
        }

        @Test
        @DisplayName("who currently owns it can be asked, for a diagnostic command")
        void reportsTheOwner() {
            assertThat(scoreboards.ownerOf(ALICE)).isEmpty();
            scoreboards.show(ALICE, "claims", sidebar("Your claim"), ScoreboardPriority.LOW);
            assertThat(scoreboards.ownerOf(ALICE)).contains("claims");
            scoreboards.show(ALICE, "ghasts", sidebar("Flight"), ScoreboardPriority.HIGH);
            assertThat(scoreboards.ownerOf(ALICE)).contains("ghasts");
        }
    }

    // ------------------------------------------------------------------ housekeeping

    @Nested
    @DisplayName("housekeeping")
    class Housekeeping {

        @Test
        @DisplayName("a player who logs out has their board deleted and forgotten")
        void forgetsPlayers() {
            scoreboards.show(ALICE, "claims", sidebar("Your claim"), ScoreboardPriority.NORMAL);
            scoreboards.forget(ALICE);

            assertThat(boards.deleted).containsExactly(ALICE);
            assertThat(scoreboards.trackedPlayers()).isEmpty();
            assertThat(scoreboards.isShowingAnything(ALICE)).isFalse();
        }

        @Test
        @DisplayName("forgetting somebody twice is harmless")
        void forgettingIsIdempotent() {
            scoreboards.show(ALICE, "claims", sidebar("Your claim"), ScoreboardPriority.NORMAL);
            scoreboards.forget(ALICE);
            assertThatCode(() -> scoreboards.forget(ALICE)).doesNotThrowAnyException();
            assertThat(boards.deleted).containsExactly(ALICE);
        }

        @Test
        @DisplayName("shutting down takes every board away, so a reload leaves nothing behind")
        void closesEverything() {
            scoreboards.show(ALICE, "claims", sidebar("Alice"), ScoreboardPriority.NORMAL);
            scoreboards.show(BOB, "claims", sidebar("Bob"), ScoreboardPriority.NORMAL);

            scoreboards.shutdown();

            assertThat(boards.deleted).containsExactlyInAnyOrder(ALICE, BOB);
            assertThat(scoreboards.trackedPlayers()).isEmpty();
        }

        @Test
        @DisplayName("clearing an owner that never showed anything is harmless")
        void clearingNothingIsFine() {
            assertThatCode(() -> scoreboards.clear(ALICE, "nobody")).doesNotThrowAnyException();
            assertThat(boards.created).isZero();
        }
    }

    // ------------------------------------------------------------------ degrading

    /**
     * Every one of these would, unwrapped, throw out of whatever listener called it. A sidebar is
     * decoration; none of it is worth taking a plugin down for.
     */
    @Nested
    @DisplayName("when the scoreboard cannot be drawn at all")
    class Degrading {

        @Test
        @DisplayName("a board that cannot be created is not a thrown exception")
        void survivesACreationFailure() {
            boards.failOnCreate = true;

            assertThatCode(() -> scoreboards.show(ALICE, "claims", sidebar("Your claim"),
                    ScoreboardPriority.NORMAL)).doesNotThrowAnyException();
            assertThat(scoreboards.isShowingAnything(ALICE)).isFalse();
        }

        @Test
        @DisplayName("a failure to create is not retried on every update for ever")
        void doesNotRetryForEver() {
            boards.failOnCreate = true;
            for (int attempt = 0; attempt < 20; attempt++) {
                scoreboards.show(ALICE, "claims", sidebar("Your claim"), ScoreboardPriority.NORMAL);
            }
            assertThat(boards.createAttempts)
                    .as("a plugin ticking a sidebar on a server that cannot draw one must not "
                            + "cost a reflection failure every tick")
                    .isLessThanOrEqualTo(2);
        }

        @Test
        @DisplayName("a board that breaks while updating is dropped rather than left broken")
        void survivesAnUpdateFailure() {
            scoreboards.show(ALICE, "claims", sidebar("Your claim", "base"),
                    ScoreboardPriority.NORMAL);
            boards.open.get(ALICE).failOnUpdate = true;

            assertThatCode(() -> scoreboards.show(ALICE, "claims", sidebar("Your claim", "market"),
                    ScoreboardPriority.NORMAL)).doesNotThrowAnyException();
            assertThat(scoreboards.isShowingAnything(ALICE)).isFalse();
        }

        @Test
        @DisplayName("a board that throws on the way out does not stop the shutdown")
        void survivesADeleteFailure() {
            scoreboards.show(ALICE, "claims", sidebar("Alice"), ScoreboardPriority.NORMAL);
            scoreboards.show(BOB, "claims", sidebar("Bob"), ScoreboardPriority.NORMAL);
            boards.open.get(ALICE).failOnDelete = true;

            assertThatCode(scoreboards::shutdown).doesNotThrowAnyException();
            assertThat(scoreboards.trackedPlayers())
                    .as("one player's broken board must not leave everybody else's behind")
                    .isEmpty();
        }

        @Test
        @DisplayName("a server where scoreboards do not work at all is reported once, not per call")
        void reportsBeingUnavailableOnce() {
            boards.failOnCreate = true;
            scoreboards.show(ALICE, "claims", sidebar("Your claim"), ScoreboardPriority.NORMAL);
            scoreboards.show(BOB, "claims", sidebar("Bob"), ScoreboardPriority.NORMAL);

            assertThat(scoreboards.isAvailable()).isFalse();
        }
    }

    // ------------------------------------------------------------------ misuse

    @Nested
    @DisplayName("misuse")
    class Misuse {

        @Test
        @DisplayName("a null player or a nameless owner is refused rather than throwing")
        void refusesNonsense() {
            scoreboards.show(null, "claims", sidebar("x"), ScoreboardPriority.NORMAL);
            scoreboards.show(ALICE, "  ", sidebar("x"), ScoreboardPriority.NORMAL);
            scoreboards.show(ALICE, "claims", null, ScoreboardPriority.NORMAL);

            assertThat(scoreboards.trackedPlayers()).isEmpty();
            assertThat(boards.created).isZero();
        }

        @Test
        @DisplayName("more lines than a sidebar can hold are cut, not refused")
        void clampsOverlongSidebars() {
            List<Component> many = new ArrayList<>();
            for (int line = 0; line < 30; line++) {
                many.add(Component.text("line " + line));
            }
            scoreboards.show(ALICE, "claims", Sidebar.of(Component.text("Long"), many),
                    ScoreboardPriority.NORMAL);

            assertThat(linesFor(ALICE))
                    .as("the client shows fifteen; the rest are not worth refusing the sidebar over")
                    .hasSize(Sidebar.MAX_LINES);
        }

        @Test
        @DisplayName("a sidebar with no lines is a title on its own, which is allowed")
        void allowsATitleOnly() {
            scoreboards.show(ALICE, "claims", sidebar("Just a title"), ScoreboardPriority.NORMAL);
            assertThat(titleFor(ALICE)).isEqualTo("Just a title");
            assertThat(linesFor(ALICE)).isEmpty();
        }
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    @DisplayName("showing from many threads at once never throws or loses the sidebar")
    void isSafeFromEveryThread() throws Exception {
        int threads = 8;
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int thread = 0; thread < threads; thread++) {
                int id = thread;
                pool.submit(() -> {
                    go.await();
                    for (int round = 0; round < 200; round++) {
                        scoreboards.show(ALICE, "owner-" + id,
                                sidebar("Title " + id, "line " + round), ScoreboardPriority.NORMAL);
                    }
                    scoreboards.clear(ALICE, "owner-" + id);
                    return null;
                });
            }
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(boards.failures).isEmpty();
        assertThat(scoreboards.isShowingAnything(ALICE))
                .as("every owner cleared up after itself, so nothing should be left")
                .isFalse();
    }

    // ------------------------------------------------------------------ the fake server

    /** Stands in for the packet layer, so every rule above is tested without a server. */
    private static final class FakeBoards implements BoardFactory {
        private final Map<UUID, FakeBoard> open = new LinkedHashMap<>();
        private final List<UUID> deleted = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();
        private int created;
        private int createAttempts;
        private boolean failOnCreate;

        @Override
        public synchronized Board create(UUID player) {
            createAttempts++;
            if (failOnCreate) {
                throw new IllegalStateException("this server's internals are not recognised");
            }
            created++;
            FakeBoard board = new FakeBoard(player, this);
            open.put(player, board);
            return board;
        }
    }

    private static final class FakeBoard implements Board {
        private final UUID player;
        private final FakeBoards owner;
        private Component title = Component.empty();
        private List<Component> lines = List.of();
        private int updates;
        private boolean failOnUpdate;
        private boolean failOnDelete;

        private FakeBoard(UUID player, FakeBoards owner) {
            this.player = player;
            this.owner = owner;
        }

        @Override
        public void update(Component newTitle, List<Component> newLines) {
            if (failOnUpdate) {
                throw new IllegalStateException("the player went away mid-packet");
            }
            title = newTitle;
            lines = List.copyOf(newLines);
            updates++;
        }

        @Override
        public void delete() {
            if (failOnDelete) {
                throw new IllegalStateException("cannot delete");
            }
            synchronized (owner) {
                owner.open.remove(player);
                owner.deleted.add(player);
            }
        }
    }
}
