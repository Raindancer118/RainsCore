package de.raindancer.core.world.farm;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.YamlStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Which farm worlds exist, when each was last made — and what may be deleted.
 *
 * <h2>Why the deletion rules live here, as a pure function</h2>
 * Regenerating a farm world deletes a directory. Everything else in this library can be wrong and
 * cost somebody an evening; this can be wrong and cost them their server. {@link #mayDelete} is
 * therefore separate from the code that deletes, takes no state, and is tested against every mistake
 * somebody could plausibly make with a command: a path outside the server, a path that climbs out
 * with {@code ..}, a folder that is not the world it claims to be, a folder that is not a world at
 * all, and the server directory itself.
 *
 * <p>It is deliberately suspicious rather than merely correct. A rule that only allows what is
 * obviously safe will occasionally refuse something harmless; a rule that only forbids what is
 * obviously dangerous will eventually allow something that is not.
 */
public final class FarmWorldState {

    private static final LogChannel log = Log.of("worlds");

    /** What a directory must contain before it is believed to be a world. */
    private static final String WORLD_MARKER = "level.dat";

    private final Path file;
    private final YamlStore store;
    private final Map<String, WorldSet> sets = new ConcurrentHashMap<>();
    private final Map<String, Instant> madeAt = new ConcurrentHashMap<>();
    /** When a set was last <em>tried</em>, whether or not it worked. See {@link #due}. */
    private final Map<String, Instant> triedAt = new ConcurrentHashMap<>();
    private final Database database;
    /** Set when a farm world's definition changed and the file needs rewriting. */
    private final AtomicBoolean dirty = new AtomicBoolean();
    /** Which sets' recorded times need writing — the database half. */
    private final Set<String> changedTimes = ConcurrentHashMap.newKeySet();

    /**
     * @param file     where the farm worlds are <em>defined</em>: which dimensions, what seed, how
     *                 often to regenerate. Written by whoever runs the server
     * @param database where <em>when each one was last made</em> is recorded. Written by the server
     *                 itself, and the half that decides whether somebody walks into a stale world
     */
    public FarmWorldState(Path file, Database database) {
        this.file = file;
        this.store = new YamlStore(file);
        this.database = database;
    }

    // ---------------------------------------------------------------------------- the sets

    public void define(WorldSet set) {
        if (set != null) {
            sets.put(set.name(), set);
            dirty.set(true);
        }
    }

    public boolean undefine(String name) {
        if (name == null) {
            return false;
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        boolean removed = sets.remove(wanted) != null;
        madeAt.remove(wanted);
        triedAt.remove(wanted);
        if (removed) {
            dirty.set(true);
            changedTimes.add(wanted);
        }
        return removed;
    }

    public Optional<WorldSet> byName(String name) {
        return name == null ? Optional.empty()
                : Optional.ofNullable(sets.get(name.trim().toLowerCase(Locale.ROOT)));
    }

    public List<WorldSet> all() {
        return List.copyOf(sets.values());
    }

    /** Which set a world belongs to, if any — what the portal listener asks. */
    public Optional<WorldSet> setOwning(String world) {
        return sets.values().stream().filter(set -> set.contains(world)).findFirst();
    }

    // ---------------------------------------------------------------------------- the schedule

    /** When a set was last made, or empty when it never has been. */
    public Optional<Instant> lastRegenerated(String name) {
        return name == null ? Optional.empty()
                : Optional.ofNullable(madeAt.get(name.trim().toLowerCase(Locale.ROOT)));
    }

    public void recordRegenerated(String name, Instant when) {
        if (name != null && when != null) {
            String wanted = name.trim().toLowerCase(Locale.ROOT);
            madeAt.put(wanted, when);
            changedTimes.add(wanted);
        }
    }

    /**
     * How long to wait before trying again after a regeneration that did not work.
     *
     * <p>Long enough that a set which cannot be made — a locked file, a folder that is a link —
     * does not retry every minute and fill the log; short enough that it is not a week before
     * anybody looks. The difference matters: recording a failure as a success, which is what this
     * used to do, left a depleted farm world depleted for the whole period with nothing said.
     */
    public static final Duration RETRY_AFTER = Duration.ofHours(1);

    /** Records that a set was tried, whether or not it worked. */
    public void recordAttempt(String name, Instant when) {
        if (name != null && when != null) {
            String wanted = name.trim().toLowerCase(Locale.ROOT);
            triedAt.put(wanted, when);
            changedTimes.add(wanted);
        }
    }

    /**
     * Every set whose time is up.
     *
     * <p>A set that was tried and failed is held off for {@link #RETRY_AFTER} rather than for its
     * whole period: it still needs making, and the alternative is a week of nobody noticing.
     */
    public List<WorldSet> due(Instant now) {
        return sets.values().stream()
                .filter(set -> set.isDue(madeAt.get(set.name()), now))
                .filter(set -> {
                    Instant tried = triedAt.get(set.name());
                    return tried == null || !now.isBefore(tried.plus(RETRY_AFTER));
                })
                .toList();
    }

    // ---------------------------------------------------------------------------- deletion

    /**
     * Whether a directory may be deleted as part of regenerating a world.
     *
     * <p>Every condition here is a mistake somebody could make with a command, and every one of
     * them would be unrecoverable. In order: something to check, inside the server directory, not
     * the server directory itself, actually a directory, actually named after the world, and
     * actually a world.
     *
     * @param serverDirectory where the server lives; nothing outside it is ever touched
     * @param candidate       the folder somebody wants removed
     * @param worldName       the world it is supposed to be
     */
    public static boolean mayDelete(Path serverDirectory, Path candidate, String worldName) {
        if (serverDirectory == null || candidate == null || worldName == null
                || worldName.isBlank()) {
            return false;
        }
        try {
            // Real paths, so a link or a .. cannot point somewhere else than it appears to.
            Path server = serverDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS).normalize();
            if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                // Includes the case of a world folder that is a symlink — somebody pointing their
                // farm world at a RAM disk, which is a reasonable thing to do. It is still refused:
                // deleting through a link is exactly how a recursive delete reaches somewhere
                // nobody meant it to. But it is said out loud rather than silently skipped, because
                // a farm world that never regenerates and never explains why is worse than one that
                // refuses and says so.
                if (Files.isSymbolicLink(candidate)) {
                    log.warn("'{}' is a link rather than a folder, so it will not be deleted and "
                            + "the farm world cannot be regenerated. Point the world at a real "
                            + "directory, or mount the fast storage there instead of linking to it.",
                            candidate);
                }
                return false;
            }
            Path folder = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS).normalize();

            if (folder.equals(server) || !folder.startsWith(server)) {
                return false;
            }
            // Directly inside the server directory, and named exactly after the world. A world
            // folder nested somewhere else is not one we made.
            if (!server.equals(folder.getParent())) {
                return false;
            }
            if (!folder.getFileName().toString().equals(worldName)) {
                return false;
            }
            // And it has to actually be a world. A folder somebody happened to name "farmworld" is
            // not the farm world, and deleting it would be deleting whatever it really was.
            return Files.isRegularFile(folder.resolve(WORLD_MARKER));
        } catch (IOException | RuntimeException unreadable) {
            // If it cannot even be resolved, it is certainly not something to delete.
            log.warn("Refusing to delete '{}': {}", candidate, String.valueOf(unreadable));
            return false;
        }
    }

    // ---------------------------------------------------------------------------- the file

    /** Whether either half is waiting to be written. */
    public boolean isDirty() {
        return dirty.get() || !changedTimes.isEmpty();
    }

    public void load() {
        sets.clear();
        madeAt.clear();
        triedAt.clear();
        if (!store.exists()) {
            dirty.set(false);
            return;
        }
        YamlConfiguration yaml = store.read();
        if (!store.problems().isEmpty()) {
            log.error("Could not read {} ({}); no farm worlds are known this session.",
                    file, String.join("; ", store.problems()));
            return;
        }
        ConfigurationSection section = yaml.getConfigurationSection("farm-worlds");
        if (section == null) {
            dirty.set(false);
            return;
        }
        for (String name : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(name);
            if (entry == null) {
                continue;
            }
            try {
                WorldSet.Builder built = WorldSet.builder(name)
                        .withNether(entry.getBoolean("nether", true))
                        .withEnd(entry.getBoolean("end", true));
                if (entry.contains("regenerate-every-hours")) {
                    built.every(Duration.ofHours(entry.getLong("regenerate-every-hours")));
                }
                if (entry.contains("seed")) {
                    built.seed(entry.getLong("seed"));
                }
                if (entry.contains("border")) {
                    built.border(entry.getInt("border"));
                }
                WorldSet set = built.build();
                sets.put(set.name(), set);
            } catch (RuntimeException broken) {
                // A bad entry is one farm world lost, not a file nobody can load — and it must not
                // take out the others, one of which somebody may be standing in.
                log.warn("{}: farm world '{}' was skipped ({})",
                        file.getFileName(), name, broken.getMessage());
            }
        }
        dirty.set(false);
        loadTimes();
    }

    /**
     * Reads when each set was last made and last attempted.
     *
     * <p>Out of the database rather than the file, because these are the server's own notes rather
     * than anybody's configuration — and getting them back is what stops a farm world being
     * regenerated on the first portal after every restart.
     */
    private void loadTimes() {
        changedTimes.clear();
        if (!database.isUsable()) {
            log.error("The farm world table is not available; every set will look as though it has "
                    + "never been made.");
            return;
        }
        database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT name, made_at, tried_at FROM farm_world");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString("name");
                    long made = rows.getLong("made_at");
                    if (!rows.wasNull()) {
                        madeAt.put(name, Instant.ofEpochMilli(made));
                    }
                    long tried = rows.getLong("tried_at");
                    if (!rows.wasNull()) {
                        triedAt.put(name, Instant.ofEpochMilli(tried));
                    }
                }
            }
            return true;
        });
    }

    /** Writes, if anything changed. Via a temporary file, so a kill mid-write cannot truncate it. */
    /**
     * Writes both halves: the definitions to their file, and the recorded times to the database.
     *
     * <p>Must be called off the server's threads.
     */
    public void flush() {
        flushDefinitions();
        flushTimes();
    }

    private void flushDefinitions() {
        if (!dirty.compareAndSet(true, false)) {
            return;
        }
        List<WorldSet> snapshot = List.copyOf(sets.values());
        boolean written = store.write(yaml -> {
            for (WorldSet set : snapshot) {
                String path = "farm-worlds." + set.name() + ".";
                yaml.set(path + "nether", set.hasNether());
                yaml.set(path + "end", set.hasEnd());
                set.regenerateEvery().ifPresent(every ->
                        yaml.set(path + "regenerate-every-hours", every.toHours()));
                if (set.fixedSeed() != null) {
                    yaml.set(path + "seed", set.fixedSeed());
                }
                set.border().ifPresent(border -> yaml.set(path + "border", border));
            }
        });
        if (!written) {
            dirty.set(true);
        }
    }

    private void flushTimes() {
        if (changedTimes.isEmpty() || !database.isUsable()) {
            return;
        }
        Set<String> writing = Set.copyOf(changedTimes);
        boolean written = database.write(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO farm_world (name, made_at, tried_at) VALUES (?, ?, ?)
                    ON CONFLICT(name) DO UPDATE SET
                        made_at = excluded.made_at, tried_at = excluded.tried_at""");
                 PreparedStatement remove =
                         connection.prepareStatement("DELETE FROM farm_world WHERE name = ?")) {
                for (String name : writing) {
                    if (!sets.containsKey(name)) {
                        // Undefined since. Its notes go with it, or a set later defined under the
                        // same name would inherit a regeneration time it never had.
                        remove.setString(1, name);
                        remove.executeUpdate();
                        continue;
                    }
                    upsert.setString(1, name);
                    setMillisOrNull(upsert, 2, madeAt.get(name));
                    setMillisOrNull(upsert, 3, triedAt.get(name));
                    upsert.executeUpdate();
                }
            }
        });
        if (written) {
            changedTimes.removeAll(writing);
        }
    }

    private static void setMillisOrNull(PreparedStatement statement, int at, Instant when)
            throws java.sql.SQLException {
        if (when == null) {
            statement.setNull(at, java.sql.Types.INTEGER);
        } else {
            statement.setLong(at, when.toEpochMilli());
        }
    }
}
