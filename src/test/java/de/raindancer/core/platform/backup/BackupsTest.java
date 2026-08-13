package de.raindancer.core.platform.backup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class BackupsTest {

    @TempDir
    Path root;

    private Path backupsDir;
    private Path pluginsDir;
    private Path worldDir;
    private Clock clock;

    @BeforeEach
    void setUp() throws IOException {
        backupsDir = root.resolve("backups");
        pluginsDir = root.resolve("plugins");
        worldDir = root.resolve("world");
        Files.createDirectories(pluginsDir);
        Files.createDirectories(worldDir);
        clock = Clock.fixed(Instant.parse("2026-08-13T07:15:30Z"), ZoneOffset.UTC);
    }

    private Path writeFile(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    /** Every entry name in the zip, for asserting what actually landed inside it. */
    private Set<String> entriesOf(Path zip) throws IOException {
        Set<String> names = new java.util.HashSet<>();
        try (InputStream in = Files.newInputStream(zip); ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private String contentOf(Path zip, String entryName) throws IOException {
        try (InputStream in = Files.newInputStream(zip); ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(entryName)) {
                    return new String(zis.readAllBytes());
                }
            }
        }
        throw new AssertionError(entryName + " not found in " + zip);
    }

    @Nested
    @DisplayName("what goes into the archive")
    class Contents {

        @Test
        @DisplayName("includes every world folder given, under worlds/<name>/")
        void includesWorldFolders() throws IOException {
            writeFile(worldDir.resolve("level.dat"), "the world");
            writeFile(worldDir.resolve("region/r.0.0.mca"), "a region file");
            Backups backups = new Backups(backupsDir, clock);

            Optional<Path> archive = backups.run(List.of(worldDir), pluginsDir, 10);

            assertThat(archive).isPresent();
            Set<String> entries = entriesOf(archive.orElseThrow());
            assertThat(entries).contains("worlds/world/level.dat", "worlds/world/region/r.0.0.mca");
            assertThat(contentOf(archive.orElseThrow(), "worlds/world/level.dat")).isEqualTo("the world");
        }

        @Test
        @DisplayName("includes every plugin's own data folder, under plugins/<name>/")
        void includesPluginFolders() throws IOException {
            writeFile(pluginsDir.resolve("RainsCore/config.yml"), "core config");
            writeFile(pluginsDir.resolve("SomeOtherPlugin/data.yml"), "other plugin data");
            Backups backups = new Backups(backupsDir, clock);

            Optional<Path> archive = backups.run(List.of(), pluginsDir, 10);

            Set<String> entries = entriesOf(archive.orElseThrow());
            assertThat(entries).contains(
                    "plugins/RainsCore/config.yml", "plugins/SomeOtherPlugin/data.yml");
        }

        @Test
        @DisplayName("does not include loose files sitting directly in the plugins folder — the jars")
        void excludesJarsAtTopLevel() throws IOException {
            writeFile(pluginsDir.resolve("RainsCore/config.yml"), "core config");
            writeFile(pluginsDir.resolve("RainsCore-1.17.3.jar"), "not really a jar");
            Backups backups = new Backups(backupsDir, clock);

            Optional<Path> archive = backups.run(List.of(), pluginsDir, 10);

            Set<String> entries = entriesOf(archive.orElseThrow());
            assertThat(entries).noneMatch(name -> name.endsWith(".jar"));
        }

        @Test
        @DisplayName("never backs up its own backups directory, even if it sits inside the plugins folder")
        void excludesItsOwnBackupsDirectory() throws IOException {
            Path nestedBackups = pluginsDir.resolve("RainsCore/backups");
            writeFile(nestedBackups.resolve("old-backup.zip"), "an old backup");
            writeFile(pluginsDir.resolve("RainsCore/config.yml"), "core config");
            Backups backups = new Backups(nestedBackups, clock);

            Optional<Path> archive = backups.run(List.of(), pluginsDir, 10);

            Set<String> entries = entriesOf(archive.orElseThrow());
            assertThat(entries).noneMatch(name -> name.contains("old-backup.zip"));
            assertThat(entries).contains("plugins/RainsCore/config.yml");
        }

        @Test
        @DisplayName("writes nothing at all when there is no world and no plugin folder to back up")
        void writesNothingWhenThereIsNothingToBackUp() {
            Backups backups = new Backups(backupsDir, clock);

            Optional<Path> archive = backups.run(List.of(), pluginsDir, 10);

            assertThat(archive).isEmpty();
            assertThat(Files.exists(backupsDir)).isFalse();
        }
    }

    @Nested
    @DisplayName("pruning")
    class Pruning {

        @Test
        @DisplayName("keeps exactly the newest maxBackups and deletes the rest")
        void keepsOnlyTheNewest() throws IOException {
            Files.createDirectories(backupsDir);
            // Pre-existing backups, oldest to newest by name — the timestamp format sorts correctly as text.
            for (String stamp : List.of("20260810-000000", "20260811-000000", "20260812-000000")) {
                writeFile(backupsDir.resolve("backup-" + stamp + ".zip"), "old");
            }
            writeFile(pluginsDir.resolve("RainsCore/config.yml"), "core config");
            Backups backups = new Backups(backupsDir, clock);   // clock: 2026-08-13

            backups.run(List.of(), pluginsDir, 2);

            List<String> remaining = listBackupNames();
            assertThat(remaining).containsExactlyInAnyOrder(
                    "backup-20260812-000000.zip", "backup-20260813-071530.zip");
        }

        @Test
        @DisplayName("a fresh backup is written before anything old is pruned, even when maxBackups is 1")
        void writesBeforePruning() throws IOException {
            Files.createDirectories(backupsDir);
            writeFile(backupsDir.resolve("backup-20260812-000000.zip"), "old");
            writeFile(pluginsDir.resolve("RainsCore/config.yml"), "core config");
            Backups backups = new Backups(backupsDir, clock);

            Optional<Path> archive = backups.run(List.of(), pluginsDir, 1);

            assertThat(archive).isPresent();
            assertThat(Files.exists(archive.orElseThrow())).isTrue();
            assertThat(listBackupNames()).containsExactly("backup-20260813-071530.zip");
        }

        @Test
        @DisplayName("never deletes anything that is not one of its own backup files")
        void onlyTouchesItsOwnFiles() throws IOException {
            Files.createDirectories(backupsDir);
            writeFile(backupsDir.resolve("readme.txt"), "do not touch");
            writeFile(backupsDir.resolve("backup-20260801-000000.zip"), "old");
            writeFile(pluginsDir.resolve("RainsCore/config.yml"), "core config");
            Backups backups = new Backups(backupsDir, clock);

            backups.run(List.of(), pluginsDir, 1);

            assertThat(Files.exists(backupsDir.resolve("readme.txt"))).isTrue();
        }

        private List<String> listBackupNames() throws IOException {
            try (var entries = Files.list(backupsDir)) {
                return entries.map(p -> p.getFileName().toString())
                        .filter(name -> name.startsWith("backup-"))
                        .collect(Collectors.toCollection(ArrayList::new));
            }
        }
    }
}
