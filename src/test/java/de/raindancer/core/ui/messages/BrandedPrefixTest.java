package de.raindancer.core.ui.messages;

import de.raindancer.core.ui.chat.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who a message says it is from.
 *
 * <h2>The bug this fixes</h2>
 * Every message on the test server went out as <code>[Core] …</code>, including the claims module's. The gradient
 * tag the plugin is branded with — the one already on every window title — was nowhere in chat.
 *
 * <p>The cause is that {@code prefixed()} never asked the {@link Brand}. It read the {@code prefix} message key,
 * of which there is exactly one on a server: RainsCore's own bundled {@code messages.yml} defines
 * <code>[Core]</code>, a module's wording arrives as a <em>floor</em>, and the bundled file sits above a floor.
 * So a module could ship a perfectly good gradient prefix and never once be able to use it.
 *
 * <h2>The shape of the fix</h2>
 * The host tells Core what it is called, once, and every message carries it. That is the same arrangement the
 * window titles already use, and it is right for the same reason: one server has one identity, whether its
 * features arrive as one plugin or as six modules. A player does not care which jar a message came from.
 *
 * <p>Left unset, the {@code prefix} key still answers — so nothing that does not opt in changes at all.
 */
class BrandedPrefixTest {

    @TempDir
    Path folder;

    private static final String BUNDLED = """
            prefix: "<gray>[<aqua>Core<gray>] "
            greeting: "Hello"
            """;

    private static InputStream bundled() {
        return new ByteArrayInputStream(BUNDLED.getBytes(StandardCharsets.UTF_8));
    }

    private Messages loaded() {
        Messages messages = new Messages(folder.resolve("messages.yml"));
        messages.load(bundled());
        return messages;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @AfterEach
    void tearDown() {
        // Static styling is process-wide; a test that leaves it set changes the next one.
        de.raindancer.core.ui.chat.Style.configure(key -> null);
    }

    @Test
    @DisplayName("without a brand, the prefix key still answers")
    void theOldBehaviourIsUntouched() {
        assertThat(plain(loaded().prefixed("greeting")))
                .as("anything that does not opt in has to behave exactly as it did")
                .contains("Core")
                .contains("Hello");
    }

    @Test
    @DisplayName("a host that names itself is what messages say they are from")
    void thebrandWins() {
        Messages messages = loaded();
        messages.prefixFrom(new Brand("RSC")::chatPrefix);

        String sent = plain(messages.prefixed("greeting"));
        assertThat(sent)
                .as("this is the whole point: the plugin's own name, not the library's")
                .contains("RSC")
                .contains("Hello");
        assertThat(sent)
                .as("and the library's name is gone rather than doubled")
                .doesNotContain("Core");
    }

    @Test
    @DisplayName("the brand is asked every time, so a rename reaches messages already written")
    void itIsAskedNotCopied() {
        Messages messages = loaded();
        String[] tag = {"First"};
        messages.prefixFrom(() -> tag[0] + " ");

        assertThat(plain(messages.prefixed("greeting"))).contains("First");
        tag[0] = "Second";
        assertThat(plain(messages.prefixed("greeting")))
                .as("read once at startup, a server renaming itself would need a restart to be believed")
                .contains("Second");
    }

    @Test
    @DisplayName("a brand that has switched itself off leaves the message unprefixed")
    void anEmptyPrefixIsRespected() {
        Messages messages = loaded();
        messages.prefixFrom(() -> "");

        assertThat(plain(messages.prefixed("greeting")))
                .as("Brand.chatPrefix returns nothing when an owner has turned the tag off, and that is an "
                        + "answer rather than a missing one")
                .isEqualTo("Hello");
    }

    @Test
    @DisplayName("a source that throws costs the prefix, not the message")
    void abrokenSourceIsSurvivable() {
        Messages messages = loaded();
        messages.prefixFrom(() -> {
            throw new IllegalStateException("no brand yet");
        });

        assertThat(plain(messages.prefixed("greeting")))
                .as("the message is the part the player needs; losing the tag with it would be the framework "
                        + "swallowing somebody's command output over a decoration")
                .contains("Hello");
    }
}
