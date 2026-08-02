package de.raindancer.core.chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A whole look, chosen by name.
 *
 * <h2>Why presets and not eleven colour settings</h2>
 * Eleven settings can express every look and make none of them easy. Colours are not independent —
 * a title colour that reads well against a lore colour is a pair somebody chose, and a server owner
 * changing one at a time is being asked to redo that work by trial and error, in a game, one restart
 * at a time. A preset is the pair already made. The individual settings stay, and any one of them
 * still wins when it is filled in, so "the ember theme but with a grey lore" is two clicks rather
 * than a fork.
 *
 * <p>Each also carries the two ends of the gradient the plugin signs its windows and messages with,
 * because a purple tag over an amber theme is exactly the mismatch this is meant to end.
 *
 * @param id         what the setting is set to
 * @param title      what it is called on the Appearance page
 * @param titleLabel the fixed part of a window title
 * @param titleValue the part that changes
 * @param itemName   a button's name
 * @param itemLore   the lines under it
 * @param ok         done
 * @param warn       careful
 * @param bad        no
 * @param danger     about to destroy something
 * @param brandFrom  the near end of the tag's gradient
 * @param brandTo    the far end
 */
public record Preset(String id, String title, String titleLabel, String titleValue, String itemName,
                     String itemLore, String ok, String warn, String bad, String danger,
                     String brandFrom, String brandTo) {

    /** What the plugin has always looked like: grey titles, aqua names, a violet tag. */
    public static final Preset DEFAULT = new Preset("default", "Default",
            "dark_gray", "white", "aqua", "gray",
            "green", "yellow", "red", "dark_red",
            "#C9A0FF", "#7C5CBF");

    private static final List<Preset> ALL = List.of(
            DEFAULT,
            // Cool and dim: for a server whose windows sit over dark builds.
            new Preset("midnight", "Midnight",
                    "dark_gray", "#CBD5E1", "#7DD3FC", "#64748B",
                    "#4ADE80", "#FBBF24", "#F87171", "dark_red",
                    "#7DD3FC", "#3B6FA8"),
            // Warm: amber and rust, the colours of the record covers.
            new Preset("ember", "Ember",
                    "dark_gray", "#F5E0C3", "#E8B04B", "#8D8880",
                    "#8FBF6F", "#E8B04B", "#B3452E", "dark_red",
                    "#E8B04B", "#B3452E"),
            // Green, quiet, easy on the eye over grass.
            new Preset("forest", "Forest",
                    "dark_gray", "#E8F0E3", "#7FB069", "#7A8B76",
                    "#7FB069", "#D9C86B", "#C1666B", "dark_red",
                    "#7FB069", "#3E6B3A"),
            // Blue and white: a cold, clean look, the ice-and-snow end of the palette.
            new Preset("frost", "Frost",
                    "dark_gray", "white", "#8FCBFF", "#9FB3C8",
                    "#6FD3B8", "#FFD98E", "#FF8A8A", "dark_red",
                    "#DCEBFF", "#3A7CC4"),
            // No colour at all beyond the three that mean something. For servers that want the
            // interface to disappear.
            new Preset("mono", "Mono",
                    "dark_gray", "white", "white", "gray",
                    "green", "yellow", "red", "dark_red",
                    "#D7DCE0", "#8B949E"));

    private static final Map<String, Preset> BY_ID = index();

    private static Map<String, Preset> index() {
        Map<String, Preset> byId = new LinkedHashMap<>();
        for (Preset preset : ALL) {
            byId.put(preset.id(), preset);
        }
        return Map.copyOf(byId);
    }

    public static List<Preset> all() {
        return ALL;
    }

    /** The ids, in the order they are offered. */
    public static List<String> ids() {
        return ALL.stream().map(Preset::id).toList();
    }

    /** The preset with this id, or the default — a misspelt name must not leave the plugin colourless. */
    public static Preset byId(String id) {
        if (id == null || id.isBlank()) {
            return DEFAULT;
        }
        return BY_ID.getOrDefault(id.trim().toLowerCase(Locale.ROOT), DEFAULT);
    }

    /** What this preset says a given role is, so {@link Style} can ask without a switch per role. */
    public String colourFor(String settingKey) {
        return switch (settingKey) {
            case Style.TITLE_LABEL -> titleLabel;
            case Style.TITLE_VALUE -> titleValue;
            case Style.ITEM_NAME -> itemName;
            case Style.ITEM_LORE -> itemLore;
            case Style.OK -> ok;
            case Style.WARN -> warn;
            case Style.BAD -> bad;
            case Style.DANGER -> danger;
            case Style.BRAND_FROM -> brandFrom;
            case Style.BRAND_TO -> brandTo;
            default -> null;
        };
    }
}
