package de.raindancer.core.log;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The one logger. Every plugin here writes through it and none of them owns a logger of its own.
 *
 * <h2>Why not {@code plugin.getLogger()}</h2>
 * Nine plugins each calling {@code getLogger()} gave nine differently-shaped console lines, no
 * common level, and — the reason this exists at all — <em>nothing written down</em>. When a server
 * owner said "it broke last night", the answer was whatever had not yet scrolled out of the console
 * buffer. Paper's own {@code logs/latest.log} has the lines in it, mixed in with every other plugin's
 * and with the server's, and gone after a handful of restarts.
 *
 * <p>So: one facade, one file, one level, and a channel name per subsystem so a line still says
 * where it came from. The console keeps getting everything it used to get — that is where an admin
 * looks first — and the same line goes to {@code plugins/RainsCore/logs/} where it survives.
 *
 * <h2>Using it</h2>
 * <pre>
 * private static final LogChannel log = Log.of("claims");
 * ...
 * log.warn("Claim {} has a degenerate shape ({} vertices) — skipping.", id, count);
 * log.error(failure, "Could not write {}", file);
 * </pre>
 * A channel is a constant, taken once. Taking one before {@link #configure} has run is fine and is
 * the normal case for a static field: the channel resolves the destination per line, so a message
 * logged before startup finished lands on the console and one logged after also lands in the file.
 *
 * <h2>Thread safety</h2>
 * Everything here is safe from any thread, which on Folia means every thread. Nothing blocks: the
 * file is written by {@link LogFile}'s own thread, and the console call is the same one
 * {@code java.util.logging} was already doing.
 */
public final class Log {

    /** Where lines go before anybody has configured anything — a test, or a very early failure. */
    private static final Logger FALLBACK_CONSOLE = Logger.getLogger("RainsCore");

    private static final Map<String, LogChannel> CHANNELS = new ConcurrentHashMap<>();

    private static volatile Logger console = FALLBACK_CONSOLE;
    private static volatile LogLevel consoleThreshold = LogLevel.INFO;
    private static volatile LogLevel fileThreshold = LogLevel.INFO;
    private static volatile LogFile file;

    private Log() {
    }

    /**
     * Installed once, by {@code RainsCorePlugin}, as early in {@code onEnable} as possible.
     *
     * <p>Re-configuring is allowed and is what a settings change does: the old file is closed and a
     * new one opened, so a server owner who changes the retention or the level does not have to
     * restart. Passing the same directory keeps writing the same day's file.
     *
     * @param logsDirectory where the files live, e.g. {@code plugins/RainsCore/logs}
     * @param serverConsole the console logger to mirror to; the host plugin's, so Paper tags it
     * @param forConsole    the lowest level the console shows
     * @param forFile       the lowest level that is written down
     * @param retentionDays how many days of files to keep
     */
    public static synchronized void configure(Path logsDirectory, Logger serverConsole,
                                              LogLevel forConsole, LogLevel forFile,
                                              int retentionDays) {
        console = serverConsole == null ? FALLBACK_CONSOLE : serverConsole;
        consoleThreshold = forConsole == null ? LogLevel.INFO : forConsole;
        fileThreshold = forFile == null ? LogLevel.INFO : forFile;

        LogFile previous = file;
        file = logsDirectory == null ? null
                : new LogFile(logsDirectory, retentionDays, ZoneId.systemDefault());
        if (previous != null) {
            // Not close(): this runs on the main thread when an admin changes a setting, and close()
            // waits for the backlog. A settings change must not be able to freeze the server for as
            // long as the disk takes. Nothing after this point depends on the old file being
            // finished, so it is finished on a thread of its own.
            previous.closeInBackground();
        }
    }

    /**
     * The channel for one subsystem — {@code "claims"}, {@code "towns"}, {@code "gui"}.
     *
     * <p>Cached, so taking the same channel from a hundred classes costs one object. The name is
     * what appears in square brackets in the file, and it is deliberately the subsystem rather than
     * the class: {@code [claims]} is what somebody reading a logfile wants, {@code [ClaimServiceImpl]}
     * is what a stack trace already tells them.
     */
    public static LogChannel of(String channel) {
        String name = channel == null || channel.isBlank() ? "core" : channel.trim();
        return CHANNELS.computeIfAbsent(name, LogChannel::new);
    }

    /** Whether a line at this level reaches the console right now. */
    static boolean consoleWants(LogLevel level) {
        return level.atLeast(consoleThreshold);
    }

    /**
     * Whether a line at this level is written down right now.
     *
     * <p>{@link LogLevel#FATAL} always is, whatever the threshold says: the one thing a logfile has
     * to contain is the reason the plugin stopped working.
     */
    static boolean fileWants(LogLevel level) {
        return level == LogLevel.FATAL || level.atLeast(fileThreshold);
    }

    static Logger console() {
        return console;
    }

    static LogFile fileSink() {
        return file;
    }

    /** Where the logs are, for the answer to "where do I find them" — or null when none are written. */
    public static Path currentFile() {
        LogFile sink = file;
        return sink == null ? null : sink.currentFile();
    }

    /**
     * Flushes and closes the file. Called from {@code onDisable}, and only from there.
     *
     * <p>Reports dropped lines if there were any, because a file with silent holes is worse than one
     * that admits to them.
     */
    public static synchronized void shutdown() {
        LogFile sink = file;
        file = null;
        if (sink == null) {
            return;
        }
        long dropped = sink.droppedLines();
        if (dropped > 0) {
            console.warning("[core] " + dropped + " log line(s) were dropped because the logger "
                    + "could not keep up; the file is missing that many entries.");
        }
        sink.close();
    }
}
