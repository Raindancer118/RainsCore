package de.raindancer.core.ui.messages;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bringing an existing {@code messages.yml} up to date without touching what somebody wrote in it.
 *
 * <h2>The problem this solves</h2>
 * The file is written when it is absent and never again, which is the right default — nobody wants their
 * carefully reworded messages replaced by an update. The cost is that a server upgrading across a release with
 * new messages keeps a file that simply does not mention them. The plugin runs fine, because every read falls
 * back to the wording in the jar, but an admin opening the file cannot see the new lines, let alone change
 * them. After one release here that was over a hundred keys.
 *
 * <h2>The rules, and why each one</h2>
 * <ul>
 *   <li><b>Adds what is missing.</b> That is the whole point.</li>
 *   <li><b>Never overwrites an existing key</b>, whatever its value. Somebody who set a message to an empty
 *       string meant it.</li>
 *   <li><b>Never deletes a key the jar no longer has.</b> It might be a leftover; it might be something a fork
 *       or the next version reads. Reported, not removed.</li>
 *   <li><b>Writes nothing when there is nothing to add</b>, so an up-to-date server restarting does not even
 *       change the file's timestamp.</li>
 *   <li><b>Backs the file up before rewriting it.</b> This is the one place the plugin edits something a human
 *       wrote, and being able to undo it is the difference between a merge people accept and one they turn
 *       off.</li>
 * </ul>
 */
class MergingNewKeysTest {

    @TempDir
    Path folder;

    private Path file;

    private static final String IN_THE_JAR = """
            prefix: "<gray>[Core] "
            greeting: "Hello"
            refusal: "No"
            nested:
              one: "First"
              two: "Second"
            added-later: "Something new"
            """;

    @BeforeEach
    void setUp() {
        file = folder.resolve("messages.yml");
    }

    private Messages messages() {
        return new Messages(file);
    }

    private static ByteArrayInputStream jar() {
        return new ByteArrayInputStream(IN_THE_JAR.getBytes(StandardCharsets.UTF_8));
    }

    private void existing(String content) throws IOException {
        Files.writeString(file, content);
    }

    private String onDisk() throws IOException {
        return Files.readString(file);
    }

    @Test
    @DisplayName("a missing file is written whole, as before")
    void anAbsentFileIsStillJustWritten() {
        assertThat(messages().mergeMissing(jar())).isEqualTo(-1);
        assertThat(file).exists();
    }

    @Test
    @DisplayName("a key the jar added appears in the file")
    void newKeysAreAdded() throws IOException {
        existing("""
                prefix: "<gray>[Core] "
                greeting: "Hello"
                refusal: "No"
                nested:
                  one: "First"
                  two: "Second"
                """);

        assertThat(messages().mergeMissing(jar())).isEqualTo(1);
        assertThat(onDisk()).contains("added-later");
    }

    @Test
    @DisplayName("what somebody wrote is left exactly alone")
    void existingWordingSurvives() throws IOException {
        existing("""
                prefix: "<gold>[My Server] "
                greeting: "Welcome, friend"
                """);

        messages().mergeMissing(jar());

        String after = onDisk();
        assertThat(after).contains("<gold>[My Server] ");
        assertThat(after).contains("Welcome, friend");
        assertThat(after).doesNotContain("Hello");
    }

    @Test
    @DisplayName("a message deliberately emptied stays empty")
    void anEmptyStringIsADecision() throws IOException {
        // Somebody who set a message to "" wanted silence. Filling it back in from the jar is the merge
        // undoing a decision, which is exactly what makes people switch a merge off.
        existing("""
                prefix: ""
                greeting: "Hello"
                """);

        messages().mergeMissing(jar());

        Messages loaded = messages();
        loaded.load(jar());
        assertThat(onDisk()).contains("prefix: \"\"");
    }

    @Test
    @DisplayName("a key the jar no longer has is left where it is")
    void nothingIsDeleted() throws IOException {
        existing("""
                prefix: "<gray>[Core] "
                greeting: "Hello"
                something-we-removed: "Still here"
                """);

        messages().mergeMissing(jar());

        assertThat(onDisk())
                .as("it may be a leftover, or something a fork reads — reported, never removed")
                .contains("something-we-removed");
    }

    @Test
    @DisplayName("nested keys are added under their own section")
    void nestingIsKept() throws IOException {
        existing("""
                prefix: "<gray>[Core] "
                nested:
                  one: "Mine"
                """);

        messages().mergeMissing(jar());

        Messages loaded = messages();
        loaded.load(jar());
        assertThat(loaded.raw("nested.one")).isEqualTo("Mine");
        assertThat(loaded.raw("nested.two")).isEqualTo("Second");
    }

    @Test
    @DisplayName("an up-to-date file is not rewritten at all")
    void nothingToDoMeansNothingIsWritten() throws IOException {
        existing(IN_THE_JAR);
        long before = Files.getLastModifiedTime(file).toMillis();

        assertThat(messages().mergeMissing(jar())).isZero();
        assertThat(Files.getLastModifiedTime(file).toMillis())
                .as("an up-to-date server restarting should not even touch the timestamp")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("the file is backed up before it is rewritten")
    void aBackupIsLeftBehind() throws IOException {
        existing("""
                prefix: "<gold>[Mine] "
                """);

        messages().mergeMissing(jar());

        try (var files = Files.list(folder)) {
            List<String> names = files.map(path -> path.getFileName().toString()).toList();
            assertThat(names)
                    .as("this is the one place the plugin edits something a human wrote")
                    .anyMatch(name -> name.startsWith("messages.yml.") && name.endsWith(".bak"));
        }
    }

    @Test
    @DisplayName("no backup when nothing was changed")
    void anUntouchedFileLeavesNoLitter() throws IOException {
        existing(IN_THE_JAR);

        messages().mergeMissing(jar());

        try (var files = Files.list(folder)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactly("messages.yml");
        }
    }

    @Test
    @DisplayName("a file that is not valid YAML is left alone rather than mangled")
    void abrokenFileIsNotMadeWorse() throws IOException {
        // Somebody mid-edit, or a file cut short by a full disk. Rewriting it from a half-parse would lose
        // whatever they were in the middle of.
        existing("prefix: \"unclosed\n  : : :\n");

        assertThat(messages().mergeMissing(jar())).isNegative();
        assertThat(onDisk()).contains("unclosed");
    }
}
