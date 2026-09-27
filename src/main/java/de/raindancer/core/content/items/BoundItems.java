package de.raindancer.core.content.items;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Items handed out as buttons — a lobby compass, a tracking compass — which a player may not throw
 * away. Bind the stack before giving it; Core's {@link BoundItemListener} refuses the drop.
 *
 * <p>Only dropping is refused. What happens to a bound item on death stays the owning plugin's call:
 * some delete theirs from the drops and hand out a fresh one on respawn, and Core cannot know which.
 *
 * <p>The key is Core's own namespace rather than the calling plugin's, so one listener recognises every
 * plugin's bound items and a plugin needs no listener of its own.
 */
public final class BoundItems {

    public static final NamespacedKey KEY = new NamespacedKey("rainscore", "bound");

    private BoundItems() {
    }

    /** Marks {@code stack} as bound and hands it back, for chaining into a give. */
    public static ItemStack bind(ItemStack stack) {
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

    /** Whether {@code stack} was bound. */
    public static boolean isBound(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE);
    }
}
