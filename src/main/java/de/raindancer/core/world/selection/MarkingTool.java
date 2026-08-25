package de.raindancer.core.world.selection;

import de.raindancer.core.content.items.ToolGift;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The item somebody marks a shape out with, and getting it back afterwards.
 *
 * <h2>Recognised by its persistent data, never by its name</h2>
 * The purpose is written into the stack's {@link org.bukkit.persistence.PersistentDataContainer}.
 * Matching on display name or lore instead — which is what the older plugins here did — means an
 * anvil and a name tag forge one, and on a server where the marking stick is a plain stick it means
 * every stick anybody is carrying becomes a marking tool.
 *
 * <p>Handed out through {@link ToolGift}, because a marker made of a blaze rod would otherwise
 * congratulate the player with <em>Into Fire</em>.
 */
public final class MarkingTool {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final NamespacedKey purposeKey;

    public MarkingTool(Plugin plugin) {
        this.purposeKey = new NamespacedKey(plugin, "marking-purpose");
    }

    /**
     * A marking tool for one purpose.
     *
     * @param purpose what this tool marks out — whatever the caller wants to tell its own tools apart
     */
    public ItemStack create(Material material, String purpose, String name, List<String> lore) {
        Material usable = material == null || !material.isItem() ? Material.STICK : material;
        ItemStack stack = new ItemStack(usable, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.displayName(MINI.deserialize(name).decoration(TextDecoration.ITALIC, false));
        List<Component> lines = new ArrayList<>(lore.size());
        for (String line : lore) {
            lines.add(MINI.deserialize(line).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lines);
        meta.getPersistentDataContainer().set(purposeKey, PersistentDataType.STRING, purpose);
        stack.setItemMeta(meta);
        return stack;
    }

    /** What this stack marks out, if it is a marking tool at all. */
    public Optional<String> purposeOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        return Optional.ofNullable(
                meta.getPersistentDataContainer().get(purposeKey, PersistentDataType.STRING));
    }

    public boolean isMarkingTool(ItemStack stack) {
        return purposeOf(stack).isPresent();
    }

    /** @return whether all of it fitted; false means it is on the ground at their feet */
    public boolean give(Player player, ItemStack tool) {
        return ToolGift.give(player, tool);
    }

    /**
     * Takes every marking tool back off a player.
     *
     * <p>Every one, not the first: a player who put the stick down and was handed another has two,
     * and leaving one behind leaves a stick that still marks things after the marking has ended.
     */
    public int revoke(Player player) {
        if (player == null) {
            return 0;
        }
        int removed = 0;
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isMarkingTool(contents[slot])) {
                player.getInventory().setItem(slot, null);
                removed++;
            }
        }
        return removed;
    }
}
