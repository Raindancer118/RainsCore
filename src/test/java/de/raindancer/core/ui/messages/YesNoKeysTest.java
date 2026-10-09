package de.raindancer.core.ui.messages;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A key called {@code off:} is the word "off", not the boolean YAML 1.1 reads it as. Read as a boolean it was
 * flattened to {@code ….false}, so every "switched off on this server" line in a module showed its raw key.
 */
class YesNoKeysTest {

    @TempDir
    Path folder;

    private static final String WORDING = """
            shop:
              off: "The shop is switched off."
              on: "Open."
              yes: "Yes."
              no: 'No.'
              "quoted": "Fine already."
              closed: off
            """;

    private Messages fresh() {
        Messages messages = new Messages(folder.resolve("messages.yml"));
        messages.load(null);
        return messages;
    }

    @Test
    @DisplayName("a module's on/off/yes/no keys keep their names; values are left alone")
    void moduleWording() {
        Messages messages = fresh();
        messages.defineFrom(new ByteArrayInputStream(WORDING.getBytes(StandardCharsets.UTF_8)));
        assertThat(messages.raw("shop.off")).isEqualTo("The shop is switched off.");
        assertThat(messages.raw("shop.on")).isEqualTo("Open.");
        assertThat(messages.raw("shop.yes")).isEqualTo("Yes.");
        assertThat(messages.raw("shop.no")).isEqualTo("No.");
        assertThat(messages.raw("shop.quoted")).isEqualTo("Fine already.");
        assertThat(messages.has("shop.false")).isFalse();
    }

    @Test
    @DisplayName("an owner's own file with an off: key is read the same way")
    void ownersFile() throws Exception {
        Files.writeString(folder.resolve("messages.yml"), WORDING);
        Messages messages = new Messages(folder.resolve("messages.yml"));
        messages.load(null);
        assertThat(messages.raw("shop.off")).isEqualTo("The shop is switched off.");
    }

    @Test
    @DisplayName("only bare keys are quoted — a value, a longer word or an indented list item is not touched")
    void quotingIsNarrow() {
        assertThat(Messages.quoteYesNoKeys("  off: \"x\"\n  offer: off\n  - off\n  no:\n"))
                .isEqualTo("  \"off\": \"x\"\n  offer: off\n  - off\n  \"no\":\n");
    }
}
