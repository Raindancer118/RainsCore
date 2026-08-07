package de.raindancer.core.social.team;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TeamColourTest {

    @Test
    @DisplayName("every colour has a banner, and it is the banner of its own dye")
    void everyColourHasItsOwnBanner() {
        for (TeamColour colour : TeamColour.values()) {
            Material expected = Material.valueOf(colour.dyeColour().name() + "_BANNER");

            assertThat(colour.bannerMaterial())
                    .as("%s's banner should match its own dye colour, not somebody else's", colour)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("orange is orange, not white")
    void orangeIsOrange() {
        assertThat(TeamColour.ORANGE.bannerMaterial()).isEqualTo(Material.ORANGE_BANNER);
    }
}
