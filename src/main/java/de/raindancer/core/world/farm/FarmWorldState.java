package de.raindancer.core.world.farm;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.util.Marks;
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

    /**
     * The other thing a world folder has, for one that has not been written out yet.
     *
     * <p>Core unloads a farm world with {@code save = false}, so a world created and regenerated without a
     * save in between has chunks and no {@code level.dat} — nothing ever wrote one. Judged by the marker
     * alone, such a world is refused, the regeneration stops half-way, and its nether and end are left
     * unloaded and not remade.
     */
    private static final String CHUNKS = "region";

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
     * @param levelName       the main world's name, from {@code level-name}. Required, and refused
     *                        outright — it is the folder every other world now lives inside, so one
     *                        delete there takes the server with it. It cannot be worked out from the
     *                        shape of the directory (a main world only grows a {@code dimensions}
     *                        folder once a second world exists) and it cannot be assumed to be
     *                        {@code world}, which would protect a default installation and nothing
     *                        else. A test found both of those attempts
     */
    public static boolean mayDelete(Path serverDirectory, Path candidate, String worldName,
                                    String levelName) {
        if (serverDirectory == null || candidate == null || worldName == null
                || worldName.isBlank() || levelName == null || levelName.isBlank()) {
            return false;
        }
        if (worldName.equalsIgnoreCase(levelName)) {
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
            if (!folder.getFileName().toString().equals(worldName)) {
                return false;
            }
            // In one of the two shapes a world folder is allowed to have, and nowhere else. See
            // isAWorldFolderPosition — "anywhere under the server directory" would be every plugin's
            // data folder, and "directly in the server directory" was every real farm world refused.
            if (!isAWorldFolderPosition(server, folder)) {
                return false;
            }
            // And it has to actually be a world. A folder somebody happened to name "farmworld" is
            // not the farm world, and deleting it would be deleting whatever it really was.
            //
            // Either marker: the written one, or chunks on their own for a world that has not been saved
            // yet. An empty folder is neither and stays refused.
            return looksLikeAWorld(folder);
        } catch (IOException | RuntimeException unreadable) {
            // If it cannot even be resolved, it is certainly not something to delete.
            log.warn("Refusing to delete '{}': {}", candidate, String.valueOf(unreadable));
            return false;
        }
    }

    /**
     * Whether a folder holds a world.
     *
     * <p>A {@code level.dat}, or a region directory with something in it. The second is what a world that
     * has never been saved looks like, and it is the ordinary state of a farm world being regenerated.
     */
    public static boolean holdsAWorld(Path folder) {
        return folder != null && Files.isDirectory(folder) && looksLikeAWorld(folder);
    }

    private static boolean looksLikeAWorld(Path folder) {
        if (Files.isRegularFile(folder.resolve(WORLD_MARKER))) {
            return true;
        }
        Path chunks = folder.resolve(CHUNKS);
        if (!Files.isDirectory(chunks)) {
            return false;
        }
        try (java.util.stream.Stream<Path> inside = Files.list(chunks)) {
            return inside.findAny().isPresent();
        } catch (IOException unreadable) {
            return false;
        }
    }

    /** The folder Paper nests every non-server world under, inside the main world's own folder. */
    private static final String DIMENSIONS = "dimensions";

    /**
     * Whether a folder sits where a world folder is allowed to sit.
     *
     * <h2>The two shapes, and why it is a list of two rather than a depth check</h2>
     * <ul>
     *   <li><b>{@code <server>/<name>}</b> — the server's own worlds, and any world moved there by
     *       hand. What this class used to allow, and only this.</li>
     *   <li><b>{@code <server>/<level-name>/dimensions/<namespace>/<name>}</b> — where Paper 26.x
     *       actually puts a world created through {@code WorldCreator}, which is every farm world.
     *       Refusing it meant regeneration deleted nothing and reported success.</li>
     * </ul>
     *
     * <p>Written as exactly these two positions rather than "somewhere under the server directory"
     * because the loose version allows every plugin's data folder, and a farm world called
     * {@code config} would then delete one. The level name and the namespace are not checked against
     * anything — {@code level-name} is a server setting and a datapack may register its own namespace —
     * but their <em>position</em> is fixed, so a folder of the same shape under {@code plugins/} is
     * still refused.
     *
     * <p>The middle segment must be literally {@code dimensions}, which is what keeps the main world
     * itself out: {@code <server>/world} has one segment, not four, and matches neither shape.
     *
     * @param server the server directory, already resolved to a real path
     * @param folder the candidate, already resolved to a real path and known to be inside the server
     */
    private static boolean isAWorldFolderPosition(Path server, Path folder) {
        // Belt and braces over the level-name check in mayDelete: a folder that has other worlds
        // nested inside it is not a leaf, whatever it is called. Cheap, and this is the one function
        // in the library where a second guard against the same mistake is worth its keep.
        if (Files.isDirectory(folder.resolve(DIMENSIONS))) {
            return false;
        }
        Path inside = server.relativize(folder);
        if (inside.getNameCount() == 1) {
            return true;
        }
        // <level-name>/dimensions/<namespace>/<name>. Exactly four, so neither the dimensions folder
        // nor the namespace folder — each of which holds every farm world on the server — can match,
        // and nor can anything buried deeper.
        return inside.getNameCount() == 4
                && inside.getName(1).toString().equals(DIMENSIONS);
    }

    /**
     * Where a world's folder is, for a world that is not loaded and cannot be asked.
     *
     * <p>{@code FarmWorlds} asks the loaded {@link org.bukkit.World} itself wherever it can, because
     * that is the only authoritative answer and the only one that cannot go stale when Paper changes
     * its layout again. This is the fallback for a world that failed to load or was never made.
     *
     * <p>Empty when there is nothing there. Deliberately not "the path it would have been at": a
     * guessed path is a path something would later hand to a delete, and the whole reason this class
     * exists is that nothing about deleting is allowed to be a guess.
     *
     * @param serverDirectory where the server lives
     * @param levelName       the main world's name, from {@code level-name}
     * @param worldName       the world being looked for
     */
    public static Optional<Path> findWorldFolder(Path serverDirectory, String levelName,
                                                 String worldName) {
        if (serverDirectory == null || levelName == null || worldName == null
                || levelName.isBlank() || worldName.isBlank()) {
            return Optional.empty();
        }
        // The dimensions layout first: it is where anything this class made will actually be, so the
        // common case does not depend on the fallback below being right.
        List<Path> candidates = List.of(
                serverDirectory.resolve(levelName).resolve(DIMENSIONS).resolve("minecraft")
                        .resolve(worldName),
                serverDirectory.resolve(worldName));
        for (Path candidate : candidates) {
            if (looksLikeAWorld(candidate)) {
                return Optional.of(candidate);
            }
        }
        // A namespace other than minecraft, which a datapack dimension may have. Looked for rather
        // than guessed at, and only one level deep.
        Path namespaces = serverDirectory.resolve(levelName).resolve(DIMENSIONS);
        if (Files.isDirectory(namespaces)) {
            try (java.util.stream.Stream<Path> found = Files.list(namespaces)) {
                return found
                        .map(namespace -> namespace.resolve(worldName))
                        .filter(FarmWorldState::looksLikeAWorld)
                        .findFirst();
            } catch (IOException unreadable) {
                log.warn("Could not look for '{}' under {}: {}", worldName, namespaces,
                        String.valueOf(unreadable));
            }
        }
        return Optional.empty();
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
        // Drained rather than snapshotted — see Marks. Copying the marks and clearing them
        // afterwards loses any change that arrives while the write is running.
        Set<String> writing = Marks.drain(changedTimes);
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
        if (!written) {
            Marks.restore(changedTimes, writing);
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
