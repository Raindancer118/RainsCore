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
 * One plugin saying one of Core's lines its own way, without changing it for anybody else.
 *
 * <p>Keys are global — there is one {@link Messages} per server — so a module's wording can never
 * replace a Core key (it arrives as a floor), and {@link Messages#force} would replace it for everybody.
 * An override maps a key to another key <em>for one plugin</em>: the replacement is an ordinary key in
 * that plugin's own section, so the owner can still edit it and it is signed with that plugin's prefix.
 */
@DisplayName("per-plugin message overrides")
class PerPluginOverrideTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static ByteArrayInputStream yaml(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Messages core(Path folder) {
        Messages messages = new Messages(folder.resolve("messages.yml"));
        messages.load(yaml("combat:\n  fists-only: \"Only fists here.\"\n"));
        messages.prefixFrom(() -> "[Core] ");
        messages.defineFrom(yaml("manhunt:\n  teammate-hit: \"Please don't kill your teammates!\"\n"),
                () -> "[Manhunt] ");
        return messages;
    }

    @Test
    @DisplayName("the overriding plugin gets its own line, signed with its own prefix")
    void overridden(@TempDir Path folder) {
        Messages messages = core(folder);
        messages.overrideFor("manhunt", "combat.fists-only", "manhunt.teammate-hit");

        assertThat(messages.keyFor("manhunt", "combat.fists-only")).isEqualTo("manhunt.teammate-hit");
        assertThat(PLAIN.serialize(messages.prefixedFor("manhunt", "combat.fists-only")))
                .isEqualTo("[Manhunt] Please don't kill your teammates!");
    }

    @Test
    @DisplayName("everybody else — another plugin, or no plugin at all — still gets Core's line")
    void othersUntouched(@TempDir Path folder) {
        Messages messages = core(folder);
        messages.overrideFor("manhunt", "combat.fists-only", "manhunt.teammate-hit");

        assertThat(PLAIN.serialize(messages.prefixedFor("arena", "combat.fists-only")))
                .isEqualTo("[Core] Only fists here.");
        assertThat(PLAIN.serialize(messages.prefixedFor(null, "combat.fists-only")))
                .isEqualTo("[Core] Only fists here.");
        assertThat(PLAIN.serialize(messages.prefixed("combat.fists-only")))
                .isEqualTo("[Core] Only fists here.");
    }

    @Test
    @DisplayName("the owner can still reword the replacement — it is an ordinary key")
    void ownerEditsTheReplacement(@TempDir Path folder) throws Exception {
        java.nio.file.Files.writeString(folder.resolve("messages.yml"),
                "manhunt:\n  teammate-hit: \"Hands off your own side.\"\n");
        Messages messages = core(folder);
        messages.overrideFor("manhunt", "combat.fists-only", "manhunt.teammate-hit");

        assertThat(PLAIN.serialize(messages.prefixedFor("manhunt", "combat.fists-only")))
                .isEqualTo("[Manhunt] Hands off your own side.");
    }

    @Test
    @DisplayName("a replacement nobody defined falls back to the original, never to a bare key")
    void undefinedReplacement(@TempDir Path folder) {
        Messages messages = core(folder);
        messages.overrideFor("manhunt", "combat.fists-only", "manhunt.no-such-line");

        assertThat(messages.keyFor("manhunt", "combat.fists-only")).isEqualTo("combat.fists-only");
    }

    @Test
    @DisplayName("a plugin unloading takes its overrides with it")
    void forgotten(@TempDir Path folder) {
        Messages messages = core(folder);
        messages.overrideFor("manhunt", "combat.fists-only", "manhunt.teammate-hit");

        assertThat(messages.forgetOverridesFor("manhunt")).isEqualTo(1);

        assertThat(messages.keyFor("manhunt", "combat.fists-only")).isEqualTo("combat.fists-only");
    }

    @Test
    @DisplayName("blank or null arguments are refused, not stored")
    void refusesNonsense(@TempDir Path folder) {
        Messages messages = core(folder);

        assertThat(messages.overrideFor(null, "combat.fists-only", "manhunt.teammate-hit")).isFalse();
        assertThat(messages.overrideFor("manhunt", " ", "manhunt.teammate-hit")).isFalse();
        assertThat(messages.overrideFor("manhunt", "combat.fists-only", null)).isFalse();
        assertThat(messages.keyFor(null, null)).isNull();
    }
}
