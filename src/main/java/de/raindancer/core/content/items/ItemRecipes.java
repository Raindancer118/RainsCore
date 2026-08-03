package de.raindancer.core.content.items;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns the rows a server owner wrote into a crafting recipe the server will accept.
 *
 * <h2>Why rows of text</h2>
 * Because that is what somebody types into a config file or arranges in a menu:
 * <pre>
 * recipe:
 *   - "LIGHTNING_ROD DIAMOND_BLOCK LIGHTNING_ROD"
 *   - "NETHERITE_INGOT DIAMOND_BLOCK NETHERITE_INGOT"
 *   - "LIGHTNING_ROD DIAMOND_BLOCK LIGHTNING_ROD"
 * </pre>
 * {@code -} or an empty slot means nothing goes there.
 *
 * <h2>The trimming, which is not optional</h2>
 * Bukkit rejects a shaped recipe whose shape has an entirely empty row or column at its edge — a
 * 3×3 shape with only the middle filled is not "one item in the middle", it is invalid. A menu that
 * lets somebody fill two slots therefore produces a recipe the server refuses, with an exception
 * that names none of this. So the grid is cropped to what is actually in it before the shape is
 * built, which is the whole reason this class exists rather than three lines at the call site.
 */
public final class ItemRecipes {

    private static final LogChannel log = Log.of("items");

    /** The letters used in a shape, in the order slots are filled. */
    private static final String SYMBOLS = "ABCDEFGHI";

    private ItemRecipes() {
    }

    /**
     * The recipe for an item, or empty when it has none or the rows make no sense.
     *
     * @param stack what crafting it produces — from {@link ItemFactory}
     */
    public static Optional<ShapedRecipe> build(Plugin plugin, CustomItem item, ItemStack stack) {
        if (item == null || stack == null || !item.isCraftable()) {
            return Optional.empty();
        }
        Material[][] grid = readGrid(item);
        int[] bounds = boundsOf(grid);
        if (bounds == null) {
            log.warn("The recipe for '{}' is empty, so it cannot be crafted.", item.key());
            return Optional.empty();
        }
        int firstRow = bounds[0];
        int lastRow = bounds[1];
        int firstColumn = bounds[2];
        int lastColumn = bounds[3];

        Map<Material, Character> symbols = new LinkedHashMap<>();
        List<String> shape = new ArrayList<>();
        for (int row = firstRow; row <= lastRow; row++) {
            StringBuilder line = new StringBuilder();
            for (int column = firstColumn; column <= lastColumn; column++) {
                Material material = grid[row][column];
                if (material == null) {
                    line.append(' ');
                    continue;
                }
                // One letter per material, reused: two diamond blocks in a recipe are the same
                // ingredient, and giving them separate letters would be legal but pointless.
                Character symbol = symbols.get(material);
                if (symbol == null) {
                    if (symbols.size() >= SYMBOLS.length()) {
                        log.warn("The recipe for '{}' uses more than nine different materials.",
                                item.key());
                        return Optional.empty();
                    }
                    symbol = SYMBOLS.charAt(symbols.size());
                    symbols.put(material, symbol);
                }
                line.append(symbol);
            }
            shape.add(line.toString());
        }

        try {
            ShapedRecipe recipe = new ShapedRecipe(keyFor(plugin, item), stack);
            recipe.shape(shape.toArray(new String[0]));
            symbols.forEach((material, symbol) -> recipe.setIngredient(symbol, material));
            return Optional.of(recipe);
        } catch (RuntimeException refused) {
            log.error(refused, "The server refused the recipe for '{}'.", item.key());
            return Optional.empty();
        }
    }

    /** The key a recipe is registered under, so it can be removed and replaced when it is edited. */
    public static NamespacedKey keyFor(Plugin plugin, CustomItem item) {
        return new NamespacedKey(plugin, item.key().replace(':', '_'));
    }

    /** The rows as a 3×3 grid, with anything unreadable left empty and reported. */
    private static Material[][] readGrid(CustomItem item) {
        Material[][] grid = new Material[3][3];
        List<String> rows = item.recipe();
        for (int row = 0; row < 3 && row < rows.size(); row++) {
            String[] tokens = rows.get(row).trim().split("\\s+");
            for (int column = 0; column < 3 && column < tokens.length; column++) {
                String token = tokens[column].trim();
                if (token.isEmpty() || token.equals("-") || token.equalsIgnoreCase("AIR")) {
                    continue;
                }
                Material material = Material.matchMaterial(token);
                if (material == null) {
                    // One bad slot is an empty slot, not a recipe nobody can craft: the rest of a
                    // recipe is still useful and the owner is told which word was wrong.
                    log.warn("'{}' in the recipe for '{}' is not a block this server knows; that "
                            + "slot is empty.", token, item.key());
                    continue;
                }
                grid[row][column] = material;
            }
        }
        return grid;
    }

    /**
     * The smallest box containing everything in the grid, as {first row, last row, first column,
     * last column} — or null when the grid is empty.
     */
    private static int[] boundsOf(Material[][] grid) {
        int firstRow = 3;
        int lastRow = -1;
        int firstColumn = 3;
        int lastColumn = -1;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                if (grid[row][column] == null) {
                    continue;
                }
                firstRow = Math.min(firstRow, row);
                lastRow = Math.max(lastRow, row);
                firstColumn = Math.min(firstColumn, column);
                lastColumn = Math.max(lastColumn, column);
            }
        }
        return lastRow < 0 ? null : new int[] {firstRow, lastRow, firstColumn, lastColumn};
    }
}
