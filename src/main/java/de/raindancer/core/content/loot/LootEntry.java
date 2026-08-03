package de.raindancer.core.content.loot;

import org.bukkit.Material;

import java.util.Optional;
import java.util.Random;

/**
 * One thing that might come out of a chest, and how likely it is.
 *
 * <h2>Why an entry may be a custom item instead of a material</h2>
 * Because the interesting loot is not bread. A supply drop containing a medikit or a grappling hook
 * is the whole reason a loot table is worth configuring, and those are {@code core.items}
 * definitions rather than materials. So an entry is one or the other, and carries the item's key
 * rather than the item — the same reason a {@code CustomItem} carries a key: the table can be read
 * from a file before the plugin owning the item has registered it.
 *
 * @param weight   how likely, relative to everything else in the pool. Zero means never.
 * @param minimum  fewest of it
 * @param maximum  most of it
 */
public record LootEntry(Material material, String customKey, int weight, int minimum, int maximum) {

    public LootEntry {
        if (material == null && (customKey == null || customKey.isBlank())) {
            throw new IllegalArgumentException(
                    "A loot entry has to be either a material or a custom item.");
        }
        weight = Math.max(0, weight);
        // A backwards range is what somebody meant, written the wrong way round; refusing it would
        // be pedantry about a typo whose intent is obvious.
        int low = Math.max(1, Math.min(minimum, maximum));
        int high = Math.max(1, Math.max(minimum, maximum));
        minimum = low;
        maximum = high;
    }

    /** A plain block or item. */
    public static LootEntry of(Material material, int weight) {
        if (material == null) {
            throw new IllegalArgumentException("A loot entry needs a material.");
        }
        return new LootEntry(material, null, weight, 1, 1);
    }

    /** One of the custom items, by its {@code plugin:id} key. */
    public static LootEntry ofCustomItem(String key, int weight) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A custom loot entry needs an item key.");
        }
        return new LootEntry(null, key.trim(), weight, 1, 1);
    }

    /** How many of it. */
    public LootEntry amount(int least, int most) {
        return new LootEntry(material, customKey, weight, least, most);
    }

    public LootEntry weight(int newWeight) {
        return new LootEntry(material, customKey, newWeight, minimum, maximum);
    }

    public boolean isCustom() {
        return customKey != null && !customKey.isBlank();
    }

    /** Which custom item this is, if it is one. */
    public Optional<String> customItem() {
        return isCustom() ? Optional.of(customKey) : Optional.empty();
    }

    /** How many of it this time. Never fewer than one — an entry that gives nothing is not loot. */
    public int rollAmount(Random random) {
        if (maximum <= minimum) {
            return minimum;
        }
        return minimum + random.nextInt(maximum - minimum + 1);
    }

    /** What this entry is called in a file and in a menu. */
    public String describe() {
        return isCustom() ? customKey : material.name();
    }
}
