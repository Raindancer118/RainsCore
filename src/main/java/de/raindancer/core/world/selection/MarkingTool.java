package de.raindancer.core.world.selection;

import de.raindancer.core.content.items.ItemBuilder;
import de.raindancer.core.content.items.ToolGift;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * The stick a player clicks blocks with to mark a polygon out — the mechanics
 * {@code claims-module}'s own {@code SelectionStick} proved: a persistent-data tag rather than the
 * display name, so renaming an ordinary stick in an anvil can never turn it into this tool or back.
 *
 * <p>One instance per owning plugin (its {@link NamespacedKey} is namespaced to that plugin), so two
 * modules marking two different kinds of thing never confuse each other's tool for their own.
 */
public final class MarkingTool {

    private final Plugin plugin;
    private final NamespacedKey key;

    public MarkingTool(Plugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "marking_tool");
    }

    public NamespacedKey key() {
        return key;
    }

    public boolean isStick(ItemStack stack) {
        return ItemBuilder.hasTag(stack, key);
    }

    /** Which purpose the stick in hand was issued for — the caller's own tag, e.g. "wall" or "road". */
    public String purposeOf(ItemStack stack) {
        return ItemBuilder.tagValue(stack, key);
    }

    public ItemStack create(Material material, String purpose, String displayName, List<String> loreLines) {
        ItemBuilder builder = ItemBuilder.of(material)
                .name(displayName)
                .glint(true)
                .hideAttributes()
                .tag(key, purpose);
        for (String line : loreLines) {
            builder.lore(line);
        }
        return builder.build();
    }

    /** Hands the stick over, dropping it at the player's feet when the inventory is full. */
    public boolean give(Player player, ItemStack stick) {
        return ToolGift.give(player, stick);
    }

    /** Removes every stick this tool issued from the player's inventory. Returns how many were taken. */
    public int revoke(Player player) {
        int removed = 0;
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (isStick(stack)) {
                removed += stack.getAmount();
                contents[slot] = null;
            }
        }
        if (removed > 0) {
            player.getInventory().setContents(contents);
            player.updateInventory();
        }
        return removed;
    }

    public boolean holdsStick(Player player) {
        return isStick(player.getInventory().getItemInMainHand())
                || isStick(player.getInventory().getItemInOffHand());
    }

    public Plugin plugin() {
        return plugin;
    }
}
