package de.raindancer.core.store;

import de.raindancer.core.log.Log;
import de.raindancer.core.log.LogChannel;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A YAML file on disk, read and written without losing it.
 *
 * <h2>Why this exists</h2>
 * Because the same twenty lines were written seven times in this library alone — {@code PoiStore},
 * {@code CustomItems}, {@code Punishments}, {@code Identities}, {@code FarmWorldState},
 * {@code Achievements} and {@code LootTables} each had their own copy of the write-to-a-temporary-
 * then-move dance — and again in every plugin that keeps anything. A library whose whole point is
 * removing duplication was the worst offender in its own family. A review said so and was right.
 *
 * <p>The repetition is the smaller half. Each copy is a chance to get the write-and-move wrong, and
 * getting it wrong means a server killed at the wrong moment has half a file where everybody's
 * homes used to be. Written once, tested once, and every store gets the same guarantees.
 *
 * <h2>What it guarantees</h2>
 * <ul>
 *   <li>The real file is only ever replaced by a finished one. A kill mid-write leaves the old file
 *       or the new one, never half of each.</li>
 *   <li>A write that throws halfway leaves the previous contents untouched.</li>
 *   <li>No temporary is left behind, whether the write worked or not.</li>
 *   <li>A file that will not parse reads as empty and <em>says so</em>, rather than looking like a
 *       server that has lost everybody's data.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Writes are serialised on this object. Two callers writing at once produce one file and one
 * winner, rather than two half-writes interleaved — which is what the seven separate copies never
 * promised.
 */
public final class YamlStore {

    private static final LogChannel log = Log.of("store");

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");

    private final Path file;
    private final List<String> problems = new ArrayList<>();

    public YamlStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public boolean exists() {
        return file != null && Files.isRegularFile(file);
    }

    /** What was wrong with the file last time it was read. Empty when it was clean. */
    public synchronized List<String> problems() {
        return List.copyOf(problems);
    }

    // ---------------------------------------------------------------------------- reading

    /**
     * The file's contents, or an empty configuration when there is not one.
     *
     * <p>Never throws and never returns null. A file that cannot be parsed comes back empty with
     * the reason in {@link #problems()} — because a plugin that refuses to start over a bad config
     * helps nobody, and one that silently treats it as empty is worse still.
     */
    public YamlConfiguration read() {
        synchronized (this) {
            problems.clear();
        }
        if (file == null || !Files.isRegularFile(file)) {
            return new YamlConfiguration();
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file));
            return yaml;
        } catch (IOException | InvalidConfigurationException | RuntimeException unreadable) {
            note("could not be read (" + unreadable.getMessage() + ")");
            return new YamlConfiguration();
        }
    }

    // ---------------------------------------------------------------------------- writing

    /**
     * Writes the file.
     *
     * <p>The consumer is given a fresh configuration to fill in. It runs <em>before</em> anything
     * touches the real file, so a caller that throws halfway costs nothing.
     *
     * @return whether it was written; false leaves whatever was there before
     */
    public boolean write(Consumer<YamlConfiguration> contents) {
        if (file == null || contents == null) {
            return false;
        }
        return put(new YamlConfiguration(), contents);
    }

    /**
     * Reads, lets the caller change what is there, and writes it back.
     *
     * <p>For a file that is not wholly ours: a settings file keeps keys a newer version of the plugin
     * added and comments somebody wrote, so writing a fresh one from what we know would quietly throw
     * those away. Reading and writing are one step here, so two callers cannot both read, both
     * change, and have the second undo the first.
     *
     * @return whether it was written; false leaves whatever was there before
     */
    public boolean update(Consumer<YamlConfiguration> change) {
        if (file == null || change == null) {
            return false;
        }
        synchronized (this) {
            return put(read(), change);
        }
    }

    private boolean put(YamlConfiguration yaml, Consumer<YamlConfiguration> contents) {
        String text;
        try {
            contents.accept(yaml);
            text = yaml.saveToString();
        } catch (RuntimeException failure) {
            // Nothing has been touched yet, so there is nothing to undo — which is the reason the
            // caller's work happens before the file is opened rather than into it.
            log.error(failure, "Could not build the contents of {}; it is unchanged.", file);
            return false;
        }

        synchronized (this) {
            Path temporary = file.resolveSibling(file.getFileName() + ".writing");
            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(temporary, text);
                // The move is what makes this safe: on every filesystem these run on it is atomic,
                // so a reader sees the old file or the new one and never a partial write.
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (IOException failure) {
                log.error(failure, "Could not write {}; it is unchanged.", file);
                return false;
            } finally {
                // A temporary left behind after a failure would be written over next time anyway,
                // but a directory littered with .writing files is a directory somebody worries about.
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Nothing useful to do, and nothing depends on it.
                }
            }
        }
    }

    /**
     * Moves a file that could not be read out of the way, keeping it.
     *
     * <p>For a store that would otherwise write over it on the next save: the data in a corrupt file
     * is often recoverable by hand and is certainly not ours to delete. The plugin then starts empty
     * rather than refusing to start, and the old file is still there to look at.
     *
     * @return where it was put, or empty when there was nothing to move
     */
    public Optional<Path> quarantine() {
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Path kept = file.resolveSibling(
                file.getFileName() + ".broken-" + STAMP.format(LocalDateTime.now()));
        try {
            Files.move(file, kept, StandardCopyOption.REPLACE_EXISTING);
            log.warn("{} could not be read and has been kept as {}. The plugin is starting with "
                    + "nothing rather than writing over it.", file.getFileName(),
                    kept.getFileName());
            return Optional.of(kept);
        } catch (IOException failure) {
            log.error(failure, "Could not set aside the unreadable file {}", file);
            return Optional.empty();
        }
    }

    private void note(String problem) {
        synchronized (this) {
            problems.add(problem);
        }
        log.warn("{}: {}", file == null ? "?" : file.getFileName(), problem);
    }
}
