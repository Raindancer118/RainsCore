package de.raindancer.core.data.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** What a live server can hand a database: a newer one, a broken one, one somebody else is writing. */
class DatabaseEdgesTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName("a database a newer Core has upgraded opens and works, without re-running anything")
    void newerThanThisVersion() {
        Path file = folder.resolve("core.db");
        Database newer = Database.open(file, Schema.of(
                "CREATE TABLE a (x INTEGER)", "CREATE TABLE b (y INTEGER)"), () -> false);
        newer.close();

        // A downgrade: this Core knows only the first step.
        Database older = Database.open(file, Schema.of("CREATE TABLE a (x INTEGER)"), () -> false);
        try {
            assertThat(older.isUsable()).isTrue();
            assertThat(older.version()).as("never moved backwards").isEqualTo(2);
            assertThat(older.write(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("INSERT INTO a (x) VALUES (1)");
                }
            })).isTrue();
        } finally {
            older.close();
        }
    }

    @Test
    @DisplayName("a file that is not a database is reported unusable and left exactly as it was")
    void corruptFileIsKept() throws Exception {
        Path file = folder.resolve("core.db");
        byte[] rubbish = "this was a database until the disk filled up".repeat(200).getBytes();
        Files.write(file, rubbish);

        Database broken = Database.open(file, CoreSchema.CORE, () -> false);

        assertThat(broken.isUsable()).isFalse();
        assertThat(broken.write(connection -> { })).isFalse();
        broken.close();
        assertThat(Files.readAllBytes(file)).as("somebody may yet recover it").isEqualTo(rubbish);
    }

    @Test
    @DisplayName("a write while another process holds the lock waits its turn, and fails cleanly if it never comes")
    void lockedByAnotherProcess() throws Exception {
        Path file = folder.resolve("core.db");
        Database database = Database.open(file, Schema.of("CREATE TABLE a (x INTEGER)"), () -> false);
        try (var other = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement lock = other.createStatement()) {
            lock.execute("BEGIN EXCLUSIVE");
            long started = System.nanoTime();

            boolean written = database.write(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("INSERT INTO a (x) VALUES (1)");
                }
            });

            assertThat(written).isFalse();
            assertThat((System.nanoTime() - started) / 1_000_000).as("it waited, rather than failing at once")
                    .isGreaterThanOrEqualTo(4_000);
            lock.execute("ROLLBACK");
        }
        assertThat(database.isUsable()).as("one refused write is not a broken database").isTrue();
        database.close();
    }
}
