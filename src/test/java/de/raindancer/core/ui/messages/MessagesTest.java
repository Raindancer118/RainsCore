package de.raindancer.core.ui.messages;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every message a plugin says, in a file somebody can edit.
 *
 * <h2>Why Core owns this</h2>
 * Because three plugins had written it and each got a different part wrong. It is boilerplate, but
 * it is boilerplate with one hard rule in it: a key the owner's file does not have must fall back to
 * the one the plugin shipped. Get that wrong and a translation that is three versions old produces
 * blank messages — or a {@code null} in the middle of a sentence — for every key added since, and
 * the server owner has no idea which of their edits caused it.
 *
 * <p>The other half is that a message with a typo in its markup should still be readable. A plugin
 * that throws while telling somebody they cannot do something has turned a refusal into a stack
 * trace.
 */
@DisplayName("messages")
class MessagesTest {

    @TempDir
    Path directory;

    private static InputStream bundled(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }

    private static final String DEFAULTS = """
            prefix: "<gold>[Claims] "
            claimed: "<green>You claimed <blocks> blocks."
            not-yours: "<red>That is not your claim."
            nested:
              deeper: "<gray>Down here."
            """;

    private Messages messages() {
        return new Messages(directory.resolve("messages.yml"));
    }

    // ------------------------------------------------------------------ the fallback

    @Nested
    @DisplayName("falling back to what the plugin shipped")
    class Fallback {

        @Test
        @DisplayName("with no file at all, everything comes from the defaults")
        void noFile() {
            Messages messages = messages();
            messages.load(bundled(DEFAULTS));

            assertThat(messages.raw("claimed")).isEqualTo("<green>You claimed <blocks> blocks.");
            assertThat(messages.raw("nested.deeper")).isEqualTo("<gray>Down here.");
        }

        @Test
        @DisplayName("a key the owner changed wins")
        void ownerWins() throws IOException {
            Files.writeString(directory.resolve("messages.yml"),
                    "claimed: \"<aqua>Got it: <blocks>.\"\n");
            Messages messages = messages();
            messages.load(bundled(DEFAULTS));

            assertThat(messages.raw("claimed")).isEqualTo("<aqua>Got it: <blocks>.");
        }

        @Test
        @DisplayName("a key the owner's file does not have still works")
        void missingKeysFallBack() throws IOException {
            // A messages.yml from three versions ago. This is the case the whole class exists for.
            Files.writeString(directory.resolve("messages.yml"),
                    "claimed: \"<aqua>Got it.\"\n");
            Messages messages = messages();
            messages.load(bundled(DEFAULTS));

            assertThat(messages.raw("not-yours"))
                    .as("an old translation must not blank out every message added since")
                    .isEqualTo("<red>That is not your claim.");
        }

        @Test
        @DisplayName("a key nobody has anywhere is visible rather than blank")
        void unknownKeys() {
            Messages messages = messages();
            messages.load(bundled(DEFAULTS));

            assertThat(messages.raw("no.such.key"))
                    .as("an empty string in the middle of a sentence is a bug nobody can find; the "
                            + "key itself at least says which one is missing")
                    .contains("no.such.key");
            assertThat(messages.problems()).isNotEmpty();
        }

        @Test
        @DisplayName("it says which keys the owner's file is missing, once, at startup")
        void reportsWhatIsMissing() throws IOException {
            Files.writeString(directory.resolve("messages.yml"), "claimed: \"<aqua>Got it.\"\n");
            Messages messages = messages();
            messages.load(bundled(DEFAULTS));

            assertThat(messages.missingFromFile())
                    .as("so an owner updating a translation knows what to add")
                    .contains("not-yours");
        }

        @Test
        @DisplayName("a file that will not parse leaves the defaults in place")
        void unreadableFile() throws IOException {
            Files.writeString(directory.resolve("messages.yml"), "this: is: not: yaml:\n\tnope\n");
            Messages messages = messages();
            messages.load(bundled(DEFAULTS));

            assertThat(messages.raw("claimed"))
                    .as("a broken translation should cost the translation, not every message")
                    .isEqualTo("<green>You claimed <blocks> blocks.");
            assertThat(messages.problems()).isNotEmpty();
        }
    }

    // ------------------------------------------------------------------ filling in

    /**
     * What a plugin may say about a message, and who wins when they disagree.
     *
     * <h2>Four layers, and why that many</h2>
     * <ol>
     *   <li><b>Bundled</b> — the {@code messages.yml} inside the jar. The floor: nothing is ever
     *       missing.</li>
     *   <li><b>{@link Messages#define}</b> — a default a plugin supplies in code, for a message it
     *       invented after its file shipped or that it builds at runtime.</li>
     *   <li><b>The owner's file</b> — beats both of those. Somebody who edits a line in
     *       {@code messages.yml} has to get that line, or the file is decoration.</li>
     *   <li><b>{@link Messages#force}</b> — beats even the file. For the few texts that must not be
     *       freely editable, and for switching a text at runtime.</li>
     * </ol>
     *
     * <p>Two calls rather than one with a flag, so the difference is visible where it is used rather
     * than in the documentation of the thing being used.
     */
    @Nested
    @DisplayName("what a plugin can override")
    class Overriding {

