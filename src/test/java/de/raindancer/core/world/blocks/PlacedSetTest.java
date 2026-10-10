package de.raindancer.core.world.blocks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlacedSetTest {

    @Test
    @DisplayName("a position packs to one number per chunk, from the bottom of the world to the top")
    void packs() {
        assertThat(PlacedSet.pack(0, -64, 0)).isNotEqualTo(PlacedSet.pack(0, -63, 0));
        assertThat(PlacedSet.pack(17, 5, -1)).as("only the place within the chunk counts")
                .isEqualTo(PlacedSet.pack(1, 5, 15));
        assertThat(PlacedSet.pack(15, 2031, 15)).isGreaterThan(PlacedSet.pack(0, -2032, 0));
    }

    @Test
    @DisplayName("added once, found, and gone when removed; adding twice keeps one")
    void addsAndRemoves() {
        int[] set = PlacedSet.EMPTY;
        set = PlacedSet.add(set, PlacedSet.pack(3, 70, 4));
        set = PlacedSet.add(set, PlacedSet.pack(1, 12, 9));
        set = PlacedSet.add(set, PlacedSet.pack(3, 70, 4));
        assertThat(set).hasSize(2);
        assertThat(PlacedSet.contains(set, PlacedSet.pack(3, 70, 4))).isTrue();
        assertThat(PlacedSet.contains(set, PlacedSet.pack(3, 71, 4))).isFalse();
        set = PlacedSet.remove(set, PlacedSet.pack(3, 70, 4));
        assertThat(PlacedSet.contains(set, PlacedSet.pack(3, 70, 4))).isFalse();
        assertThat(PlacedSet.remove(set, PlacedSet.pack(9, 9, 9))).isSameAs(set);
    }

    @Test
    @DisplayName("whatever order things are placed in, the set stays sorted so a look-up is a binary search")
    void staysSorted() {
        int[] set = PlacedSet.EMPTY;
        for (int y : new int[]{90, -10, 40, 300, 0}) {
            set = PlacedSet.add(set, PlacedSet.pack(0, y, 0));
        }
        assertThat(set).isSorted();
    }
}
