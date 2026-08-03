package de.raindancer.core.ui.choose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sorting everything a server has into the drawers people already know.
 *
 * <h2>Why Core sorts them at all</h2>
 * Because every plugin that lets somebody pick a block builds the same screen: a thousand-odd
 * materials, in enum order, paged. Enum order is not an order — {@code ACACIA_BOAT} sits between
 * {@code ACACIA_BUTTON} and {@code ACACIA_CHEST_BOAT}, and a player looking for redstone scrolls past
 * eleven pages of wood. So each plugin either ships its own hand-written shortlist, which is always
 * missing the block somebody wants, or it ships the wall.
 *
 * <p>The categories here are the creative inventory's, because that is the sorting every player on
 * every server already has in their head. Being clever with a different one would be worse.
 *
 * <h2>Why the materials are injected</h2>
 * {@code Material.isItem()} needs the server's registry, so a catalogue that filtered with it could
 * only be tested on a running server — and what needs testing is the sorting, not the enum. The list
 * comes in, so these tests are the sorting alone.
 */
@DisplayName("the item catalogue")
class CatalogueTest {

    /** A handful of real material names, one from each drawer. */
    private static final List<String> SOME = List.of(
            "STONE", "OAK_PLANKS", "DEEPSLATE_BRICKS",
            "OAK_SAPLING", "RED_BED", "PAINTING", "FLOWER_POT", "TORCH",
            "REDSTONE", "PISTON", "REDSTONE_TORCH", "OBSERVER", "HOPPER",
            "MINECART", "OAK_BOAT", "RAIL", "ELYTRA",
            "APPLE", "COOKED_BEEF", "GOLDEN_CARROT", "BREAD",
            "DIAMOND_PICKAXE", "SHEARS", "FISHING_ROD", "OAK_SIGN",
            "DIAMOND_SWORD", "BOW", "IRON_CHESTPLATE", "SHIELD", "ARROW",
            "POTION", "BREWING_STAND", "GLASS_BOTTLE", "NETHER_WART",
            "ZOMBIE_SPAWN_EGG", "COMMAND_BLOCK", "STRUCTURE_VOID",
            "STICK", "DIAMOND", "BONE", "ENDER_PEARL");

    private static Catalogue catalogue() {
        return new Catalogue(() -> SOME);
    }

    // ------------------------------------------------------------------ the drawers

    @Nested
    @DisplayName("the categories")
    class Categories {

        @Test
        @DisplayName("they are the creative inventory's, because that is what players know")
        void areTheFamiliarOnes() {
            assertThat(Category.values()).extracting(Enum::name)
                    .contains("BUILDING_BLOCKS", "DECORATIONS", "REDSTONE", "TRANSPORTATION",
                            "FOOD", "TOOLS", "COMBAT", "BREWING", "MISC");
        }

        @Test
        @DisplayName("each one has a title and an icon, so nothing has to invent them")
        void comeWithTheirOwnChrome() {
            for (Category category : Category.values()) {
                assertThat(category.title()).isNotBlank();
                assertThat(category.icon())
                        .as(category + " has no icon, so every chooser would have to pick one")
                        .isNotBlank();
            }
        }
    }

    // ------------------------------------------------------------------ the sorting

    @Nested
    @DisplayName("sorting things into them")
    class Sorting {

        @Test
        @DisplayName("blocks you build with")
        void buildingBlocks() {
            assertThat(Catalogue.categoryOf("STONE")).isEqualTo(Category.BUILDING_BLOCKS);
            assertThat(Catalogue.categoryOf("OAK_PLANKS")).isEqualTo(Category.BUILDING_BLOCKS);
            assertThat(Catalogue.categoryOf("DEEPSLATE_BRICKS")).isEqualTo(Category.BUILDING_BLOCKS);
        }

        @Test
        @DisplayName("things you decorate with")
        void decorations() {
            assertThat(Catalogue.categoryOf("PAINTING")).isEqualTo(Category.DECORATIONS);
            assertThat(Catalogue.categoryOf("FLOWER_POT")).isEqualTo(Category.DECORATIONS);
            assertThat(Catalogue.categoryOf("OAK_SAPLING")).isEqualTo(Category.DECORATIONS);
        }

        @Test
        @DisplayName("redstone")
        void redstone() {
            assertThat(Catalogue.categoryOf("REDSTONE")).isEqualTo(Category.REDSTONE);
            assertThat(Catalogue.categoryOf("PISTON")).isEqualTo(Category.REDSTONE);
            assertThat(Catalogue.categoryOf("OBSERVER")).isEqualTo(Category.REDSTONE);
            assertThat(Catalogue.categoryOf("HOPPER")).isEqualTo(Category.REDSTONE);
        }