        @Test
        @DisplayName("a plugin default is used when neither the jar nor the file has the key")
        void defineFillsAGap() {
            Messages messages = messages();
            messages.load(bundled(DEFAULTS));

            messages.define("invented-later", "<gray>Something new.");

            assertThat(plain(messages.get("invented-later"))).isEqualTo("Something new.");
            assertThat(messages.has("invented-later")).isTrue();
        }

        @Test
        @DisplayName("a plugin default does not replace what the jar shipped")
        void defineDoesNotBeatTheJar() {
            Messages messages = messages();
            messages.load(bundled(DEFAULTS));

            messages.define("not-yours", "<red>Something else entirely.");

            assertThat(plain(messages.get("not-yours")))
                    .as("the bundled file is the plugin's own considered wording; a define is for a "
                            + "key that is not in it")
                    .isEqualTo("That is not your claim.");
        }

        @Test
        @DisplayName("the owner's file beats a plugin default")
        void theFileBeatsDefine() throws Exception {
            Files.writeString(directory.resolve("messages.yml"),
                    "invented-later: \"<green>My own wording.\"\n");
            Messages messages = messages();
            messages.load(bundled(DEFAULTS));

            messages.define("invented-later", "<gray>The plugin's suggestion.");

            assertThat(plain(messages.get("invented-later")))
                    .as("somebody who edits a line has to get that line, or the file is decoration")
                    .isEqualTo("My own wording.");
        }

        @Test
        @DisplayName("a forced message beats the owner's file")
        void forceBeatsTheFile() throws Exception {
            Files.writeString(directory.resolve("messages.yml"),
                    "claimed: \"<green>Their own wording.\"\n");
            Messages messages = messages();
            messages.load(bundled(DEFAULTS));

            messages.force("claimed", "<red>The plugin insists.");

            assertThat(plain(messages.get("claimed"))).isEqualTo("The plugin insists.");
        }

        @Test
        @DisplayName("a forced message can be taken back, and the file comes through again")
        void forceCanBeUndone() throws Exception {
            Files.writeString(directory.resolve("messages.yml"),
                    "claimed: \"<green>Their own wording.\"\n");
            Messages messages = messages();
            messages.load(bundled(DEFAULTS));
            messages.force("claimed", "<red>The plugin insists.");

            assertThat(messages.release("claimed")).isTrue();

            assertThat(plain(messages.get("claimed"))).isEqualTo("Their own wording.");
            assertThat(messages.release("claimed"))
                    .as("releasing something nobody forced is nothing, not an error")
                    .isFalse();
        }

        @Test
        @DisplayName("overrides survive the file being read again")
        void overridesSurviveAReload() {
            Messages messages = messages();
            messages.load(bundled(DEFAULTS));
            messages.define("invented-later", "<gray>Something new.");
            messages.force("claimed", "<red>The plugin insists.");

            // A reload is what happens when somebody edits the file and runs /reload. A plugin's
            // overrides are not in that file and must not be lost with it — the plugin is not going
            // to be asked to register them again.
            messages.load(bundled(DEFAULTS));

            assertThat(plain(messages.get("invented-later"))).isEqualTo("Something new.");
            assertThat(plain(messages.get("claimed"))).isEqualTo("The plugin insists.");
        }

        @Test
        @DisplayName("placeholders and escaping work the same in an override")
        void overridesGetTheSameTreatment() {
            Messages messages = messages();
            messages.load(bundled(DEFAULTS));
            messages.define("greeting", "<gray>Hello, <who>.");

            assertThat(plain(messages.get("greeting", "who", "<rainbow>Steve")))
                    .as("a name a player chose is text, in an override as much as in the file")
                    .isEqualTo("Hello, <rainbow>Steve.");
        }

        @Test
        @DisplayName("several lines work in an override too")
        void overridesCanBeSeveralLines() {
            Messages messages = messages();
            messages.load(bundled(DEFAULTS));

            messages.define("help", java.util.List.of("<gray>One.", "<gray>Two."));

            assertThat(messages.lines("help")).hasSize(2);
            assertThat(plain(messages.lines("help").get(1))).isEqualTo("Two.");
        }

