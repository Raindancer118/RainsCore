package de.raindancer.core.ui.choose;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

import java.util.List;

/**
 * Vanilla's sixteen chat colours, as something a chooser can draw and a player can click.
 *
 * <h2>Why this exists</h2>
 * A list of colour names is a list nobody reads at a glance; a grid of blocks that actually look
 * like the colour they stand for is one a player recognises instantly — the same reasoning
 * {@code SoundChooser} draws the thing that makes the sound rather than a note block for every
 * entry. Every screen that lets somebody pick a {@link NamedTextColor} — a personal chat style, a
 * server-wide default, whatever a plugin adds next — wants the same sixteen swatches in the same
 * order, so this is the one place that says what they are.
 */
public final class ColorSwatches {

    /**
     * Every named colour, in the order a picker shows them — not {@link NamedTextColor#NAMES}'s own
     * iteration order, which is a {@code Set} and no order a player would recognise as anything.
     * This is the order the colour codes themselves were assigned in, §0 through §f, which is the
     * one order a screen full of them actually reads as sorted.
     */
    public static final List<NamedTextColor> ALL = List.of(
            NamedTextColor.BLACK, NamedTextColor.DARK_BLUE, NamedTextColor.DARK_GREEN,
            NamedTextColor.DARK_AQUA, NamedTextColor.DARK_RED, NamedTextColor.DARK_PURPLE,
            NamedTextColor.GOLD, NamedTextColor.GRAY, NamedTextColor.DARK_GRAY,
            NamedTextColor.BLUE, NamedTextColor.GREEN, NamedTextColor.AQUA, NamedTextColor.RED,
            NamedTextColor.LIGHT_PURPLE, NamedTextColor.YELLOW, NamedTextColor.WHITE);

    private ColorSwatches() {
    }

    /** A block that actually looks like the colour it stands for. */
    public static Material materialFor(NamedTextColor color) {
        if (color == null) {
            return Material.WHITE_CONCRETE;
        }
        if (color.equals(NamedTextColor.BLACK)) {
            return Material.BLACK_CONCRETE;
        }
        if (color.equals(NamedTextColor.DARK_BLUE)) {
            return Material.BLUE_CONCRETE;
        }
        if (color.equals(NamedTextColor.DARK_GREEN)) {
            return Material.GREEN_CONCRETE;
        }
        if (color.equals(NamedTextColor.DARK_AQUA)) {
            return Material.CYAN_CONCRETE;
        }
        if (color.equals(NamedTextColor.DARK_RED)) {
            return Material.RED_CONCRETE;
        }
        if (color.equals(NamedTextColor.DARK_PURPLE)) {
            return Material.PURPLE_CONCRETE;
        }
        if (color.equals(NamedTextColor.GOLD)) {
            return Material.ORANGE_CONCRETE;
        }
        if (color.equals(NamedTextColor.GRAY)) {
            return Material.LIGHT_GRAY_CONCRETE;
        }
        if (color.equals(NamedTextColor.DARK_GRAY)) {
            return Material.GRAY_CONCRETE;
        }
        if (color.equals(NamedTextColor.BLUE)) {
            return Material.LIGHT_BLUE_CONCRETE;
        }
        if (color.equals(NamedTextColor.GREEN)) {
            return Material.LIME_CONCRETE;
        }
        if (color.equals(NamedTextColor.AQUA)) {
            return Material.CYAN_CONCRETE;
        }
        if (color.equals(NamedTextColor.RED)) {
            return Material.RED_CONCRETE;
        }
        if (color.equals(NamedTextColor.LIGHT_PURPLE)) {
            return Material.MAGENTA_CONCRETE;
        }
        if (color.equals(NamedTextColor.YELLOW)) {
            return Material.YELLOW_CONCRETE;
        }
        return Material.WHITE_CONCRETE;   // NamedTextColor.WHITE, and anything future-added
    }

    /** The colour's own name, capitalised — "dark_purple" read as "Dark purple". */
    public static String readable(NamedTextColor color) {
        String key = NamedTextColor.NAMES.key(color);
        if (key == null) {
            return "Unknown";
        }
        String spaced = key.replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
