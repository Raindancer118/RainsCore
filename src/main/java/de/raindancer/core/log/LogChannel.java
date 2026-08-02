package de.raindancer.core.log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * One subsystem's door to {@link Log}.
 *
 * <p>Held as a {@code private static final} field, named for the subsystem rather than the class.
 * Every method is safe from any thread and none of them blocks.
 *
 * <h2>The {@code {}} placeholder</h2>
 * {@code log.warn("Claim {} skipped: {}", id, reason)} rather than string concatenation, for the
 * usual reason — the arguments are only turned into text when the line is actually going somewhere,
 * so a {@code debug} call on a server that has debug switched off costs nothing but the call. The
 * placeholder is deliberately not {@code java.util.Formatter}'s {@code %s}: a log message containing
 * a literal percent sign is common (a percentage, a URL escape) and having that throw at runtime,
 * inside the error handler, is how a logger takes a server down.
 */
public final class LogChannel {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final String name;

    LogChannel(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public void debug(String message, Object... arguments) {
        log(LogLevel.DEBUG, null, message, arguments);
    }

    public void info(String message, Object... arguments) {
        log(LogLevel.INFO, null, message, arguments);
    }

    public void warn(String message, Object... arguments) {
        log(LogLevel.WARN, null, message, arguments);
    }

    public void warn(Throwable cause, String message, Object... arguments) {
        log(LogLevel.WARN, cause, message, arguments);
    }

    public void error(String message, Object... arguments) {
        log(LogLevel.ERROR, null, message, arguments);
    }

    public void error(Throwable cause, String message, Object... arguments) {
        log(LogLevel.ERROR, cause, message, arguments);
    }

    /** The plugin cannot do its job. Always written down, whatever the file threshold says. */
    public void fatal(Throwable cause, String message, Object... arguments) {
        log(LogLevel.FATAL, cause, message, arguments);
    }

    public void fatal(String message, Object... arguments) {
        log(LogLevel.FATAL, null, message, arguments);
    }

    /** Whether a {@code debug} call would go anywhere — for the rare block that is expensive to build. */
    public boolean isDebugEnabled() {
        return Log.consoleWants(LogLevel.DEBUG) || Log.fileWants(LogLevel.DEBUG);
    }

    private void log(LogLevel level, Throwable cause, String message, Object... arguments) {
        boolean toConsole = Log.consoleWants(level);
        boolean toFile = Log.fileWants(level);
        if (!toConsole && !toFile) {
            return;
        }
        String text = format(message, arguments);
        if (toConsole) {
            // Prefixed with the channel, not the level: java.util.logging already prints the level,
            // and printing it twice is how a console line becomes unreadable.
            Log.console().log(level.consoleLevel(), "[" + name + "] " + text, cause);
        }
        LogFile sink = Log.fileSink();
        if (toFile && sink != null) {
            sink.write(line(level, text, cause));
        }
    }

    /**
     * One finished line for the file: time, level, channel, message — and the stack trace beneath it
     * when there is one.
     *
     * <p>Only the time, not the date: the file is named for the day, so repeating it on every line
     * costs ten characters times a hundred thousand lines and tells nobody anything.
     */
    private String line(LogLevel level, String text, Throwable cause) {
        StringBuilder built = new StringBuilder(text.length() + 48);
        built.append(TIME.format(LocalTime.now()))
                .append(" [").append(level.label()).append("] [").append(name).append("] ")
                .append(text);
        if (cause != null) {
            StringWriter trace = new StringWriter();
            cause.printStackTrace(new PrintWriter(trace));
            built.append(System.lineSeparator()).append(trace);
        }
        return built.toString();
    }

    /**
     * Substitutes {@code {}} placeholders, left to right.
     *
     * <p>Extra arguments are appended rather than dropped — a call that has drifted out of step with
     * its message should still show what it was trying to say — and extra placeholders are left as
     * they are, which reads as the mistake it is.
     */
    static String format(String message, Object... arguments) {
        String template = message == null ? "" : message;
        if (arguments == null || arguments.length == 0) {
            return template;
        }
        StringBuilder built = new StringBuilder(template.length() + 16 * arguments.length);
        int next = 0;
        int from = 0;
        while (next < arguments.length) {
            int at = template.indexOf("{}", from);
            if (at < 0) {
                break;
            }
            built.append(template, from, at).append(text(arguments[next++]));
            from = at + 2;
        }
        built.append(template.substring(from));
        for (int spare = next; spare < arguments.length; spare++) {
            built.append(spare == next ? " " : ", ").append(text(arguments[spare]));
        }
        return built.toString();
    }

    /**
     * One argument as text.
     *
     * <p>A {@code toString()} that throws must not take the logger with it: the object is already
     * suspect — that is usually why it is being logged — and losing the whole line to it would hide
     * the very thing being reported.
     */
    private static String text(Object argument) {
        if (argument == null) {
            return "null";
        }
        try {
            return String.valueOf(argument);
        } catch (RuntimeException broken) {
            return "<" + argument.getClass().getSimpleName() + ".toString() threw "
                    + broken.getClass().getSimpleName() + ">";
        }
    }
}
