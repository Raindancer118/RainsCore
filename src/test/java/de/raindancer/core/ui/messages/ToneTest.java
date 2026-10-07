package de.raindancer.core.ui.messages;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
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
 * Serious mode swaps the jokes for plain sentences — but only where the line is still ours. A line the
 * owner wrote themselves is what they want said, in either tone.
 */
class ToneTest {

    @TempDir
    Path folder;

    private static InputStream yaml(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String plain(Messages messages, String key) {
        return PlainTextComponentSerializer.plainText().serialize(messages.get(key));
    }

    private static final String SHIPPED = """
            prefix: ""
            home:
              gone: "That home has left the building."
              set: "Home sweet home — saved."
              full: "You have too many homes."
            """;

    private static final String SERIOUS = """
            home:
              gone: "That home no longer exists."
              set: "Home saved."
            """;

    private Messages loaded(String ownerFile) throws IOException {
        Path file = folder.resolve("messages.yml");
        if (ownerFile != null) {
            Files.writeString(file, ownerFile);
        }
        Messages messages = new Messages(file);
        messages.load(yaml(SHIPPED));
        messages.seriousFrom(yaml(SERIOUS));
        return messages;
    }

    @Test
    @DisplayName("playful is the default, and serious lines change nothing until it is switched")
    void playfulByDefault() throws IOException {
        Messages messages = loaded(null);
        assertThat(messages.tone()).isEqualTo(Messages.Tone.PLAYFUL);
        assertThat(plain(messages, "home.gone")).isEqualTo("That home has left the building.");
    }

    @Test
    @DisplayName("serious mode says the serious line, and switching back restores the joke")
    void switches() throws IOException {
        Messages messages = loaded(null);
        messages.tone(Messages.Tone.SERIOUS);
        assertThat(plain(messages, "home.gone")).isEqualTo("That home no longer exists.");
        messages.tone(Messages.Tone.PLAYFUL);
        assertThat(plain(messages, "home.gone")).isEqualTo("That home has left the building.");
    }

    @Test
    @DisplayName("a line still exactly as we shipped it in the owner's file is ours, so it turns serious")
    void untouchedFileLinesTurnSerious() throws IOException {
        Messages messages = loaded(SHIPPED);
        messages.tone(Messages.Tone.SERIOUS);
        assertThat(plain(messages, "home.gone")).isEqualTo("That home no longer exists.");
        assertThat(plain(messages, "home.set")).isEqualTo("Home saved.");
    }

    @Test
    @DisplayName("a line the owner reworded stays theirs in both tones")
    void ownersWordsWin() throws IOException {
        Messages messages = loaded("""
                prefix: ""
                home:
                  gone: "Dein Zuhause ist weg."
                """);
        messages.tone(Messages.Tone.SERIOUS);
        assertThat(plain(messages, "home.gone")).isEqualTo("Dein Zuhause ist weg.");
        assertThat(plain(messages, "home.set")).isEqualTo("Home saved.");
    }

    @Test
    @DisplayName("a line with no serious version keeps the only wording there is")
    void noSeriousLineFallsBack() throws IOException {
        Messages messages = loaded(null);
        messages.tone(Messages.Tone.SERIOUS);
        assertThat(plain(messages, "home.full")).isEqualTo("You have too many homes.");
    }

    @Test
    @DisplayName("a forced line beats the serious one, as it beats everything")
    void forcedWins() throws IOException {
        Messages messages = loaded(null);
        messages.tone(Messages.Tone.SERIOUS);
        messages.force("home.gone", "Forced.");
        assertThat(plain(messages, "home.gone")).isEqualTo("Forced.");
    }

    @Test
    @DisplayName("a module's serious wording works like its playful wording: a floor, first one in keeps it")
    void modules() throws IOException {
        Messages messages = loaded(null);
        messages.defineFrom(yaml("""
                warp:
                  gone: "That warp has wandered off."
                  owned: "Not your warp. Hands off the signposts."
                """));
        messages.seriousFrom(yaml("""
                warp:
                  gone: "That warp no longer exists."
                  owned: "You may not change that warp."
                """));
        messages.seriousFrom(yaml("""
                warp:
                  gone: "Another module trying to take the key."
                """));
        assertThat(plain(messages, "warp.gone")).isEqualTo("That warp has wandered off.");
        messages.tone(Messages.Tone.SERIOUS);
        assertThat(plain(messages, "warp.gone")).isEqualTo("That warp no longer exists.");
        assertThat(plain(messages, "warp.owned")).isEqualTo("You may not change that warp.");
    }

    @Test
    @DisplayName("an owner's line for a module key beats the module's serious line")
    void ownerBeatsModuleSerious() throws IOException {
        Messages messages = loaded("""
                prefix: ""
                warp:
                  gone: "Warp weg."
                """);
        messages.defineFrom(yaml("""
                warp:
                  gone: "That warp has wandered off."
                """));
        messages.seriousFrom(yaml("""
                warp:
                  gone: "That warp no longer exists."
                """));
        messages.tone(Messages.Tone.SERIOUS);
        assertThat(plain(messages, "warp.gone")).isEqualTo("Warp weg.");
    }

    @Test
    @DisplayName("lines and placeholders work the same in serious mode")
    void placeholdersAndLists() throws IOException {
        Messages messages = loaded(null);
        messages.defineFrom(yaml("""
                help:
                  lines:
                    - "Joke one about <name>."
                    - "Joke two."
                  who: "Nobody called <name>, believe us, we looked."
                """));
        messages.seriousFrom(yaml("""
                help:
                  lines:
                    - "Line one about <name>."
                  who: "Nobody is called <name>."
                """));
        messages.tone(Messages.Tone.SERIOUS);
        assertThat(PlainTextComponentSerializer.plainText().serialize(messages.get("help.who", "name", "Steve")))
                .isEqualTo("Nobody is called Steve.");
        assertThat(messages.lines("help.lines", "name", "Alex"))
                .extracting(line -> PlainTextComponentSerializer.plainText().serialize(line))
                .containsExactly("Line one about Alex.");
    }

    @Test
    @DisplayName("a missing or broken serious file costs the serious lines, nothing else")
    void brokenFile() throws IOException {
        Messages messages = loaded(null);
        assertThat(messages.seriousFrom(null)).isZero();
        assertThat(messages.seriousFrom(yaml("home: [unclosed"))).isZero();
        messages.tone(Messages.Tone.SERIOUS);
        assertThat(plain(messages, "home.gone")).isEqualTo("That home no longer exists.");
    }

    @Test
    @DisplayName("without a running Core, a built-in line is said as written")
    void builtInWithoutCore() {
        assertThat(PlainTextComponentSerializer.plainText().serialize(
                Messages.spoken("nothing.here", "<red>Not a sound. Silence it is.")))
                .isEqualTo("Not a sound. Silence it is.");
    }
}
