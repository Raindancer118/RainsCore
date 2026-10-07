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
 * A server's messages.yml is written once and never overwritten, so new wording in a release would
 * never reach a server that has run an older one. A line still exactly as an older version shipped it
 * is not the owner's wording — it is ours, and takes this version's. A line they changed stays theirs.
 */
class RetiredWordingTest {

    @TempDir
    Path folder;

    private static InputStream yaml(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String plain(Messages messages, String key) {
        return PlainTextComponentSerializer.plainText().serialize(messages.get(key));
    }

    private Messages loaded(String ownerFile) throws IOException {
        Path file = folder.resolve("messages.yml");
        Files.writeString(file, ownerFile);
        Messages messages = new Messages(file);
        messages.retired(yaml("""
                prefix: ""
                home:
                  set: "Home set."
                  gone: "That home is gone."
                """));
        messages.load(yaml("""
                prefix: ""
                home:
                  set: "Home sweet home — saved."
                  gone: "That home has left the building."
                """));
        return messages;
    }

    @Test
    @DisplayName("a line still as an older version shipped it takes this version's wording")
    void untouchedLinesUpgrade() throws IOException {
        Messages messages = loaded("""
                prefix: ""
                home:
                  set: "Home set."
                  gone: "That home is gone."
                """);
        assertThat(plain(messages, "home.set")).isEqualTo("Home sweet home — saved.");
        assertThat(plain(messages, "home.gone")).isEqualTo("That home has left the building.");
    }

    @Test
    @DisplayName("a line the owner reworded stays exactly theirs")
    void ownersWordsStay() throws IOException {
        Messages messages = loaded("""
                prefix: ""
                home:
                  set: "Dein Zuhause ist gespeichert."
                  gone: "That home is gone."
                """);
        assertThat(plain(messages, "home.set")).isEqualTo("Dein Zuhause ist gespeichert.");
        assertThat(plain(messages, "home.gone")).isEqualTo("That home has left the building.");
    }

    @Test
    @DisplayName("an owner who emptied a line still gets silence")
    void silenceStays() throws IOException {
        Messages messages = loaded("""
                prefix: ""
                home:
                  set: ""
                """);
        assertThat(plain(messages, "home.set")).isEmpty();
    }
}