        @Test
        @DisplayName("things you travel with")
        void transport() {
            assertThat(Catalogue.categoryOf("MINECART")).isEqualTo(Category.TRANSPORTATION);
            assertThat(Catalogue.categoryOf("OAK_BOAT")).isEqualTo(Category.TRANSPORTATION);
            assertThat(Catalogue.categoryOf("RAIL")).isEqualTo(Category.TRANSPORTATION);
            assertThat(Catalogue.categoryOf("ELYTRA")).isEqualTo(Category.TRANSPORTATION);
        }

        @Test
        @DisplayName("food")
        void food() {
            assertThat(Catalogue.categoryOf("APPLE")).isEqualTo(Category.FOOD);
            assertThat(Catalogue.categoryOf("COOKED_BEEF")).isEqualTo(Category.FOOD);
            assertThat(Catalogue.categoryOf("BREAD")).isEqualTo(Category.FOOD);
        }

        @Test
        @DisplayName("tools and weapons are told apart")
        void toolsAndWeapons() {
            assertThat(Catalogue.categoryOf("DIAMOND_PICKAXE")).isEqualTo(Category.TOOLS);
            assertThat(Catalogue.categoryOf("SHEARS")).isEqualTo(Category.TOOLS);
            assertThat(Catalogue.categoryOf("DIAMOND_SWORD")).isEqualTo(Category.COMBAT);
            assertThat(Catalogue.categoryOf("IRON_CHESTPLATE")).isEqualTo(Category.COMBAT);
            assertThat(Catalogue.categoryOf("BOW"))
                    .as("a bow is not a tool, whatever the word 'tool' suggests")
                    .isEqualTo(Category.COMBAT);
        }

        @Test
        @DisplayName("brewing")
        void brewing() {
            assertThat(Catalogue.categoryOf("POTION")).isEqualTo(Category.BREWING);
            assertThat(Catalogue.categoryOf("BREWING_STAND")).isEqualTo(Category.BREWING);
            assertThat(Catalogue.categoryOf("NETHER_WART")).isEqualTo(Category.BREWING);
        }

        @Test
        @DisplayName("anything that fits nowhere goes to the last drawer rather than vanishing")
        void everythingLandsSomewhere() {
            Catalogue catalogue = catalogue();
            int sorted = 0;
            for (Category category : Category.values()) {
                sorted += catalogue.itemsIn(category).size();
            }
            assertThat(sorted)
                    .as("an item that is in no category is an item nobody can ever pick")
                    .isEqualTo(SOME.size());
        }

        @Test
        @DisplayName("a name nobody has ever heard of is misc, not a crash")
        void unknownNames() {
            assertThat(Catalogue.categoryOf("SOME_BLOCK_FROM_2031")).isEqualTo(Category.MISC);
            assertThat(Catalogue.categoryOf(null)).isEqualTo(Category.MISC);
        }
    }

    // ------------------------------------------------------------------ finding one

    @Nested
    @DisplayName("searching")
    class Searching {

        @Test
        @DisplayName("part of a name is enough")
        void findsBySubstring() {
            assertThat(catalogue().search("diamond"))
                    .containsExactlyInAnyOrder("DIAMOND_PICKAXE", "DIAMOND_SWORD", "DIAMOND");
        }

        @Test
        @DisplayName("spaces work where underscores are")
        void spacesWork() {
            assertThat(catalogue().search("cooked beef"))
                    .as("nobody types underscores into a search box")
                    .containsExactly("COOKED_BEEF");
        }

        @Test
        @DisplayName("an exact name comes first")
        void exactMatchesLead() {
            assertThat(catalogue().search("redstone").getFirst())
                    .as("searching for the thing you named should not put four other things above it")
                    .isEqualTo("REDSTONE");
        }

        @Test
        @DisplayName("an empty search is everything, not nothing")
        void emptySearch() {
            assertThat(catalogue().search("")).hasSize(SOME.size());
            assertThat(catalogue().search(null)).hasSize(SOME.size());
        }

        @Test
        @DisplayName("nothing matching is empty rather than everything")
        void noMatches() {
            assertThat(catalogue().search("qwertyuiop")).isEmpty();
        }
    }

    // ------------------------------------------------------------------ presentation

    @Nested
    @DisplayName("showing a name to somebody")
    class Naming {

        @Test
        @DisplayName("a material name is written the way a person would")
        void readableNames() {
            assertThat(Catalogue.readable("DIAMOND_PICKAXE")).isEqualTo("Diamond Pickaxe");
            assertThat(Catalogue.readable("TNT"))
                    .as("an acronym that is only capitals should not become 'Tnt'")
                    .isEqualTo("TNT");
            assertThat(Catalogue.readable("STONE")).isEqualTo("Stone");
        }

