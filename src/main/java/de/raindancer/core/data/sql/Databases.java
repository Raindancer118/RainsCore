package de.raindancer.core.data.sql;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Every database this server keeps, by name.
 *
 * <h2>Why more than one</h2>
 * Because they are not all the same kind of thing, and one file would force the slowest and largest
 * of them onto the rest:
 *
 * <ul>
 *   <li><b>{@code core}</b> — what the server <em>is</em>: warps, homes, custom items, loot tables,
 *       achievements. Small, read constantly, written rarely, and the thing you want a backup of.</li>
 *   <li><b>{@code audit}</b> — what was <em>done</em>: an append-only journal that only grows, is
 *       written far more often than everything else put together, and is thrown away by age. Keeping
 *       it beside the warps would mean every backup of a handful of warps carries a year of history,
 *       and every write of a history line contends with reading them.</li>
 * </ul>
 *
 * <p>They are also thrown away differently, which is the deciding argument: an audit journal has a
 * retention period and the rest does not. One file cannot have two retention policies.
 *
 * <h2>Names are files</h2>
 * A name becomes {@code <name>.db} in the plugin's folder, so what is on disk matches what the code
 * calls it. Anybody looking at a data folder can tell what each file is without reading any code.
 */
public final class Databases implements AutoCloseable {

    private static final LogChannel log = Log.of("sql");

    /** What the server is: warps, homes, items, loot, achievements. */
    public static final String CORE = "core";

    /** What was done: the audit journal, which only grows and is aged out. */
    public static final String AUDIT = "audit";

    private final Path folder;
    private final BooleanSupplier onServerThread;
    private final Map<String, Database> open = new ConcurrentHashMap<>();

    /**
     * @param folder         where the files live, usually the plugin's data folder
     * @param onServerThread how to tell whether the caller is on a thread running the world
     */
    public Databases(Path folder, BooleanSupplier onServerThread) {
        this.folder = folder;
        this.onServerThread = onServerThread;
    }

    /**
     * The named database, opened and brought up to date the first time it is asked for.
     *
     * <p>Opened once and shared, because two {@link Database} objects over one file would be two
     * write locks that know nothing about each other — and SQLite's answer to that is to fail one of
     * them under load rather than to queue it.
     *
     * <p>Asking twice with different schemas is a programming mistake and is reported as one: the
     * first schema wins, because it is the one the tables were built from.
     */
    public Database of(String name, Schema schema) {
        return open.computeIfAbsent(name, key ->
                Database.open(folder.resolve(key + ".db"), schema, onServerThread));
    }

    /**
     * The core database, with every table this library keeps in it.
     *
     * <p>One schema for the whole file rather than one per subsystem, which was the first attempt and
     * does not work: the version is a single number per database, so two subsystems each counting
     * their own steps would each think the other's progress was theirs. The second one to be applied
     * would find the version already past its own step count and skip its tables entirely — and it
     * would do so silently, on a fresh install, with the failure appearing later as a missing table.
     *
     * <p>Keeping the whole shape in one ordered list also means there is exactly one place to read to
     * know what is in the file, which is worth more than having each subsystem's tables next to its
     * code.
     */
    public Database core() {
        return of(CORE, CoreSchema.CORE);
    }

    /** The audit journal, which is its own file because it only grows and is aged out. */
    public Database audit() {
        return of(AUDIT, CoreSchema.AUDIT);
    }

    /** Whether a database is open and complete. */
    public boolean isUsable(String name) {
        Database database = open.get(name);
        return database != null && database.isUsable();
    }

    public Collection<Database> all() {
        return java.util.List.copyOf(open.values());
    }

    /** How often any database has been used from a thread that is running the world. */
    public int workOnServerThread() {
        return open.values().stream().mapToInt(Database::workOnServerThread).sum();
    }

    /**
     * Closes all of them.
     *
     * <p>Each one separately and each one guarded, because a database that will not close must not
     * stop the next one from being closed cleanly — an unclosed database is the one that leaves a
     * write-ahead log behind.
     */
    @Override
    public void close() {
        open.values().forEach(database -> {
            try {
                database.close();
            } catch (RuntimeException failed) {
                log.error(failed, "Could not close {} cleanly.", database.file());
            }
        });
        open.clear();
    }
}
