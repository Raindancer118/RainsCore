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

            assertThat(FarmWorldState.mayDelete(serverDirectory, folder, "farmworld", "world")).isTrue();
        }

        @Test
        @DisplayName("never anything outside the server directory")
        void refusesOutsideTheServer() throws IOException {
            Path elsewhere = serverDirectory.getParent().resolve("somewhere-else");
            Files.createDirectories(elsewhere);
            assertThat(FarmWorldState.mayDelete(serverDirectory, elsewhere, "somewhere-else", "world"))
                    .isFalse();
        }

        @Test
        @DisplayName("never a path that climbs out with ..")
        void refusesTraversal() {
            Path escaping = serverDirectory.resolve("farmworld").resolve("..").resolve("..");
            assertThat(FarmWorldState.mayDelete(serverDirectory, escaping, "farmworld", "world")).isFalse();
        }

        @Test
        @DisplayName("never a folder whose name is not the world's")
        void refusesTheWrongFolder() throws IOException {
            Path plugins = serverDirectory.resolve("plugins");
            Files.createDirectories(plugins);
            assertThat(FarmWorldState.mayDelete(serverDirectory, plugins, "farmworld", "world")).isFalse();
        }

        @Test
        @DisplayName("never the server directory itself")
        void refusesTheServerRoot() {
            assertThat(FarmWorldState.mayDelete(serverDirectory, serverDirectory, "farmworld", "world"))
                    .isFalse();
        }

        @Test
        @DisplayName("never something that is not a directory")
        void refusesAFile() throws IOException {
            Path file = serverDirectory.resolve("farmworld");
            Files.writeString(file, "not a world");
            assertThat(FarmWorldState.mayDelete(serverDirectory, file, "farmworld", "world")).isFalse();
        }

        @Test
        @DisplayName("never a folder that is not a world — no level.dat, no deletion")
        void refusesANonWorld() throws IOException {
            Path folder = serverDirectory.resolve("farmworld");
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("something.txt"), "not a world");

            assertThat(FarmWorldState.mayDelete(serverDirectory, folder, "farmworld", "world"))
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
            assertThat(FarmWorldState.mayDelete(serverDirectory, link, "farmworld", "world"))
                    .as("a recursive delete that follows a link is how the wrong thing gets removed")
                    .isFalse();
        }

        @Test
        @DisplayName("nulls are refused rather than throwing inside a delete")
        void refusesNulls() {
            assertThat(FarmWorldState.mayDelete(null, null, null, "world")).isFalse();
            assertThat(FarmWorldState.mayDelete(serverDirectory, null, "farmworld", "world")).isFalse();
        }
    }

    // ------------------------------------------------------------------ where Paper actually puts them

    /**
     * The layout Paper 26.x uses for a world that is not one of the server's own three.
     *
     * <h2>The defect these were written for</h2>
     * Paper does not put a world created by {@code WorldCreator} beside the server's own — it puts it
     * under the main world at {@code <level-name>/dimensions/<namespace>/<name>}. This class only ever
     * allowed a folder sitting <em>directly</em> in the server directory, so every real farm world was
     * refused before anything was deleted.
     *
     * <p>Refused silently, which is what made it expensive: {@code FarmWorlds} only calls this when the
     * folder exists, the folder it built never did, so no refusal was ever logged. Regeneration
     * unloaded the world, deleted nothing, created it again from the surviving region files, and
     * announced success. Found on the test server by regenerating twice and checking that the terrain's
     * md5 had not moved.
     */
    @Nested
    @DisplayName("the dimensions layout Paper actually uses")
    class DimensionsLayout {

        /** {@code <server>/world/dimensions/minecraft/<name>}, with a level.dat in it. */
        private Path aDimensionWorld(String levelName, String namespace, String name)
                throws IOException {
            Path folder = serverDirectory.resolve(levelName)
                    .resolve("dimensions").resolve(namespace).resolve(name);
            Files.createDirectories(folder.resolve("region"));
            Files.writeString(folder.resolve("level.dat"), "x");
            return folder;
        }

        @Test
        @DisplayName("a farm world where Paper actually puts it may be deleted")
        void allowsTheDimensionsLayout() throws IOException {
            Path folder = aDimensionWorld("world", "minecraft", "farmworld");

            assertThat(FarmWorldState.mayDelete(serverDirectory, folder, "farmworld", "world"))
                    .as("this is the layout every real farm world has, and refusing it is why "
                            + "regeneration deleted nothing and said it had worked")
                    .isTrue();
        }

        @Test
        @DisplayName("the nether and the end of a farm world too")
        void allowsTheOtherTwoParts() throws IOException {
            Path nether = aDimensionWorld("world", "minecraft", "farmworld_nether");
            Path end = aDimensionWorld("world", "minecraft", "farmworld_the_end");

            assertThat(FarmWorldState.mayDelete(serverDirectory, nether, "farmworld_nether", "world")).isTrue();
            assertThat(FarmWorldState.mayDelete(serverDirectory, end, "farmworld_the_end", "world")).isTrue();
        }

        @Test
        @DisplayName("a server whose main world is not called 'world' works the same way")
        void theLevelNameIsNotAssumed() throws IOException {
            // level-name is a server.properties setting and plenty of servers change it. Hard-coding
            // "world" here would mean the fix worked on the test server and nowhere else.
            Path folder = aDimensionWorld("survival", "minecraft", "farmworld");

            assertThat(FarmWorldState.mayDelete(serverDirectory, folder, "farmworld", "world")).isTrue();
        }

        @Test
        @DisplayName("the old layout still works, because a world folder may be either")
        void theFlatLayoutStillWorks() throws IOException {
            Path folder = serverDirectory.resolve("farmworld");
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("level.dat"), "x");

            assertThat(FarmWorldState.mayDelete(serverDirectory, folder, "farmworld", "world"))
                    .as("widening this must not narrow it — a world moved by hand, or an older "
                            + "server, still sits directly in the server directory")
                    .isTrue();
        }

        // -------------------------------------------------------------- and what it must still refuse

        @Test
        @DisplayName("never the main world, even though it is now on the way to the farm worlds")
        void refusesTheMainWorld() throws IOException {
            // The one that would end a server. `world` holds `dimensions`, so any rule loose enough to
            // allow a folder *under* it has to still refuse the folder itself.
            Path main = serverDirectory.resolve("world");
            Files.createDirectories(main.resolve("region"));
            Files.writeString(main.resolve("level.dat"), "x");

            assertThat(FarmWorldState.mayDelete(serverDirectory, main, "world", "world")).isFalse();
        }

        @Test
        @DisplayName("never the dimensions folder itself, which holds every farm world at once")
        void refusesTheDimensionsFolder() throws IOException {
            aDimensionWorld("world", "minecraft", "farmworld");
            Path dimensions = serverDirectory.resolve("world").resolve("dimensions");
            // Given a level.dat of its own, so it is refused by its shape rather than by accident.
            Files.writeString(dimensions.resolve("level.dat"), "x");

            assertThat(FarmWorldState.mayDelete(serverDirectory, dimensions, "dimensions", "world"))
                    .as("one delete here takes every farm world on the server")
                    .isFalse();
            assertThat(FarmWorldState.mayDelete(serverDirectory,
                    dimensions.resolve("minecraft"), "minecraft", "world"))
                    .as("and the namespace folder holds them all as well")
                    .isFalse();
        }

        @Test
        @DisplayName("never a folder buried deeper than the layout allows")
        void refusesSomethingDeeper() throws IOException {
            // A rule written as "anywhere under the server directory" would allow this, and with it
            // anything a plugin keeps in its own data folder.
            Path deep = serverDirectory.resolve("world").resolve("dimensions")
                    .resolve("minecraft").resolve("farmworld").resolve("region")
                    .resolve("farmworld");
            Files.createDirectories(deep);
            Files.writeString(deep.resolve("level.dat"), "x");

            assertThat(FarmWorldState.mayDelete(serverDirectory, deep, "farmworld", "world")).isFalse();
        }

        @Test
        @DisplayName("never a plugin's data folder that happens to be shaped like the layout")
        void refusesAPluginFolder() throws IOException {
            // plugins/SomePlugin/dimensions/x/farmworld — the same shape, in the wrong place. The rule
            // has to be anchored at the server directory rather than matched anywhere in the path.
            Path pretending = serverDirectory.resolve("plugins").resolve("SomePlugin")
                    .resolve("dimensions").resolve("minecraft").resolve("farmworld");
            Files.createDirectories(pretending);
            Files.writeString(pretending.resolve("level.dat"), "x");

            assertThat(FarmWorldState.mayDelete(serverDirectory, pretending, "farmworld", "world")).isFalse();
        }

        /**
         * A world that exists but has never been written out.
         *
         * <h2>Why this is not the same as "not a world"</h2>
         * Core unloads a farm world with {@code save = false} — writing chunks to disk immediately before
         * deleting them is a freeze that buys nothing. So a farm world created and regenerated without a
         * {@code save-all} in between has region files and <b>no {@code level.dat}</b>, because nothing ever
         * wrote one.
         *
         * <p>Refused for that, the regeneration stops half-way: the world is unloaded, the delete is refused,
         * and {@code FarmWorlds} deliberately does not recreate a folder it may have half-removed. That is the
         * right response to a real refusal and the wrong one here, and it left the nether and the end of a
         * live farm world unloaded and not remade. Seen on the test server the first time this ran.
         *
         * <p>So a folder with region data in it is a world, whether or not the marker file has been written.
         * The position and name checks are unchanged — this widens what counts as a world, not where one may
         * be.
         */
        @Test
        @DisplayName("a world that was never saved is still a world, and may still be deleted")
        void allowsAWorldWithNoLevelDatYet() throws IOException {
            Path folder = serverDirectory.resolve("world").resolve("dimensions")
                    .resolve("minecraft").resolve("farmworld_nether");
            Files.createDirectories(folder.resolve("region"));
            Files.writeString(folder.resolve("region").resolve("r.0.0.mca"), "chunks");

            assertThat(FarmWorldState.mayDelete(serverDirectory, folder, "farmworld_nether", "world"))
                    .as("unloaded with save=false, so nothing ever wrote a level.dat — refusing this "
                            + "leaves a farm world with its nether unloaded and not remade")
                    .isTrue();
        }

        @Test
        @DisplayName("an empty folder is not a world, so there is nothing there to delete")
        void refusesAnEmptyFolder() throws IOException {
            Path folder = serverDirectory.resolve("world").resolve("dimensions")
                    .resolve("minecraft").resolve("farmworld");
            Files.createDirectories(folder);

            assertThat(FarmWorldState.mayDelete(serverDirectory, folder, "farmworld", "world"))
                    .as("nothing in it is nothing to remove, and a folder somebody happened to make is "
                            + "not ours to delete")
                    .isFalse();
        }

        @Test
        @DisplayName("still never a folder that is not a world")
        void stillRefusesANonWorld() throws IOException {
            Path folder = serverDirectory.resolve("world").resolve("dimensions")
                    .resolve("minecraft").resolve("farmworld");
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("something.txt"), "not a world");

            assertThat(FarmWorldState.mayDelete(serverDirectory, folder, "farmworld", "world")).isFalse();
        }

        @Test
        @DisplayName("still never a folder whose name is not the world's")
        void stillRefusesTheWrongName() throws IOException {
            Path folder = aDimensionWorld("world", "minecraft", "farmworld");

            assertThat(FarmWorldState.mayDelete(serverDirectory, folder, "somethingelse", "world")).isFalse();
        }

        @Test
        @DisplayName("still never through a link")
        void stillRefusesASymlink() throws IOException {
            Path real = serverDirectory.resolve("elsewhere");
            Files.createDirectories(real);
            Files.writeString(real.resolve("level.dat"), "x");
            Path parent = serverDirectory.resolve("world").resolve("dimensions").resolve("minecraft");
            Files.createDirectories(parent);
            Path link = parent.resolve("farmworld");
            try {
                Files.createSymbolicLink(link, real);
            } catch (UnsupportedOperationException | IOException notSupported) {
                return; // No links on this filesystem; nothing to assert.
            }

            assertThat(FarmWorldState.mayDelete(serverDirectory, link, "farmworld", "world")).isFalse();
        }
    }

    // ------------------------------------------------------------------ finding the folder at all

    /**
     * Which folder holds a world, when the world is not loaded and cannot be asked.
     *
     * <p>{@code FarmWorlds} asks the loaded {@code World} itself wherever it can — that is the only
     * authoritative answer. This is the fallback for a world that failed to load or was never made, and
     * it is a pure function so that the two layouts are decided somewhere testable rather than inside a
     * method that needs a running server.
     */
    @Nested
    @DisplayName("finding a world's folder without a server")
    class Finding {

        @Test
        @DisplayName("the dimensions layout is found")
        void findsTheDimensionsLayout() throws IOException {
            Path folder = serverDirectory.resolve("world").resolve("dimensions")
                    .resolve("minecraft").resolve("farmworld");
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("level.dat"), "x");

            assertThat(FarmWorldState.findWorldFolder(serverDirectory, "world", "farmworld"))
                    .contains(folder);
        }

        @Test
        @DisplayName("the flat layout is found too")
        void findsTheFlatLayout() throws IOException {
            Path folder = serverDirectory.resolve("farmworld");
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("level.dat"), "x");

            assertThat(FarmWorldState.findWorldFolder(serverDirectory, "world", "farmworld"))
                    .contains(folder);
        }

        @Test
        @DisplayName("a world that is not on disk at all is empty, not a guess")
        void findsNothingWhenThereIsNothing() {
            assertThat(FarmWorldState.findWorldFolder(serverDirectory, "world", "farmworld"))
                    .as("a guessed path is a path something would later try to delete")
                    .isEmpty();
        }

        @Test
        @DisplayName("a folder without a level.dat is not a world and is not returned")
        void ignoresSomethingThatIsNotAWorld() throws IOException {
            Files.createDirectories(serverDirectory.resolve("farmworld"));

            assertThat(FarmWorldState.findWorldFolder(serverDirectory, "world", "farmworld"))
                    .isEmpty();
        }

        @Test
        @DisplayName("nulls answer empty rather than throwing")
        void survivesNulls() {
            assertThat(FarmWorldState.findWorldFolder(null, "world", "farmworld")).isEmpty();
            assertThat(FarmWorldState.findWorldFolder(serverDirectory, null, "farmworld")).isEmpty();
            assertThat(FarmWorldState.findWorldFolder(serverDirectory, "world", null)).isEmpty();
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
