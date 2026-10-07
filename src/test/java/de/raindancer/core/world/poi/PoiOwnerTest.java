package de.raindancer.core.world.poi;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PoiOwnerTest {

    @Test
    @DisplayName("handing a place to somebody else keeps everything else about it")
    void withOwnerKeepsTheRest() {
        UUID before = UUID.randomUUID();
        UUID after = UUID.randomUUID();
        Poi place = Poi.builder("mine", "world", 1, 2, 3).kind("warp").owner(before)
                .icon(Material.DIAMOND_PICKAXE).label("The Mine").shared(true).tag("category", "work")
                .facing(90, 10).build();

        Poi handed = place.withOwner(after);

        assertThat(handed.owner()).isEqualTo(after);
        assertThat(handed).usingRecursiveComparison().ignoringFields("owner").isEqualTo(place);
    }
}
