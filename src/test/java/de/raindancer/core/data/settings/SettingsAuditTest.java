package de.raindancer.core.data.settings;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.log.LogLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SettingsAudit}: the ordering, the blank-message guard, and the two properties that decide whether
 * anybody ever reads one of these blocks.
 *
 * <p>The first is that <b>nothing is said when there is nothing to say</b>. An audit that announced every
 * boot that the configuration was fine would be scrolled past exactly as fast as one that was wrong, and it
 * would teach the same habit. The second is that <b>the worst thing is first</b>, because on a console the
 * first line is the one that gets read.
 */
class SettingsAuditTest {

    /**
     * Everything the audit put on the console during a test.
     *
     * <p>Captured through {@link Log}'s own console logger rather than by faking a {@code LogChannel} —
     * which cannot be done from here anyway, since it is final with a package-private constructor, and
     * Core deliberately has no mocking library. Attaching a {@link Handler} to a plain JUL logger and
     * handing it to {@code Log.configure} is the seam Core already has, and it exercises the real
     * formatting rather than intercepting the call before it happens.
     */
    private final List<String> logged = new ArrayList<>();

    private LogChannel log;

    @BeforeEach
    void captureTheConsole() {
        logged.clear();
        Logger console = Logger.getLogger("SettingsAuditTest");
        console.setUseParentHandlers(false);
        for (Handler existing : console.getHandlers()) {
            console.removeHandler(existing);
        }
        console.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        console.setLevel(Level.ALL);

        // No logs directory: nothing is written to disk by a test. Everything else defaults.
        Log.configure(null, console, LogLevel.DEBUG, LogLevel.DEBUG, 1);
        log = Log.of("audit-test");
    }

    @Nested
    @DisplayName("an audit with nothing in it")
    class Quiet {

        @Test
        @DisplayName("says nothing at all")
        void nothingIsLogged() {
            int reported = new SettingsAudit().report(log, "This configuration");

            assertThat(reported).isZero();
            assertThat(logged)
                    .as("an audit that announced every boot that everything was fine would be scrolled "
                            + "past exactly as fast as one that was wrong")
                    .isEmpty();
        }

        @Test
        @DisplayName("is empty, and knows it")
        void itSaysSo() {
            SettingsAudit audit = new SettingsAudit();

            assertThat(audit.isEmpty()).isTrue();
            assertThat(audit.hasBroken()).isFalse();
            assertThat(audit.findings()).isEmpty();
            assertThat(audit.lines()).isEmpty();
        }
    }

    @Nested
    @DisplayName("what gets recorded")
    class Recording {

        @Test
        @DisplayName("both severities, kept apart")
        void twoKinds() {
            SettingsAudit audit = new SettingsAudit()
                    .broken("the border cannot finish closing before the round ends")
                    .questionable("the later monster waves will never arrive");

            assertThat(audit.size()).isEqualTo(2);
            assertThat(audit.broken()).hasSize(1);
            assertThat(audit.hasBroken()).isTrue();
        }

        @Test
        @DisplayName("the broken ones come first")
        void worstFirst() {
            SettingsAudit audit = new SettingsAudit()
                    .questionable("first noticed, less important")
                    .broken("noticed second, matters more");

            // On a console the first line is the one that gets read.
            assertThat(audit.findings().get(0).isBroken()).isTrue();
            assertThat(audit.findings().get(1).isBroken()).isFalse();
        }

        @Test
        @DisplayName("order within a severity is the order things were noticed")
        void stableWithinASeverity() {
            SettingsAudit audit = new SettingsAudit()
                    .broken("one")
                    .broken("two")
                    .broken("three");

            // The checks a caller runs are usually in the order somebody would read the config, and
            // reshuffling them makes three findings about one mistake look like three mistakes.
            assertThat(audit.findings())
                    .extracting(SettingsAudit.Finding::message)
                    .containsExactly("one", "two", "three");
        }

        @Test
        @DisplayName("a blank message is dropped rather than logged as an empty bullet")
        void nothingSaysNothing() {
            SettingsAudit audit = new SettingsAudit()
                    .broken("")
                    .broken("   ")
                    .broken(null)
                    .questionable("something real");

            // A caller building a sentence from a value that turned out to be absent would otherwise
            // produce a warning that says nothing — the fastest way to teach somebody to skip the block.
            assertThat(audit.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("messages are trimmed, so indentation in a formatted string does not survive")
        void tidyText() {
            SettingsAudit audit = new SettingsAudit().broken("  the border is too fast  ");

            assertThat(audit.findings().get(0).message()).isEqualTo("the border is too fast");
        }

        @Test
        @DisplayName("the conditional forms add nothing when the condition is false")
        void theIfHelpers() {
            SettingsAudit audit = new SettingsAudit()
                    .brokenIf(false, "not this one")
                    .questionableIf(false, "nor this")
                    .brokenIf(true, "but this one")
                    .questionableIf(true, "and this");

            assertThat(audit.findings())
                    .extracting(SettingsAudit.Finding::message)
                    .containsExactly("but this one", "and this");
        }
    }

    @Nested
    @DisplayName("the block that reaches the console")
    class Reporting {

        @Test
        @DisplayName("names the subject, the count and how many will not work")
        void theHeading() {
            SettingsAudit audit = new SettingsAudit()
                    .broken("the border cannot finish closing")
                    .questionable("the waves run long");

            int reported = audit.report(log, "This configuration");

            assertThat(reported).isEqualTo(2);
            assertThat(logged.get(0))
                    .as("a reader has to be able to tell from the first line whether to act")
                    .contains("This configuration")
                    .contains("2 problem(s)")
                    .contains("1 that will not work");
        }

        @Test
        @DisplayName("marks the two severities differently but logs both as warnings")
        void bothAreWarnings() {
            new SettingsAudit()
                    .broken("cannot happen")
                    .questionable("will surprise somebody")
                    .report(log, "The border");

            // Two markers, one level. Splitting these across levels would hide the questionable ones on a
            // server filtered to warn-and-above, which is most of them.
            assertThat(logged).anyMatch(line -> line.contains("[!]"));
            assertThat(logged).anyMatch(line -> line.contains("[?]"));
        }

        @Test
        @DisplayName("says out loud that none of it stops the plugin")
        void theClosingLine() {
            new SettingsAudit().broken("cannot happen").report(log, "This configuration");

            // Without this, "problem(s) that will not work as written" reads as a startup failure and
            // somebody rolls back a release over a warning about their own config.
            assertThat(logged).anyMatch(line -> line.contains("stops the plugin running"));
        }

        @Test
        @DisplayName("a heading without the word \"problem\" when nothing is broken")
        void nothingBroken() {
            new SettingsAudit()
                    .questionable("the waves run long")
                    .report(log, "This configuration");

            assertThat(logged.get(0))
                    .as("calling a judgement a problem is how BROKEN stops meaning anything")
                    .doesNotContain("problem(s)")
                    .contains("worth a look");
        }

        @Test
        @DisplayName("a null log is not a crash")
        void nowhereToWrite() {
            // A module built for a test, or one whose log channel is not up yet. Reporting into nothing is
            // a no-op that still answers how much there was.
            assertThat(new SettingsAudit().broken("something").report(null, "This")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the lines form is the same content, marked, for a screen or an API")
    void asLines() {
        List<String> lines = new SettingsAudit()
                .questionable("a judgement")
                .broken("a contradiction")
                .lines();

        assertThat(lines).containsExactly("[!] a contradiction", "[?] a judgement");
    }
}
