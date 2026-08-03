package de.raindancer.core.messages;

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
