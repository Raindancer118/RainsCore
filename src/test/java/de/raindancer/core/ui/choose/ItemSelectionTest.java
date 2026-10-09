package de.raindancer.core.ui.choose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ItemSelectionTest {

    @Test
    @DisplayName("a selection holds whole drawers and named items, by name or pattern, and never what it excepts")
    void covers() {
        ItemSelection building = new ItemSelection(List.of(Category.BUILDING_BLOCKS),
                List.of("iron_ingot", "*_glass"), List.of("diamond_block", "*_ore"));
        assertThat(building.covers("STONE_BRICKS")).isTrue();
        assertThat(building.covers("iron_ingot")).isTrue();
        assertThat(building.covers("RED_STAINED_GLASS")).isTrue();
        assertThat(building.covers("DIAMOND_BLOCK")).isFalse();
        assertThat(building.covers("DEEPSLATE_IRON_ORE")).isFalse();
        assertThat(building.covers("DIAMOND")).isFalse();
        assertThat(building.covers(null)).isFalse();
        assertThat(ItemSelection.NOTHING.covers("STONE")).isFalse();
        assertThat(ItemSelection.NOTHING.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("patterns: a star anywhere, case does not matter")
    void patterns() {
        assertThat(ItemSelection.matches("*_log", "OAK_LOG")).isTrue();
        assertThat(ItemSelection.matches("cooked_*", "COOKED_BEEF")).isTrue();
        assertThat(ItemSelection.matches("*copper*", "WAXED_COPPER_BLOCK")).isTrue();
        assertThat(ItemSelection.matches("bread", "BREAD")).isTrue();
        assertThat(ItemSelection.matches("bread", "BREADS")).isFalse();
        assertThat(ItemSelection.matches("*_log", "OAK_LOGS")).isFalse();
    }

    @Test
    @DisplayName("read from settings lines: a category by its name, anything else an item; '!' excepts")
    void parse() {
        ItemSelection read = ItemSelection.parse(List.of("building_blocks", "iron_ingot", "*_wool", "!diamond_block",
                " ", "Decorations"));
        assertThat(read.categories()).containsExactly(Category.BUILDING_BLOCKS, Category.DECORATIONS);
        assertThat(read.items()).containsExactly("IRON_INGOT", "*_WOOL");
        assertThat(read.except()).containsExactly("DIAMOND_BLOCK");
    }

    @Test
    @DisplayName("in words, for a menu: drawers, then items, a few and how many more")
    void says() {
        assertThat(new ItemSelection(List.of(Category.FOOD), List.of("SMOKER"), List.of()).says())
                .isEqualTo("Food, Smoker");
        assertThat(new ItemSelection(List.of(), List.of("*_LOG", "A", "B", "C", "D", "E"), List.of()).says())
                .isEqualTo("any log, A, B, C and 2 more");
    }
}
