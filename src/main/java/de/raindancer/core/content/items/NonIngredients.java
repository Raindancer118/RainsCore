package de.raindancer.core.content.items;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Marks a stack as something that is never worth its material: a coin that happens to be a gold nugget,
 * a banknote that happens to be paper, a token that happens to be a nether star.
 *
 * <p>A marked stack still moves freely between chests and players. What {@link NonIngredientListener}
 * refuses is every way of using it up <em>as</em> its material — crafting, smelting, brewing, anvils,
 * smithing, looms, villagers, beacons and piglins.
 *
 * <p>Unlike {@link CustomItem#isIngredient}, this needs no registered item definition: the mark travels
 * on the stack, so items whose value differs stack by stack can carry it.
 */
public final class NonIngredients {

    public static final NamespacedKey KEY = new NamespacedKey("rainscore", "not-an-ingredient");

    private NonIngredients() {
    }

    /** Marks it; returns the same stack. */
    public static ItemStack mark(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    public static boolean isMarked(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE);
    }

    public static boolean anyMarked(ItemStack[] stacks) {
        if (stacks == null) {
            return false;
        }
        for (ItemStack stack : stacks) {
            if (isMarked(stack)) {
                return true;
            }
        }
        return false;
    }
}