        @Test
        @DisplayName("items inside a category are in alphabetical order, not enum order")
        void sortedWithinACategory() {
            List<String> combat = catalogue().itemsIn(Category.COMBAT);
            assertThat(combat)
                    .as("enum order puts ACACIA_BOAT between two buttons; nobody can find "
                            + "anything in it")
                    .isSorted();
        }
    }

    // ------------------------------------------------------------------ drawers inside drawers

    /**
     * The second level, which is where a chooser stops being a wall.
     *
     * <p>"Building Blocks" on a modern server is several hundred materials, and eleven of every
     * twelve are wood. Somebody looking for deepslate scrolls past acacia, bamboo, birch, cherry,
     * crimson and dark oak to get there. The creative inventory has the same problem and players
     * solve it by knowing where things are; a chooser has to solve it by grouping.
     */
    @Nested
    @DisplayName("groups within a category")
    class Groups {

        @Test
        @DisplayName("wood is split by which tree it came from")
        void woodByTree() {
            assertThat(Catalogue.groupOf("OAK_PLANKS")).isEqualTo("Oak");
            assertThat(Catalogue.groupOf("ACACIA_STAIRS")).isEqualTo("Acacia");
            assertThat(Catalogue.groupOf("DARK_OAK_SLAB"))
                    .as("dark oak is its own tree and must not be swallowed by oak")
                    .isEqualTo("Dark Oak");
            assertThat(Catalogue.groupOf("CHERRY_LOG")).isEqualTo("Cherry");
            assertThat(Catalogue.groupOf("CRIMSON_PLANKS")).isEqualTo("Crimson");
        }

        @Test
        @DisplayName("stone and its relatives are grouped by the rock")
        void stoneByRock() {
            assertThat(Catalogue.groupOf("STONE_BRICKS")).isEqualTo("Stone");
            assertThat(Catalogue.groupOf("DEEPSLATE_TILES")).isEqualTo("Deepslate");
            assertThat(Catalogue.groupOf("BLACKSTONE_WALL")).isEqualTo("Blackstone");
            assertThat(Catalogue.groupOf("SANDSTONE_STAIRS")).isEqualTo("Sandstone");
        }

        @Test
        @DisplayName("things that come in sixteen colours are grouped by the colour")
        void byColour() {
            assertThat(Catalogue.groupOf("RED_WOOL")).isEqualTo("Red");
            assertThat(Catalogue.groupOf("LIGHT_BLUE_CONCRETE"))
                    .as("light blue is a colour, not blue with a word in front")
                    .isEqualTo("Light Blue");
            assertThat(Catalogue.groupOf("BLACK_TERRACOTTA")).isEqualTo("Black");
        }

        @Test
        @DisplayName("tools and armour are grouped by what they are made of")
        void byMaterial() {
            assertThat(Catalogue.groupOf("DIAMOND_PICKAXE")).isEqualTo("Diamond");
            assertThat(Catalogue.groupOf("NETHERITE_CHESTPLATE")).isEqualTo("Netherite");
            assertThat(Catalogue.groupOf("GOLDEN_SWORD")).isEqualTo("Gold");
        }

        @Test
        @DisplayName("anything with no obvious family gets one of its own rather than none")
        void everythingHasAGroup() {
            assertThat(Catalogue.groupOf("ELYTRA")).isNotBlank();
            assertThat(Catalogue.groupOf("SOME_BLOCK_FROM_2031")).isNotBlank();
            assertThat(Catalogue.groupOf(null)).isNotBlank();
        }

        @Test
        @DisplayName("every category can be asked for its groups, not just building blocks")
        void everyCategoryIsGrouped() {
            Catalogue catalogue = catalogue();
            for (Category category : catalogue.categories()) {
                assertThat(catalogue.groupsIn(category))
                        .as(category + " has items but no groups, so its page is still a wall")
                        .isNotEmpty();
            }
        }

        @Test
        @DisplayName("the groups of a category account for everything in it")
        void groupsLoseNothing() {
            Catalogue catalogue = catalogue();
            for (Category category : catalogue.categories()) {
                int inGroups = catalogue.groupsIn(category).stream()
                        .mapToInt(group -> catalogue.itemsIn(category, group).size())
                        .sum();
                assertThat(inGroups)
                        .as("an item in no group is an item nobody can reach through the menu")
                        .isEqualTo(catalogue.itemsIn(category).size());
            }
        }

        @Test
        @DisplayName("a group with one thing in it is not worth a page of its own")
        void tinyGroupsAreMergedAway() {
            Catalogue catalogue = catalogue();
            assertThat(catalogue.groupsIn(Category.TRANSPORTATION))
                    .as("clicking through to a page holding one item is worse than a longer list")
                    .hasSizeLessThan(catalogue.itemsIn(Category.TRANSPORTATION).size());
        }
    }
}