        @Test
        @DisplayName("nothing is overridden for nothing")
        void nulls() {
            Messages messages = messages();
            messages.load(bundled(DEFAULTS));

            assertThat(messages.define(null, "x")).isFalse();
            assertThat(messages.define("  ", "x")).isFalse();
            assertThat(messages.define("key", null)).isFalse();
            assertThat(messages.force(null, "x")).isFalse();
            assertThat(messages.force("key", null)).isFalse();
        }

        @Test
        @DisplayName("a plugin default is reported as coming from the plugin, not the file")
        void definedKeysAreNotReportedAsMissing() {
            Messages messages = messages();
            messages.load(bundled(DEFAULTS));

            messages.define("invented-later", "<gray>Something new.");

            assertThat(messages.missingFromFile())
                    .as("a key a plugin supplied in code is not something the owner forgot to "
                            + "translate, and listing it as missing sends them looking for it")
                    .doesNotContain("invented-later");
            assertThat(messages.keys()).contains("invented-later");
        }
    }

    @Nested
    @DisplayName("filling in the blanks")
    class Placeholders {

        @Test
        @DisplayName("a value is put where its name is")
        void fillsThem() {
            Messages messages = messages();
            messages.load(bundled(DEFAULTS));

            assertThat(plain(messages.get("claimed", "blocks", 256)))
                    .isEqualTo("You claimed 256 blocks.");
        }

        @Test
        @DisplayName("what a player typed is never read as markup")
        void playerTextIsNotMarkup() {
            Messages messages = messages();
            messages.load(bundled("greeting: \"<gray>Hello, <name>.\""));

            assertThat(plain(messages.get("greeting", "name", "<red>Bob</red>")))
                    .as("a home called <red> is nine characters, not a colour change")
                    .contains("<red>Bob</red>");
        }

        @Test
        @DisplayName("a placeholder nobody supplied is left as it is rather than blanked")
        void unsuppliedPlaceholders() {
            Messages messages = messages();
            messages.load(bundled(DEFAULTS));

            assertThat(plain(messages.get("claimed")))
                    .as("a sentence with a hole in it says which value was forgotten; a sentence "
                            + "with a blank says nothing")
                    .contains("blocks");
        }

        @Test
        @DisplayName("an odd number of arguments is refused rather than half-applied")
        void oddArguments() {
            Messages messages = messages();
            messages.load(bundled(DEFAULTS));

            assertThat(plain(messages.get("claimed", "blocks")))
                    .as("a name with no value is a mistake at the call site, and guessing at it "
                            + "would hide it")
                    .isNotEmpty();
            assertThat(messages.problems()).isNotEmpty();
        }
    }

    // ------------------------------------------------------------------ the prefix

    @Nested
    @DisplayName("the prefix")
    class Prefix {

        @Test
        @DisplayName("it is put in front when asked for, and only then")
        void prefixed() {
            Messages messages = messages();
            messages.load(bundled(DEFAULTS));

            assertThat(plain(messages.prefixed("not-yours")))
                    .startsWith("[Claims] ")
                    .contains("That is not your claim.");
            assertThat(plain(messages.get("not-yours")))
                    .as("a line inside a list should not carry the prefix on every row")
                    .doesNotContain("[Claims]");
        }

        @Test
        @DisplayName("no prefix defined is simply no prefix")
        void noPrefix() {
            Messages messages = messages();
            messages.load(bundled("hello: \"<gray>Hello.\""));

            assertThat(plain(messages.prefixed("hello"))).isEqualTo("Hello.");
        }
    }

    // ------------------------------------------------------------------ bad markup

    @Test
    @DisplayName("markup with a typo in it is still readable")
    void brokenMarkup() {
        Messages messages = messages();
        messages.load(bundled("broken: \"<not_a_colour>Hello.\""));

        assertThat(plain(messages.get("broken")))
                .as("a plugin that throws while refusing something has turned a refusal into a "
                        + "stack trace")
                .contains("Hello.");
    }

    @Test
    @DisplayName("lists of lines are kept as lists")
    void lists() {
        Messages messages = messages();
        messages.load(bundled("""
                help:
                  - "<gray>Line one"
                  - "<gray>Line two"
                """));

        assertThat(messages.lines("help")).hasSize(2);
        assertThat(plain(messages.lines("help").getFirst())).isEqualTo("Line one");
    }

    @Test
    @DisplayName("a file is written out for the owner if there is not one")
    void writesTheFile() {
        Messages messages = messages();
        messages.load(bundled(DEFAULTS));

        assertThat(messages.writeIfMissing(bundled(DEFAULTS))).isTrue();
        assertThat(directory.resolve("messages.yml"))
                .as("an owner who cannot see the file cannot edit it, and will not know it exists")
                .exists();
        assertThat(messages.writeIfMissing(bundled(DEFAULTS)))
                .as("and it must never write over one they have edited")
                .isFalse();
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(component);
    }
}
