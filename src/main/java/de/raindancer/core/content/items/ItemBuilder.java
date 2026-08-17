package de.raindancer.core.content.items;

import de.raindancer.core.ui.menu.Icons;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * A small fluent {@link ItemStack} builder — a tool's stick, a menu icon, a placed sign's held
 * copy. Every module that has needed one so far has written its own; this is the generic two-thirds
 * of those (name, lore, glint, a persistent-data tag, hidden attributes), on top of the same
 * {@link Icons#name(String)}/{@link Icons#loreLine(String)} MiniMessage rendering every menu icon in
 * this codebase already goes through, so an item built here looks and behaves exactly like one drawn
 * on a screen.
 */
public final class ItemBuilder {

    private final ItemStack stack;
    private final List<Component> lore = new ArrayList<>();

    private ItemBuilder(Material material, int amount) {
        this.stack = new ItemStack(material, Math.max(1, Math.min(64, amount)));
    }

    private ItemBuilder(ItemStack existing) {
        this.stack = existing.clone();
        List<Component> current = this.stack.lore();
        if (current != null) {
            lore.addAll(current);
        }
    }

    public static ItemBuilder of(Material material) {
        return new ItemBuilder(material, 1);
    }

    public static ItemBuilder of(Material material, int amount) {
        return new ItemBuilder(material, amount);
    }

    public static ItemBuilder copyOf(ItemStack existing) {
        return new ItemBuilder(existing);
    }

    public ItemBuilder name(String miniMessage) {
        stack.editMeta(meta -> meta.displayName(Icons.name(miniMessage)));
        return this;
    }

    public ItemBuilder lore(String... lines) {
        for (String line : lines) {
            lore.add(Icons.loreLine(line));
        }
        return this;
    }

    public ItemBuilder blank() {
        lore.add(Component.empty());
        return this;
    }

    public ItemBuilder amount(int amount) {
        stack.setAmount(Math.max(1, Math.min(stack.getMaxStackSize(), amount)));
        return this;
    }

    public ItemBuilder glint(boolean enabled) {
        stack.editMeta(meta -> meta.setEnchantmentGlintOverride(enabled));
        return this;
    }

    public ItemBuilder hideAttributes() {
        stack.editMeta(meta -> meta.addItemFlags(ItemFlag.values()));
        return this;
    }

    public ItemBuilder tag(NamespacedKey key, String value) {
        stack.editMeta(meta -> meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, value));
        return this;
    }

    public ItemStack build() {
        if (!lore.isEmpty()) {
            stack.lore(new ArrayList<>(lore));
        }
        return stack;
    }

    /** Whether a stack carries the given tag at all — how a module recognises its own tool. */
    public static boolean hasTag(ItemStack stack, NamespacedKey key) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        ItemStack real = stack;
        return real.getItemMeta() != null
                && real.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    public static String tagValue(ItemStack stack, NamespacedKey key) {
        if (stack == null || stack.getItemMeta() == null) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }
}
