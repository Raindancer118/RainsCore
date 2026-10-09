package de.raindancer.core.data.loadout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoadoutTest {

    @Test
    @DisplayName("an empty loadout is a full set of empty slots, at full health and food")
    void empty() {
        Loadout empty = Loadout.empty("CREATIVE");
        assertThat(empty.inventory()).hasSize(Loadout.INVENTORY_SLOTS).allMatch(String::isEmpty);
        assertThat(empty.enderChest()).hasSize(Loadout.ENDER_SLOTS).allMatch(String::isEmpty);
        assertThat(empty.health()).isEqualTo(20.0);
        assertThat(empty.food()).isEqualTo(20);
        assertThat(empty.gameMode()).isEqualTo("CREATIVE");
        assertThat(empty.place()).isNull();
        assertThat(empty.hasItems()).isFalse();
    }

    @Test
    @DisplayName("short or null lists are padded to full size, nulls become empty slots")
    void padded() {
        java.util.ArrayList<String> one = new java.util.ArrayList<>();
        one.add(null);
        one.add("eA==");
        Loadout loadout = new Loadout(one, null, -3, 2f, 20, 99, -1f, null, false, false, null, null);

        assertThat(loadout.inventory()).hasSize(Loadout.INVENTORY_SLOTS);
        assertThat(loadout.inventory().get(0)).isEmpty();
        assertThat(loadout.inventory().get(1)).isEqualTo("eA==");
        assertThat(loadout.enderChest()).hasSize(Loadout.ENDER_SLOTS);
        assertThat(loadout.level()).isZero();
        assertThat(loadout.exp()).isEqualTo(1f);
        assertThat(loadout.food()).isEqualTo(20);
        assertThat(loadout.saturation()).isZero();
        assertThat(loadout.gameMode()).isEqualTo("SURVIVAL");
        assertThat(loadout.effects()).isEmpty();
        assertThat(loadout.hasItems()).isTrue();
    }

    @Test
    @DisplayName("a longer inventory than vanilla's is kept whole, never cut")
    void neverCut() {
        List<String> many = java.util.Collections.nCopies(Loadout.INVENTORY_SLOTS + 4, "eA==");
        assertThat(new Loadout(many, List.of(), 0, 0, 20, 20, 5, "SURVIVAL", false, false, List.of(), null)
                .inventory()).hasSize(Loadout.INVENTORY_SLOTS + 4);
    }
}
