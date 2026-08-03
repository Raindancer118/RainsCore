package de.raindancer.core.choose;

/**
 * The drawers everything is sorted into.
 *
 * <p>The creative inventory's, deliberately. It is the sorting every player on every server already
 * has in their head, and a cleverer one would only be a second thing to learn.
 *
 * <p>Each carries its own title and icon so no chooser anywhere has to invent them — which is how
 * two menus in two plugins end up showing the same category under two different names.
 */
public enum Category {

    BUILDING_BLOCKS("Building Blocks", "BRICKS"),
    DECORATIONS("Decoration", "PEONY"),
    REDSTONE("Redstone", "REDSTONE"),
    TRANSPORTATION("Transport", "POWERED_RAIL"),
    FOOD("Food", "GOLDEN_APPLE"),
    TOOLS("Tools", "IRON_AXE"),
    COMBAT("Combat", "GOLDEN_SWORD"),
    BREWING("Brewing", "BREWING_STAND"),
    /** Everything else. Never empty on a real server, and never a place things disappear into. */
    MISC("Everything Else", "LAVA_BUCKET");

    private final String title;
    private final String icon;

    Category(String title, String icon) {
        this.title = title;
        this.icon = icon;
    }

    /** What to write above it. */
    public String title() {
        return title;
    }

    /** The material to draw it with, by name — resolved by whoever is building the menu. */
    public String icon() {
        return icon;
    }
}
