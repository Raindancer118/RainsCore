package de.raindancer.core.ui.choose;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.DyeColor;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One colour a {@link StyleEditor} offers: what it is called, what it is, what stands for it. */
public record Swatch(String label, TextColor colour, Material icon) {

    /**
     * The sixteen dyes at their real colours, then the chat colours no dye has — the palette a server
     * gets when it does not write its own.
     */
    public static List<Swatch> defaults() {
        List<Swatch> swatches = new ArrayList<>();
        for (DyeColor dye : ColorSwatches.DYES) {
            swatches.add(new Swatch(dye.name().toLowerCase(Locale.ROOT).replace('_', ' '),
                    ColorSwatches.ofDye(dye), ColorSwatches.dyeItem(dye)));
        }
        for (NamedTextColor chat : ColorSwatches.ALL) {
            swatches.add(new Swatch(ColorSwatches.readable(chat).toLowerCase(Locale.ROOT) + " (chat)",
                    chat, ColorSwatches.materialFor(chat)));
        }
        return List.copyOf(swatches);
    }
}
