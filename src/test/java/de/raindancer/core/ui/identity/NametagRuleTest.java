package de.raindancer.core.ui.identity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** When a styled nametag floats over somebody, and what it says. */
class NametagRuleTest {

    private final NametagRule rule = new NametagRule();

    @Test
    @DisplayName("an ordinary living player in plain sight has one")
    void shown() {
        assertThat(rule.shows(false, false, false, false, false)).isTrue();
    }

    @Test
    @DisplayName("nobody hidden gives themselves away by a floating name")
    void hidden() {
        assertThat(rule.shows(true, false, false, false, false)).as("dead").isFalse();
        assertThat(rule.shows(false, true, false, false, false)).as("spectating").isFalse();
        assertThat(rule.shows(false, false, true, false, false)).as("invisible").isFalse();
        assertThat(rule.shows(false, false, false, true, false)).as("vanished").isFalse();
    }

    @Test
    @DisplayName("somebody in another plugin's team keeps the vanilla name, so gets no second one")
    void foreignTeams() {
        assertThat(rule.shows(false, false, false, false, true)).isFalse();
    }

    @Test
    @DisplayName("the subtitle, when there is one, goes under the name")
    void text() {
        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
        assertThat(plain.serialize(rule.text(Component.text("Bo"), Optional.empty()))).isEqualTo("Bo");
        assertThat(plain.serialize(rule.text(Component.text("Bo"), Optional.of(Component.text("12 kills")))))
                .isEqualTo("Bo\n12 kills");
    }
}
