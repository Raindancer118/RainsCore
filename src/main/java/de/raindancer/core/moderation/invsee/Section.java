package de.raindancer.core.moderation.invsee;

/**
 * The parts of what somebody is carrying.
 *
 * <h2>Why these are named rather than numbered</h2>
 * Because a player's inventory is one array with four different meanings in it, and every plugin
 * that treats it as one array gets something wrong. Slots 0–8 are the hotbar but they are stored
 * <em>after</em> the main storage; slots 36–39 are armour and are in the reverse of the order they
 * are worn; slot 40 is the off-hand. Nothing about that is guessable, so it is written down once
 * here and never again.
 *
 * <p>Naming them is also what makes a window worth looking at. "Their inventory" as thirty-six
 * identical squares tells a moderator nothing; hotbar, backpack, what they are wearing and what is
 * in their ender chest, laid out apart, is a page you can read at a glance.
 */
public enum Section {

    /** The nine slots they can hold. What somebody is about to use. */
    HOTBAR("Hotbar", "GOLDEN_SWORD", 9),

    /** The twenty-seven above it — the backpack. */
    STORAGE("Backpack", "CHEST", 27),

    /** Helmet, chestplate, leggings, boots. */
    ARMOUR("Worn", "IRON_CHESTPLATE", 4),

    /** The one in the other hand. */
    OFF_HAND("Off Hand", "SHIELD", 1),

    /** The ender chest, which is not in the inventory at all and is usually forgotten. */
    ENDER_CHEST("Ender Chest", "ENDER_CHEST", 27);

    private final String title;
    private final String icon;
    private final int size;

    Section(String title, String icon, int size) {
        this.title = title;
        this.icon = icon;
        this.size = size;
    }

    /** What to write above it. */
    public String title() {
        return title;
    }

    /** The material to label it with, by name. */
    public String icon() {
        return icon;
    }

    /** How many slots it has. */
    public int size() {
        return size;
    }

    /** Whether this is something worn, which is protected by default — see {@code Access}. */
    public boolean isEquipment() {
        return this == ARMOUR || this == OFF_HAND;
    }

    /** Whether this lives outside the inventory array and has to be read separately. */
    public boolean isSeparate() {
        return this == ENDER_CHEST;
    }
}
