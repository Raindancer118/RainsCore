package de.raindancer.core.log;

import java.util.Locale;
import java.util.logging.Level;

/**
 * How much a line matters.
 *
 * <h2>Why not {@link java.util.logging.Level} directly</h2>
 * {@code Level} has nine constants, a public constructor and an integer ordering, which invites
 * exactly the sprawl this refactor exists to end: one plugin logging at {@code CONFIG}, another at
 * {@code FINE}, and a server owner with no way to say "show me the problems". Five levels are enough
 * for a Minecraft plugin, and being an enum means the console filter, the file filter and the GUI
 * dropdown all read the same list.
 */
public enum LogLevel {

    /** Only interesting while chasing something; off on a normal server. */
    DEBUG(Level.FINE, "DEBUG"),
    /** Something happened that a server owner would want to know about. */
    INFO(Level.INFO, "INFO"),
    /** Something is wrong but the plugin carried on, possibly by guessing. */
    WARN(Level.WARNING, "WARN"),
    /** Something failed. A player noticed, or will. */
    ERROR(Level.SEVERE, "ERROR"),
    /**
     * The plugin cannot do its job at all.
     *
     * <p>Distinct from {@link #ERROR} because it is the level that answers "why is this plugin
     * disabled": a failed startup, a data file that cannot be read, a dependency that is missing.
     * Always written to disk, whatever the file threshold says.
     */
    FATAL(Level.SEVERE, "FATAL");

    private final Level consoleLevel;
    private final String label;

    LogLevel(Level consoleLevel, String label) {
        this.consoleLevel = consoleLevel;
        this.label = label;
    }

    /** How this reaches the server console, which speaks {@code java.util.logging}. */
    public Level consoleLevel() {
        return consoleLevel;
    }

    /** Fixed-width-ish name for the logfile. */
    public String label() {
        return label;
    }

    /** Whether a line at this level passes a threshold set to {@code minimum}. */
    public boolean atLeast(LogLevel minimum) {
        return ordinal() >= minimum.ordinal();
    }

    /**
     * The level named by a config value, or {@code fallback} for anything unrecognised.
     *
     * <p>Deliberately forgiving: a typo in {@code logging.level} should not stop the server, and a
     * plugin that refuses to start because somebody wrote {@code warning} instead of {@code warn} is
     * worse than one that logs a little more than asked.
     */
    public static LogLevel parse(String raw, LogLevel fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String cleaned = raw.trim().toUpperCase(Locale.ROOT);
        for (LogLevel level : values()) {
            if (level.name().equals(cleaned)) {
                return level;
            }
        }
        // The two spellings people actually type.
        return switch (cleaned) {
            case "WARNING" -> WARN;
            case "SEVERE", "ERR" -> ERROR;
            case "FINE", "TRACE", "VERBOSE" -> DEBUG;
            case "OFF", "NONE" -> FATAL;
            default -> fallback;
        };
    }
}
