package de.raindancer.core.world.farm;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What is remembered about a farm world between restarts, and what may be deleted.
 *
 * <h2>Why the deletion rules are tested this hard</h2>
 * Regenerating a farm world deletes a directory. Everything else in this library can be wrong and
 * cost somebody an evening; this can be wrong and cost them their server. So the check that a path
 * is one we are allowed to remove is a pure function with its own tests, and it is deliberately
 * suspicious: it refuses anything outside the server directory, anything that is not a world folder
 * we manage, and anything reached through a link.
 */
class FarmWorldStateTest {

    private Database openedDatabase;

    /** One database per test, opened on first use so the temporary directory already exists. */
    private Database database() {
        if (openedDatabase == null || !openedDatabase.isUsable()) {
            openedDatabase = Database.open(serverDirectory.resolve("core.db"), CoreSchema.CORE,
                    () -> false);
        }
        return openedDatabase;
    }

    @AfterEach
    void closeDatabase() {
        if (openedDatabase != null) {
            openedDatabase.close();
        }
    }

    @TempDir
    Path serverDirectory;
    private FarmWorldState state;

    @BeforeEach
    void setUp() {
        state = new FarmWorldState(serverDirectory.resolve("farmworlds.yml"), database());
    }

    // ------------------------------------------------------------------ what is remembered

    @Nested
    @DisplayName("remembering a farm world")
    class Remembering {

        @Test
        @DisplayName("a set can be added and found again")
        void keepsSets() {
            state.define(WorldSet.builder("farmworld").every(Duration.ofDays(7)).build());
            assertThat(state.byName("farmworld")).isPresent();
            assertThat(state.all()).hasSize(1);
        }

        @Test
        @DisplayName("when it was last made is remembered, so the schedule survives a restart")
        void remembersWhenItWasMade() {
            state.define(WorldSet.of("farmworld"));
            Instant when = Instant.ofEpochSecond(1_700_000_000);
            state.recordRegenerated("farmworld", when);

            assertThat(state.lastRegenerated("farmworld")).contains(when);
        }

        @Test
        @DisplayName("a set nobody has made yet has no date")
        void noDateBeforeItIsMade() {
            state.define(WorldSet.of("farmworld"));
            assertThat(state.lastRegenerated("farmworld")).isEmpty();
        }

        @Test
        @DisplayName("everything survives a restart")
        void roundTrips() {
            state.define(WorldSet.builder("farmworld")
                    .every(Duration.ofDays(7)).border(5000).seed(123L).build());
            state.recordRegenerated("farmworld", Instant.ofEpochSecond(1_700_000_000));
            state.flush();

            // Closed and reopened over the same file and database, because that is what a restart
            // is — and this store has two halves, so both have to survive it.
            openedDatabase.close();
            FarmWorldState reopened = new FarmWorldState(
                    serverDirectory.resolve("farmworlds.yml"), database());
            reopened.load();

            WorldSet set = reopened.byName("farmworld").orElseThrow();
            assertThat(set.regenerateEvery()).contains(Duration.ofDays(7));
            assertThat(set.border()).contains(5000);
            assertThat(set.nextSeed()).isEqualTo(123L);
            assertThat(reopened.lastRegenerated("farmworld"))
                    .contains(Instant.ofEpochSecond(1_700_000_000));
        }

        @Test
        @DisplayName("which sets are due can be asked, for the timer")
        void listsWhatIsDue() {
            state.define(WorldSet.builder("weekly").every(Duration.ofDays(7)).build());
            state.define(WorldSet.of("manual"));
            Instant now = Instant.ofEpochSecond(1_700_000_000);
            state.recordRegenerated("weekly", now.minus(Duration.ofDays(8)));

            assertThat(state.due(now)).extracting(WorldSet::name).containsExactly("weekly");
        }

        /**
         * Raised in review: a regeneration that failed was still recorded as having happened, so
         * the schedule reset and the farm world stayed depleted for another full week with nothing
         * in the log after the first complaint. A failure now retries sooner instead.
         */
        @Test
        @DisplayName("a failed attempt does not reset the schedule, but does space out the retries")
        void aFailedAttemptRetriesSooner() {
            state.define(WorldSet.builder("farmworld").every(Duration.ofDays(7)).build());
            Instant now = Instant.ofEpochSecond(1_700_000_000);
            state.recordRegenerated("farmworld", now.minus(Duration.ofDays(8)));
            assertThat(state.due(now)).hasSize(1);

            state.recordAttempt("farmworld", now);

            assertThat(state.due(now.plus(Duration.ofMinutes(1))))
                    .as("it must not hammer a set that cannot be made")
                    .isEmpty();
            assertThat(state.due(now.plus(FarmWorldState.RETRY_AFTER).plusSeconds(1)))
                    .as("but it must try again, rather than waiting out the whole week")
                    .hasSize(1);
        }

        @Test
        @DisplayName("a successful regeneration resets the whole schedule")
        void successResetsTheSchedule() {
            state.define(WorldSet.builder("farmworld").every(Duration.ofDays(7)).build());
            Instant now = Instant.ofEpochSecond(1_700_000_000);
            state.recordAttempt("farmworld", now.minus(Duration.ofDays(1)));
            state.recordRegenerated("farmworld", now);

            assertThat(state.due(now.plus(Duration.ofDays(1)))).isEmpty();
            assertThat(state.due(now.plus(Duration.ofDays(8)))).hasSize(1);
        }

