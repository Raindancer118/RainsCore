package de.raindancer.core.ui.choose;

/**
 * How long ago somebody was here — the rank a list of players is sectioned by.
 *
 * <p>Ranks rather than a filter, because both of the obvious answers are wrong. Leaving the long-gone
 * out breaks the case a player chooser exists for: the person being unbanned or having their claim
 * transferred is usually the one nobody has seen for a year. Mixing them in breaks it too, because a
 * four-year-old server has thousands of them and they bury the six names anybody is looking for.
 */
public enum Presence {

    /** Here now. */
    HERE("Online", "LIME_DYE"),

    /** Not here, but recently enough that somebody would remember them. */
    RECENTLY("Seen Recently", "CLOCK"),

    /** A long time ago, or never. Still pickable, and clearly not in the way. */
    LONG_AGO("Long Ago", "COBWEB");

    private final String title;
    private final String icon;

    Presence(String title, String icon) {
        this.title = title;
        this.icon = icon;
    }

    /** What to write above the section. */
    public String title() {
        return title;
    }

    /** The material to draw the section with, by name. */
    public String icon() {
        return icon;
    }
}
