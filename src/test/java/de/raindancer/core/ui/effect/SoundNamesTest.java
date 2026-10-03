package de.raindancer.core.ui.effect;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Bukkit sound name is not its key with dots for underscores: ENTITY_LIGHTNING_BOLT_THUNDER is
 * entity.lightning_bolt.thunder. Guessed, the client is sent a key it has never heard of and plays
 * nothing, without a word.
 */
class SoundNamesTest {

    private final UnaryOperator<String> before = SoundSequence.vanillaKeys;

    @AfterEach
    void restore() {
        SoundSequence.vanillaKeys = before;
    }

    @Test
    @DisplayName("a constant the server knows is turned into the key the server gives it")
    void askedOfTheServer() {
        SoundSequence.vanillaKeys = constant -> constant.equals("ENTITY_LIGHTNING_BOLT_THUNDER")
                ? "entity.lightning_bolt.thunder" : null;

        assertThat(SoundSequence.normalise("ENTITY_LIGHTNING_BOLT_THUNDER"))
                .isEqualTo("entity.lightning_bolt.thunder");
        assertThat(SoundSequence.normalise("entity_lightning_bolt_thunder"))
                .isEqualTo("entity.lightning_bolt.thunder");
    }

    @Test
    @DisplayName("a name the server does not know is still guessed, and a dotted key is left alone")
    void fallsBack() {
        SoundSequence.vanillaKeys = constant -> null;

        assertThat(SoundSequence.normalise("ENTITY_GENERIC_EXPLODE")).isEqualTo("entity.generic.explode");
        assertThat(SoundSequence.normalise("custom.halt")).isEqualTo("custom.halt");
    }
}