        @Test
        @DisplayName("a set can be forgotten")
        void undefines() {
            state.define(WorldSet.of("farmworld"));
            assertThat(state.undefine("farmworld")).isTrue();
            assertThat(state.all()).isEmpty();
        }

        @Test
        @DisplayName("a missing file is simply no farm worlds")
        void survivesAMissingFile() {
            FarmWorldState fresh = new FarmWorldState(serverDirectory.resolve("nothing.yml"),
                    Database.open(serverDirectory.resolve("never-used.db"), CoreSchema.CORE,
                            () -> false));
            assertThatCode(fresh::load).doesNotThrowAnyException();
            assertThat(fresh.all()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ what may be deleted

    /**
     * The rules that stop this deleting a server. Each one is a mistake somebody could plausibly
     * make with a command, and each would be unrecoverable.
     */
    @Nested
    @DisplayName("which directories may be deleted")
    class Deletion {

        @Test
        @DisplayName("a world folder of a set we manage, inside the server directory")
        void allowsOurOwn() throws IOException {
            Path folder = serverDirectory.resolve("farmworld");
            Files.createDirectories(folder.resolve("region"));
            Files.writeString(folder.resolve("level.dat"), "x");

            assertThat(FarmWorldState.mayDelete(serverDirectory, folder, "farmworld")).isTrue();
        }

        @Test
        @DisplayName("never anything outside the server directory")
        void refusesOutsideTheServer() throws IOException {
            Path elsewhere = serverDirectory.getParent().resolve("somewhere-else");
            Files.createDirectories(elsewhere);
            assertThat(FarmWorldState.mayDelete(serverDirectory, elsewhere, "somewhere-else"))
                    .isFalse();
        }

        @Test
        @DisplayName("never a path that climbs out with ..")
        void refusesTraversal() {
            Path escaping = serverDirectory.resolve("farmworld").resolve("..").resolve("..");
            assertThat(FarmWorldState.mayDelete(serverDirectory, escaping, "farmworld")).isFalse();
        }

        @Test
        @DisplayName("never a folder whose name is not the world's")
        void refusesTheWrongFolder() throws IOException {
            Path plugins = serverDirectory.resolve("plugins");
            Files.createDirectories(plugins);
            assertThat(FarmWorldState.mayDelete(serverDirectory, plugins, "farmworld")).isFalse();
        }

        @Test
        @DisplayName("never the server directory itself")
        void refusesTheServerRoot() {
            assertThat(FarmWorldState.mayDelete(serverDirectory, serverDirectory, "farmworld"))
                    .isFalse();
        }

        @Test
        @DisplayName("never something that is not a directory")
        void refusesAFile() throws IOException {
            Path file = serverDirectory.resolve("farmworld");
            Files.writeString(file, "not a world");
            assertThat(FarmWorldState.mayDelete(serverDirectory, file, "farmworld")).isFalse();
        }

        @Test
        @DisplayName("never a folder that is not a world — no level.dat, no deletion")
        void refusesANonWorld() throws IOException {
            Path folder = serverDirectory.resolve("farmworld");
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("something.txt"), "not a world");

            assertThat(FarmWorldState.mayDelete(serverDirectory, folder, "farmworld"))
                    .as("a folder somebody happened to name after the world is not the world")
                    .isFalse();
        }

        /**
         * Raised in review as too strict, because somebody may reasonably point a farm world at a
         * RAM disk. Kept strict deliberately — deleting through a link is exactly how a recursive
         * delete reaches somewhere nobody meant — but it now says so in the log rather than
         * skipping the world for ever without explaining why.
         */
        @Test
        @DisplayName("never through a link, even one pointing at a real world folder")
        void refusesASymlink() throws IOException {
            Path real = serverDirectory.resolve("elsewhere");
            Files.createDirectories(real);
            Files.writeString(real.resolve("level.dat"), "x");
            Path link = serverDirectory.resolve("farmworld");
            try {
                Files.createSymbolicLink(link, real);
            } catch (UnsupportedOperationException | IOException notSupported) {
                return; // No links on this filesystem; nothing to assert.
            }
            assertThat(FarmWorldState.mayDelete(serverDirectory, link, "farmworld"))
                    .as("a recursive delete that follows a link is how the wrong thing gets removed")
                    .isFalse();
        }

        @Test
        @DisplayName("nulls are refused rather than throwing inside a delete")
        void refusesNulls() {
            assertThat(FarmWorldState.mayDelete(null, null, null)).isFalse();
            assertThat(FarmWorldState.mayDelete(serverDirectory, null, "farmworld")).isFalse();
        }
    }

    // ------------------------------------------------------------------ what gets deleted

    @Test
    @DisplayName("a set's folders are named, so nothing else is ever passed to a delete")
    void namesOnlyItsOwnFolders() {
        WorldSet farm = WorldSet.of("farmworld");
        List<String> folders = farm.worlds();
        assertThat(folders).containsExactly("farmworld", "farmworld_nether", "farmworld_the_end");
        assertThat(folders).allSatisfy(name ->
                assertThat(name).startsWith("farmworld"));
    }
}
