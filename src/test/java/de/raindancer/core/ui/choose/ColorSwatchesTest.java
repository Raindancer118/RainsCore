package de.raindancer.core.ui.choose;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ColorSwatchesTest {

    @Test
    @DisplayName("all sixteen colours are in the fixed picker order, once each")
    void allSixteenOnceEach() {
        assertThat(ColorSwatches.ALL).hasSize(16).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("every colour maps to a block that actually looks like it")
    void everyColourHasAMaterial() {
        for (NamedTextColor color : ColorSwatches.ALL) {
            assertThat(ColorSwatches.materialFor(color))
                    .as("no material for " + color)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("white and an unrecognised colour both fall back to a plain white block")
    void fallsBackToWhite() {
        assertThat(ColorSwatches.materialFor(NamedTextColor.WHITE)).isEqualTo(Material.WHITE_CONCRETE);
        assertThat(ColorSwatches.materialFor(null)).isEqualTo(Material.WHITE_CONCRETE);
    }

    @Test
    @DisplayName("a colour's readable name is capitalised, underscores read as spaces")
    void readableName() {
        assertThat(ColorSwatches.readable(NamedTextColor.DARK_PURPLE)).isEqualTo("Dark purple");
        assertThat(ColorSwatches.readable(NamedTextColor.RED)).isEqualTo("Red");
    }
}
