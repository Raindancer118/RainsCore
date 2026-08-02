package de.raindancer.core.log;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class LogChannelTest {

    @AfterEach
    void resetTheLogger() {
        Log.shutdown();
        Log.configure(null, null, LogLevel.INFO, LogLevel.INFO, 7);
    }

    // ------------------------------------------------------------------ formatting

    @Test
    @DisplayName("placeholders are filled left to right")
    void fillsPlaceholders() {
        assertThat(LogChannel.format("Claim {} skipped: {}", "abc", "no world"))
                .isEqualTo("Claim abc skipped: no world");
    }

    @Test
    @DisplayName("a percent sign is a percent sign, not a format specifier")
    void doesNotInterpretPercent() {
        assertThat(LogChannel.format("pack is 50% done")).isEqualTo("pack is 50% done");
        assertThat(LogChannel.format("{}% of {}", 50, "the pack")).isEqualTo("50% of the pack");
    }

    @Test
    @DisplayName("more arguments than placeholders are appended rather than lost")
    void appendsSpareArguments() {
        assertThat(LogChannel.format("only one: {}", "a", "b", "c"))
                .isEqualTo("only one: a b, c");
    }

    @Test
    @DisplayName("more placeholders than arguments are left visible rather than filled with null")
    void leavesUnmatchedPlaceholders() {
        assertThat(LogChannel.format("{} and {}", "a")).isEqualTo("a and {}");
    }

    @Test
    @DisplayName("a toString() that throws costs its own argument, not the whole line")
    void survivesABrokenToString() {
        Object broken = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("no");
            }
        };
        assertThat(LogChannel.format("value was {}", broken))
                .startsWith("value was <")
                .contains("IllegalStateException");
    }

    @Test
    @DisplayName("null is written as null rather than throwing")
    void handlesNulls() {
        assertThat(LogChannel.format("{}", new Object[] {null})).isEqualTo("null");
        assertThat(LogChannel.format(null)).isEmpty();
    }

    // ------------------------------------------------------------------- routing

    @Test
    @DisplayName("a channel is one object however often it is asked for")
    void cachesChannels() {
        assertThat(Log.of("claims")).isSameAs(Log.of("claims"));
        assertThat(Log.of("claims")).isNotSameAs(Log.of("towns"));
        assertThat(Log.of("  ")).isSameAs(Log.of("core"));
    }

    @Test
    @DisplayName("the line reaches both the console and the file, with the channel on it")
    void writesToBothDestinations(@TempDir Path directory) throws IOException {
        Recorder recorder = new Recorder();
        Logger console = Logger.getAnonymousLogger();
        console.setUseParentHandlers(false);
        console.addHandler(recorder);

        Log.configure(directory, console, LogLevel.INFO, LogLevel.INFO, 7);
        Log.of("claims").warn("Claim {} skipped", "abc");
        Log.shutdown();

        assertThat(recorder.messages).containsExactly("[claims] Claim abc skipped");
        assertThat(todaysLines(directory)).singleElement().asString()
                .contains("[WARN]").contains("[claims]").endsWith("Claim abc skipped");
    }

    @Test
    @DisplayName("a level below the threshold reaches neither destination")
    void respectsTheThreshold(@TempDir Path directory) throws IOException {
        Recorder recorder = new Recorder();
        Logger console = Logger.getAnonymousLogger();
        console.setUseParentHandlers(false);
        console.addHandler(recorder);

        Log.configure(directory, console, LogLevel.WARN, LogLevel.WARN, 7);
        Log.of("claims").info("nobody wants this");
        Log.of("claims").error("but they want this");
        Log.shutdown();

        assertThat(recorder.messages).containsExactly("[claims] but they want this");
        assertThat(todaysLines(directory)).singleElement().asString().contains("but they want this");
    }

    @Test
    @DisplayName("FATAL is written down even when the file threshold is above it")
    void alwaysWritesFatal(@TempDir Path directory) throws IOException {
        Log.configure(directory, Logger.getAnonymousLogger(), LogLevel.FATAL, LogLevel.FATAL, 7);
        Log.of("claims").info("filtered out");
        Log.of("claims").fatal("cannot start");
        Log.shutdown();

        assertThat(todaysLines(directory)).singleElement().asString()
                .contains("[FATAL]").endsWith("cannot start");
    }

    @Test
    @DisplayName("a stack trace lands under the line it belongs to")
    void writesStackTraces(@TempDir Path directory) throws IOException {
        Log.configure(directory, Logger.getAnonymousLogger(), LogLevel.INFO, LogLevel.INFO, 7);
        Log.of("storage").error(new IllegalStateException("disk went away"), "Could not write {}",
                "claims.yml");
        Log.shutdown();

        String written = Files.readString(todaysFile(directory));
        assertThat(written)
                .contains("Could not write claims.yml")
                .contains("java.lang.IllegalStateException: disk went away")
                .contains("at de.raindancer.core.log.LogChannelTest");
    }

    @Test
    @DisplayName("logging before configure() runs goes to the console and does not throw")
    void worksBeforeStartup() {
        Log.shutdown();
        Log.configure(null, null, LogLevel.INFO, LogLevel.INFO, 7);
        Log.of("early").warn("this happens during class loading");
        assertThat(Log.currentFile()).isNull();
    }

    // ------------------------------------------------------------------ level parsing

    @Test
    @DisplayName("the spellings people actually type are understood")
    void parsesLevels() {
        assertThat(LogLevel.parse("warn", LogLevel.INFO)).isEqualTo(LogLevel.WARN);
        assertThat(LogLevel.parse("WARNING", LogLevel.INFO)).isEqualTo(LogLevel.WARN);
        assertThat(LogLevel.parse("  Debug ", LogLevel.INFO)).isEqualTo(LogLevel.DEBUG);
        assertThat(LogLevel.parse("severe", LogLevel.INFO)).isEqualTo(LogLevel.ERROR);
        assertThat(LogLevel.parse("nonsense", LogLevel.INFO)).isEqualTo(LogLevel.INFO);
        assertThat(LogLevel.parse(null, LogLevel.WARN)).isEqualTo(LogLevel.WARN);
    }

    // ------------------------------------------------------------------ helpers

    private static Path todaysFile(Path directory) {
        return directory.resolve("rainscore-"
                + DateTimeFormatter.ofPattern("yyyy-MM-dd").format(LocalDate.now()) + ".log");
    }

    private static List<String> todaysLines(Path directory) throws IOException {
        return Files.readAllLines(todaysFile(directory));
    }

    /** Captures what the console was told, so the routing can be asserted without a server. */
    private static final class Recorder extends Handler {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            messages.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isLoggable(LogRecord record) {
            return record.getLevel().intValue() >= Level.ALL.intValue();
        }
    }
}
