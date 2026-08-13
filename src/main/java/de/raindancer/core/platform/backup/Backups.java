package de.raindancer.core.platform.backup;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A safety copy of every world and every plugin's data, made on the way down — written because there
 * was not one. {@link de.raindancer.core.RainsCorePlugin#onDisable} shuts down whether or not anybody
 * asked for that, on every restart and every redeploy, and until this existed a mistake discovered
 * only after the fact — a listener that wiped a player's inventory, say — had nothing to restore from.
 * A safety net that only exists after being needed once is not a safety net.
 *
 * <h2>Why this is pure {@code java.nio}/{@code java.util.zip}, not Bukkit</h2>
 * Copying files and writing a zip needs nothing from a running server, so none of this needs one
 * either — it can be exercised against a {@code @TempDir} with real files in it, which is worth more
 * here than almost anywhere else in this codebase: a backup routine that has never actually been run
 * against real bytes is exactly the kind of thing that turns out to write an empty zip.
 *
 * <h2>Why deleting the oldest happens after writing the newest, never before</h2>
 * If the write fails partway — disk full, most likely, which is precisely when a backup matters most —
 * pruning first would have already thrown away a good backup to make room for one that never finished.
 * The newest zip lands on disk (or the run fails and nothing is pruned) before anything old is touched.
 */
public final class Backups {

    private static final LogChannel log = Log.of("backups");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path backupsDirectory;
    private final Clock clock;

    public Backups(Path backupsDirectory) {
        this(backupsDirectory, Clock.systemDefaultZone());
    }

    Backups(Path backupsDirectory, Clock clock) {
        this.backupsDirectory = backupsDirectory;
        this.clock = clock;
    }

    /**
     * Zips every folder in {@code worldFolders} under {@code worlds/<name>/…} and every directory
     * directly inside {@code pluginsDirectory} under {@code plugins/<name>/…} — loose files sitting in
     * {@code pluginsDirectory} itself (the jars) are not, since a jar is rebuilt from source and is not
     * the state anything needs restoring. {@code backupsDirectory} is excluded from the plugin-folder
     * scan even if it happens to sit inside {@code pluginsDirectory}, so a backup never contains itself.
     *
     * @return the archive just written, or empty if there was nothing to back up at all — an empty zip
     *         on a server with no worlds and no plugins would be technically correct and practically
     *         useless, so nothing is written rather than writing one
     */
    public Optional<Path> run(Collection<Path> worldFolders, Path pluginsDirectory, int maxBackups) {
        List<Path> plugins = pluginFolders(pluginsDirectory);
        List<Path> worlds = worldFolders == null ? List.of() : worldFolders.stream()
                .filter(folder -> folder != null && Files.isDirectory(folder))
                .toList();
        if (worlds.isEmpty() && plugins.isEmpty()) {
            log.warn("Nothing to back up: no world folders and no plugin folders were given.");
            return Optional.empty();
        }

        Path archive = backupsDirectory.resolve("backup-" + STAMP.format(clock.instant()
                .atZone(clock.getZone())) + ".zip");
        try {
            Files.createDirectories(backupsDirectory);
            try (OutputStream fileOut = Files.newOutputStream(archive);
                 ZipOutputStream zip = new ZipOutputStream(fileOut)) {
                for (Path world : worlds) {
                    addTree(zip, world, "worlds/" + world.getFileName());
                }
                for (Path plugin : plugins) {
                    addTree(zip, plugin, "plugins/" + plugin.getFileName());
                }
            }
        } catch (IOException failure) {
            log.error(failure, "Could not write backup '{}'.", archive);
            // A half-written zip is worse than none: it looks like a backup right up until somebody
            // needs it. Deleted on any failure, including one partway through the tree walk above.
            deleteQuietly(archive);
            return Optional.empty();
        }

        log.info("Backup written: {}", archive.getFileName());
        prune(maxBackups);
        return Optional.of(archive);
    }

    /** Every directory directly under {@code pluginsDirectory} — not files, and not itself. */
    private List<Path> pluginFolders(Path pluginsDirectory) {
        if (pluginsDirectory == null || !Files.isDirectory(pluginsDirectory)) {
            return List.of();
        }
        Path backupsAbsolute = backupsDirectory.normalize().toAbsolutePath();
        try (var entries = Files.list(pluginsDirectory)) {
            return entries.filter(Files::isDirectory)
                    // Only an exact match — a plugin folder that merely *contains* the backups
                    // directory (the ordinary case: RainsCore/backups/ sits inside RainsCore's own
                    // folder) is still backed up in full; the nested backups directory is excluded
                    // file-by-file in addTree below instead, or the whole rest of that plugin's data
                    // would be silently dropped along with it.
                    .filter(entry -> !entry.normalize().toAbsolutePath().equals(backupsAbsolute))
                    .sorted()
                    .toList();
        } catch (IOException failure) {
            log.error(failure, "Could not list plugin folders under '{}'.", pluginsDirectory);
            return List.of();
        }
    }

    /** Writes {@code source}'s whole tree into the zip, every entry named {@code prefix/<relative path>}. */
    private void addTree(ZipOutputStream zip, Path source, String prefix) throws IOException {
        Path backupsAbsolute = backupsDirectory.normalize().toAbsolutePath();
        try (var files = Files.walk(source)) {
            for (Path file : files.sorted().toList()) {
                if (Files.isDirectory(file)) {
                    continue;   // directories are implied by their files' entry names
                }
                if (file.normalize().toAbsolutePath().startsWith(backupsAbsolute)) {
                    continue;   // never backs up its own backups directory, wherever it is nested
                }
                String relative = source.relativize(file).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(prefix + "/" + relative);
                BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                entry.setTime(attrs.lastModifiedTime().toMillis());
                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    /** Deletes every backup but the newest {@code maxBackups} — never touches anything else in the directory. */
    private void prune(int maxBackups) {
        int keep = Math.max(1, maxBackups);
        List<Path> backups;
        try (var entries = Files.list(backupsDirectory)) {
            backups = entries.filter(path -> path.getFileName().toString().startsWith("backup-")
                            && path.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing(Path::getFileName).reversed())
                    .toList();
        } catch (IOException failure) {
            log.error(failure, "Could not list existing backups to prune '{}'.", backupsDirectory);
            return;
        }
        if (backups.size() <= keep) {
            return;
        }
        for (Path stale : backups.subList(keep, backups.size())) {
            deleteQuietly(stale);
            log.info("Pruned old backup: {}", stale.getFileName());
        }
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            log.warn("Could not delete '{}'.", file);
        }
    }
}
