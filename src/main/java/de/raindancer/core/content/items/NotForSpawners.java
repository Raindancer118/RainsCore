package de.raindancer.core.content.items;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Marks a spawn egg that hatches a mob but may never set a spawner — a shop that sells eggs must not sell
 * mob farms with them. {@link NotForSpawnersListener} refuses a marked egg on a spawner or trial spawner;
 * everywhere else it is an ordinary egg. The mark travels on the stack, so marked eggs do not stack with
 * found ones.
 */
public final class NotForSpawners {

    public static final NamespacedKey KEY = new NamespacedKey("rainscore", "not-for-spawners");

    private NotForSpawners() {
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
}
