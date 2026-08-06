package de.raindancer.core.content.items;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Changing what a custom item is crafted from.
 *
 * <h2>Why this needed anything at all</h2>
 * {@link CustomItem} could hold a recipe from the moment it was built and could never be given a different
 * one: there was a {@code with…} for the material, the name, the lore, the model data, the glow and a tag, and
 * none for the rows. So a server owner could see an item's recipe and not change it, and the plugin that used
 * to carry a page for exactly that had been ported onto this registry.
 *
 * <h2>What the rows mean, and the mistake the shape makes easy</h2>
 * Up to three rows of material names, space-separated, and the <em>shape</em> matters: a three-by-three grid
 * with only the middle filled is not "one item in the middle" as far as Bukkit is concerned — it is a recipe
 * the server refuses, with an exception naming none of this. {@link ItemRecipes} crops the grid to what is
 * actually in it for that reason, and this is the arithmetic that lets a page show somebody the cropped shape
 * before they save it.
 */
class RecipeEditingTest {

    private static CustomItem anItem() {
        return CustomItem.builder("test", "thing").material(Material.STICK).build();
    }

    @Nested
    @DisplayName("giving an item a recipe")
    class Setting {

        @Test
        @DisplayName("an item can be given rows it did not have")
        void aRecipeCanBeAdded() {
            CustomItem before = anItem();
            assertThat(before.isCraftable()).isFalse();

            CustomItem after = before.withRecipe(List.of("STICK STICK", "AIR IRON_INGOT"));

            assertThat(after.isCraftable()).isTrue();
            assertThat(after.recipe()).containsExactly("STICK STICK", "AIR IRON_INGOT");
        }

        @Test
        @DisplayName("everything else about the item is kept")
        void nothingElseChanges() {
            CustomItem before = CustomItem.builder("test", "thing")
                    .material(Material.STICK)
                    .name("<gold>Thing")
                    .lore(List.of("<gray>A thing."))
                    .glowing(true)
                    .ability("wave")
                    .build();

            CustomItem after = before.withRecipe(List.of("STICK"));

            // A with… that quietly dropped the ability would unbind the right-click of every item edited
            // through a screen, and nothing would say so.
            assertThat(after.material()).isEqualTo(before.material());
            assertThat(after.displayName()).isEqualTo(before.displayName());
            assertThat(after.lore()).isEqualTo(before.lore());
            assertThat(after.isGlowing()).isEqualTo(before.isGlowing());
            assertThat(after.abilityKey()).isEqualTo(before.abilityKey());
            assertThat(after.key()).isEqualTo(before.key());
        }

        @Test
        @DisplayName("a recipe can be taken away again")
        void aRecipeCanBeRemoved() {
            CustomItem with = anItem().withRecipe(List.of("STICK STICK"));

            assertThat(with.withRecipe(List.of()).isCraftable())
                    .as("an item whose recipe cannot be removed is one somebody has to delete and rebuild")
                    .isFalse();
            assertThat(with.withRecipe(null).isCraftable())
                    .as("null is how a screen says 'no recipe' without building an empty list")
                    .isFalse();
        }

        @Test
        @DisplayName("the original is untouched, because these are values")
        void theOriginalIsUnchanged() {
            CustomItem before = anItem();

            before.withRecipe(List.of("STICK"));

            assertThat(before.isCraftable())
                    .as("a with… that mutated would change an item another screen is already showing")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("what a page has to show before saving")
    class Cropping {

        @Test
        @DisplayName("a grid is cropped to what is actually in it")
        void emptyEdgesAreDropped() {
            // The mistake the 3x3 grid makes easy: only the middle filled. Bukkit refuses a 3x3 shape whose
            // outer ring is empty, so a page that saved the grid verbatim would produce a recipe the server
            // rejects — with an exception naming none of this.
            List<String> cropped = ItemRecipes.crop(List.of(
                    "AIR AIR AIR",
                    "AIR DIAMOND AIR",
                    "AIR AIR AIR"));

            assertThat(cropped).containsExactly("DIAMOND");
        }

        @Test
        @DisplayName("a shape that is genuinely three wide keeps its width")
        void realShapesSurvive() {
            List<String> cropped = ItemRecipes.crop(List.of(
                    "IRON_INGOT IRON_INGOT IRON_INGOT",
                    "AIR STICK AIR",
                    "AIR STICK AIR"));

            assertThat(cropped).containsExactly(
                    "IRON_INGOT IRON_INGOT IRON_INGOT", "AIR STICK AIR", "AIR STICK AIR");
        }

        @Test
        @DisplayName("an entirely empty grid crops to nothing, not to a row of air")
        void nothingIsNothing() {
            assertThat(ItemRecipes.crop(List.of("AIR AIR AIR", "AIR AIR AIR", "AIR AIR AIR")))
                    .as("a page has to be able to tell 'no recipe' from 'a recipe made of air'")
                    .isEmpty();
        }

        @Test
        @DisplayName("a lopsided grid keeps only the columns that are used")
        void unusedColumnsGo() {
            assertThat(ItemRecipes.crop(List.of("STICK AIR AIR", "STICK AIR AIR")))
                    .containsExactly("STICK", "STICK");
        }
    }
}
