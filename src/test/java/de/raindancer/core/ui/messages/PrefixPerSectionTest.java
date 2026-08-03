package de.raindancer.core.ui.messages;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a message is signed by the plugin it came from.
 *
 * <h2>The defect this exists because of</h2>
 * There is one {@link Messages} on the server, shared by everything, and one prefix on it. Every module
 * plugin sets that prefix to its own brand as it starts — so the <em>last</em> one to enable wins, and
 * every message on the server is then signed with its name.
 *
 * <p>On a live server that read:
 *
 * <pre>
 *   Moderation » You were shown out of Berry_The_Jerry's poopy_claim.
 * </pre>
 *
 * <p>which is the claims plugin talking, over the moderation plugin's signature. Nothing failed and
 * nothing was logged; it is only visible by reading chat and knowing which plugin owns the sentence.
 *
 * <p>So the prefix follows the <em>key</em>: a module registers the sections it owns when it supplies
 * its wording, and anything it did not claim falls back to the host's own brand.
 */
class PrefixPerSectionTest {

    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    private static Messages messages(@TempDir Path folder) {
        return new Messages(folder.resolve("messages.yml"));
    }

    private static ByteArrayInputStream yaml(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a section's own prefix is used for its keys")
    void ownPrefix(@TempDir Path folder) {
        Messages messages = messages(folder);
        messages.defineFrom(yaml("claim:\n  none-here: \"You are not in a claim.\"\n"),
                () -> "[Claims] ");

        assertThat(PLAIN.serialize(messages.prefixed("claim.none-here")))
                .isEqualTo("[Claims] You are not in a claim.");
    }

    @Test
    @DisplayName("two plugins keep their own signatures")
    void twoPlugins(@TempDir Path folder) {
        // The actual bug: whichever enabled last signed everything.
        Messages messages = messages(folder);
        messages.defineFrom(yaml("claim:\n  gone: \"That claim is gone.\"\n"), () -> "[Claims] ");
        messages.defineFrom(yaml("moderation:\n  no-permission: \"You may not.\"\n"),
                () -> "[Moderation] ");

        assertThat(PLAIN.serialize(messages.prefixed("claim.gone")))
                .isEqualTo("[Claims] That claim is gone.");
        assertThat(PLAIN.serialize(messages.prefixed("moderation.no-permission")))
                .isEqualTo("[Moderation] You may not.");
    }

    @Test
    @DisplayName("a key nobody claimed falls back to the host's prefix")
    void fallback(@TempDir Path folder) {
        Messages messages = messages(folder);
        messages.prefixFrom(() -> "[Host] ");
        messages.defineFrom(yaml("claim:\n  gone: \"Gone.\"\n"), () -> "[Claims] ");
        messages.define("something.else", "Hello.");

        assertThat(PLAIN.serialize(messages.prefixed("something.else")))
                .isEqualTo("[Host] Hello.");
    }

    @Test
    @DisplayName("wording supplied without a prefix source still uses the host's")
    void noSourceGiven(@TempDir Path folder) {
        // defineFrom(stream) has to keep working exactly as it did, or every existing caller changes
        // behaviour on an upgrade.
        Messages messages = messages(folder);
        messages.prefixFrom(() -> "[Host] ");
        messages.defineFrom(yaml("claim:\n  gone: \"Gone.\"\n"));

        assertThat(PLAIN.serialize(messages.prefixed("claim.gone"))).isEqualTo("[Host] Gone.");
    }

    @Test
    @DisplayName("a key with no dot at all is still prefixed")
    void topLevelKey(@TempDir Path folder) {
        Messages messages = messages(folder);
        messages.prefixFrom(() -> "[Host] ");
        messages.define("greeting", "Hello.");

        assertThat(PLAIN.serialize(messages.prefixed("greeting"))).isEqualTo("[Host] Hello.");
    }

    @Test
    @DisplayName("a prefix source that throws costs the prefix and not the message")
    void aThrowingSource(@TempDir Path folder) {
        // Losing somebody's command output over a decoration would be the framework failing at the one
        // thing it is for.
        Messages messages = messages(folder);
        messages.defineFrom(yaml("claim:\n  gone: \"Gone.\"\n"), () -> {
            throw new IllegalStateException("no brand yet");
        });

        assertThat(PLAIN.serialize(messages.prefixed("claim.gone"))).isEqualTo("Gone.");
    }

    @Test
    @DisplayName("the owner's own file still wins the wording, whoever signs it")
    void theOwnerStillWins(@TempDir Path folder) {
        // The prefix is about attribution; it must not change which layer supplies the words.
        Messages messages = messages(folder);
        messages.defineFrom(yaml("claim:\n  gone: \"Gone.\"\n"), () -> "[Claims] ");
        messages.force("claim.gone", "Insisted.");

        assertThat(PLAIN.serialize(messages.prefixed("claim.gone"))).isEqualTo("[Claims] Insisted.");
    }
}
