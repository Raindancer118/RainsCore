package de.raindancer.core.content.items;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Items handed out as buttons — a lobby compass, a tracking compass — which never leave their
 * holder's own inventory. Bind the stack before giving it; Core's {@link BoundItemListener} refuses
 * every way out: dropping, putting it in any container (an ender chest too), a bundle, an item frame,
 * an armour stand or a block, placing it, and dropping it from a full inventory when a window closes.
 *
 * <p>On death a bound item still in the drops is kept, at {@code HIGH} priority — after a plugin that
 * deletes its own from the drops at {@code NORMAL} to hand out a fresh one on respawn, which keeps
 * working as before.
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
