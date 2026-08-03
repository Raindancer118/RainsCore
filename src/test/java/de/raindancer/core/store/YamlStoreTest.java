package de.raindancer.core.store;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Reading and writing a YAML file without losing it.
 *
 * <h2>Why this exists</h2>
 * Because the same twenty lines were written seven times in this library alone — {@code PoiStore},
 * {@code CustomItems}, {@code Punishments}, {@code Identities}, {@code FarmWorldState},
 * {@code Achievements}, {@code LootTables} — and again in every plugin that keeps anything. A
 * library whose whole point is removing duplication was the worst offender in its own family, which
 * a review pointed out and which was entirely fair.
 *
 * <p>Worse than the repetition: each copy is a chance to get the write-and-move wrong, and getting
 * it wrong means a server killed at the wrong moment has half a file where everybody's homes used
 * to be. Written once, tested once, and every store gets the same guarantees.
 */
class YamlStoreTest {

    @TempDir
    Path directory;

    private YamlStore store() {
        return new YamlStore(directory.resolve("things.yml"));
    }

    // ------------------------------------------------------------------ the round trip

    @Nested
    @DisplayName("reading and writing")
    class RoundTrip {

        @Test
        @DisplayName("what was written is what is read back")
        void roundTrips() {
            YamlStore store = store();
            store.write(yaml -> {
                yaml.set("greeting", "hello");
                yaml.set("count", 3);
            });

            YamlConfiguration read = store().read();
            assertThat(read.getString("greeting")).isEqualTo("hello");
            assertThat(read.getInt("count")).isEqualTo(3);
        }

        @Test
        @DisplayName("a file that is not there reads as empty rather than failing")
        void missingFileIsEmpty() {
            YamlStore fresh = new YamlStore(directory.resolve("never-written.yml"));
            assertThat(fresh.read().getKeys(false)).isEmpty();
            assertThat(fresh.exists()).isFalse();
            assertThat(fresh.problems()).isEmpty();
        }

        @Test
        @DisplayName("the directory is made rather than the write failing")
        void createsItsOwnDirectory() {
            YamlStore nested = new YamlStore(directory.resolve("a").resolve("b").resolve("c.yml"));
            assertThat(nested.write(yaml -> yaml.set("x", 1))).isTrue();
            assertThat(nested.read().getInt("x")).isEqualTo(1);
        }

        @Test
        @DisplayName("a plain write replaces everything that was there")
        void writeIsAReplacement() {
            YamlStore store = store();
            store.write(yaml -> yaml.set("old", "gone"));
            store.write(yaml -> yaml.set("new", "here"));

            assertThat(store().read().getKeys(false)).containsExactly("new");
        }

        @Test
        @DisplayName("an update keeps what it did not touch")
        void updateKeepsTheRest() {
            YamlStore store = store();
            store.write(yaml -> {
                yaml.set("mine", 1);
                // A key written by a newer version of the plugin, which this one knows nothing about.
                yaml.set("theirs", "do not lose me");
            });

            assertThat(store.update(yaml -> yaml.set("mine", 2))).isTrue();

            YamlConfiguration read = store().read();
            assertThat(read.getInt("mine")).isEqualTo(2);
            assertThat(read.getString("theirs"))
                    .as("a downgrade must not throw away a setting the newer version added")
                    .isEqualTo("do not lose me");
        }

        @Test
        @DisplayName("an update to a file that is not there writes a new one")
        void updateWithoutAFile() {
            YamlStore fresh = new YamlStore(directory.resolve("first-run.yml"));
            assertThat(fresh.update(yaml -> yaml.set("x", 1))).isTrue();
            assertThat(fresh.read().getInt("x")).isEqualTo(1);
        }

        @Test
        @DisplayName("a file that is not YAML reads as empty and says so")
        void survivesRubbish() throws IOException {
            Files.writeString(directory.resolve("things.yml"),
                    "this: is: not: valid: yaml:\n\t\tand neither is this\n");
            YamlStore store = store();

            assertThat(store.read().getKeys(false)).isEmpty();
            assertThat(store.problems())
                    .as("a file nobody can read must be reported, not silently treated as empty")
                    .isNotEmpty();
        }
    }

    // ------------------------------------------------------------------ not losing it

    /**
     * The reason this is one class rather than seven. Every one of these is a way to end up with
     * half a file where everybody's homes used to be.
     */
    @Nested
    @DisplayName("not losing the file")
    class Durability {

        @Test
        @DisplayName("the real file is never the one being written to")
        void writesThroughATemporary() {
            YamlStore store = store();
            store.write(yaml -> {
                // While this runs the real file must not exist yet — everything so far has gone to
                // the temporary. A server killed here leaves no file rather than an empty one.
                assertThat(directory.resolve("things.yml")).doesNotExist();
                yaml.set("x", 1);
            });
            assertThat(directory.resolve("things.yml")).exists();
        }

        @Test
        @DisplayName("a failed write leaves the old file exactly as it was")
        void keepsTheOldFileOnFailure() throws IOException {
            YamlStore store = store();
            store.write(yaml -> yaml.set("good", "data"));

            boolean written = store.write(yaml -> {
                throw new IllegalStateException("something went wrong halfway");
            });

            assertThat(written).isFalse();
            assertThat(store.read().getString("good"))
                    .as("a write that failed must not cost the data that was already there")
                    .isEqualTo("data");
        }

        @Test
        @DisplayName("no temporary file is left behind, whether it worked or not")
        void tidiesUpAfterItself() throws IOException {
            YamlStore store = store();
            store.write(yaml -> yaml.set("x", 1));
            store.write(yaml -> {
                throw new IllegalStateException("no");
            });

            try (var files = Files.list(directory)) {
                assertThat(files.map(path -> path.getFileName().toString()))
                        .containsExactly("things.yml");
            }
        }

        @Test
        @DisplayName("a corrupt file can be set aside rather than overwritten")
        void keepsABrokenFileForSomebodyToLookAt() throws IOException {
            Files.writeString(directory.resolve("things.yml"), "\t not yaml \t:\n\tbroken");
            YamlStore store = store();
            store.read();

            assertThat(store.quarantine()).isPresent();
            assertThat(directory.resolve("things.yml")).doesNotExist();
            try (var files = Files.list(directory)) {
                assertThat(files.map(path -> path.getFileName().toString()))
                        .as("the data somebody may want back is kept, not deleted")
                        .anyMatch(name -> name.startsWith("things.yml.broken"));
            }
        }
    }

    // ------------------------------------------------------------------ threads

    @Test
    @DisplayName("writing from many threads at once never produces a broken file")
    void isSafeFromEveryThread() throws Exception {
        YamlStore store = store();
        int threads = 8;
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int thread = 0; thread < threads; thread++) {
                int id = thread;
                pool.submit(() -> {
                    go.await();
                    for (int round = 0; round < 20; round++) {
                        int at = round;
                        store.write(yaml -> {
                            yaml.set("writer", id);
                            yaml.set("round", at);
                        });
                    }
                    return null;
                });
            }
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        // Whichever writer got there last, the file has to be readable and whole.
        YamlConfiguration read = store().read();
        assertThat(store().problems()).isEmpty();
        assertThat(read.getKeys(false)).containsExactlyInAnyOrder("writer", "round");
    }

    // ------------------------------------------------------------------ misuse

    @Test
    @DisplayName("nulls do not throw")
    void survivesNulls() {
        assertThatCode(() -> {
            store().write(null);
            new YamlStore(null).read();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a store can say where it keeps things, for a diagnostic")
    void namesItsFile() {
        assertThat(store().file().getFileName().toString()).isEqualTo("things.yml");
    }
}
