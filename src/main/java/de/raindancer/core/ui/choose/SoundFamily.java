package de.raindancer.core.ui.choose;

import java.util.Locale;

/**
 * The families a sound key falls into.
 *
 * <p>Minecraft's sound names are already a hierarchy — {@code block.stone.break},
 * {@code entity.villager.no}, {@code ui.button.click} — so the sorting is the first word, and it is
 * the right sorting because it is the one the game itself uses.
 */
public enum SoundFamily {

    UI("Interface", "OAK_BUTTON", "ui."),
    BLOCK("Blocks", "STONE", "block."),
    ITEM("Items", "IRON_INGOT", "item."),
    ENTITY("Creatures", "ZOMBIE_HEAD", "entity."),
    MUSIC("Music", "MUSIC_DISC_CAT", "music.", "music_disc."),
    AMBIENT("Ambience", "FEATHER", "ambient.", "weather."),
    OTHER("Everything Else", "NOTE_BLOCK");

    private final String title;
    private final String icon;
    private final String[] prefixes;

    SoundFamily(String title, String icon, String... prefixes) {
        this.title = title;
        this.icon = icon;
        this.prefixes = prefixes;
    }

    public String title() {
        return title;
    }

    public String icon() {
        return icon;
    }

    /** Which family a sound key belongs to. Anything unrecognised is {@link #OTHER}, never dropped. */
    public static SoundFamily of(String key) {
        if (key == null || key.isBlank()) {
            return OTHER;
        }
        String name = key.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
        for (SoundFamily family : values()) {
            for (String prefix : family.prefixes) {
                if (name.startsWith(prefix)) {
                    return family;
                }
            }
        }
        return OTHER;
    }
}
