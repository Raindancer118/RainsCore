package de.raindancer.core.ui.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Paints text in a {@link NameStyle}: any number of colour stops, interpolated per character.
 *
 * <p>Not MiniMessage's {@code <gradient>}: that one would need the text pasted into markup, and the text
 * here is somebody's name — inserting it as markup is how a player called {@code <rainbow>} recolours
 * everybody's chat. Everything here builds components from plain text.
 *
 * <h2>Every decoration is stated, never left to inherit</h2>
 * An item's custom name renders italic unless something says otherwise, and an unset decoration
 * inherits from its parent. So all five are written out as {@code TRUE} or {@code FALSE} — otherwise a
 * style without italic would silently mean italic on an item.
 */
public final class Gradients {

    private Gradients() {
    }

    /**
     * Repaints {@code base} in {@code style}.
     *
     * <p>A style with colours flattens {@code base} to plain text first — there is no way to keep the
     * old colours under a new gradient. A style with only decorations keeps {@code base} as it is, so
     * bolding an existing gradient keeps the gradient. {@link NameStyle#NONE} returns it unchanged.
     */
    public static Component apply(Component base, NameStyle style) {
        if (style.isEmpty()) {
            return base;
        }
        if (style.colours().isEmpty()) {
            return base.decorations(states(style));
        }
        return styled(PlainTextComponentSerializer.plainText().serialize(base), style);
    }

    /** {@code text} painted in {@code style}, as it stands at the start of any animation. */
    public static Component styled(String text, NameStyle style) {
        return styled(text, style, 0);
    }

    /**
     * {@code text} painted in {@code style} at {@code phase} of its animation — 0 to 1, one full
     * pass round the colours. A still style ignores the phase.
     */
    public static Component styled(String text, NameStyle style, double phase) {
        String content = text == null ? "" : text;
        List<TextColor> stops = style.colours();
        if (stops.isEmpty()) {
            return Component.text(content).decorations(states(style));
        }
        if (stops.size() == 1) {
            return Component.text(content, stops.getFirst()).decorations(states(style));
        }
        // Code points, not chars: an emoji in a nickname is two chars, and colouring each half on its
        // own makes the client draw two replacement boxes.
        int[] points = content.codePoints().toArray();
        TextComponent.Builder builder = Component.text().decorations(states(style));
        for (int index = 0; index < points.length; index++) {
            TextColor colour = style.isAnimated()
                    ? cyclicColourAt(stops, (double) index / points.length + phase)
                    : colourAt(stops, index, points.length);
            builder.append(Component.text(Character.toString(points[index])).color(colour));
        }
        return builder.build();
    }

    /**
     * The colour of character {@code index} of {@code length} across {@code stops}.
     *
     * <p>The first character is the first stop and the last is the last stop, so a two-letter name
     * shows both ends rather than stopping halfway. A one-character name takes the first stop.
     */
    public static TextColor colourAt(List<TextColor> stops, int index, int length) {
        if (length <= 1 || stops.size() == 1) {
            return stops.getFirst();
        }
        double position = (double) index / (length - 1) * (stops.size() - 1);
        int lower = (int) Math.floor(position);
        if (lower >= stops.size() - 1) {
            return stops.getLast();
        }
        return TextColor.lerp((float) (position - lower), stops.get(lower), stops.get(lower + 1));
    }

    /**
     * The colour at {@code position} round a loop through the stops and back to the first — so a
     * moving gradient flows on rather than jumping from the last colour to the first.
     */
    public static TextColor cyclicColourAt(List<TextColor> stops, double position) {
        if (stops.size() == 1) {
            return stops.getFirst();
        }
        double around = position - Math.floor(position);
        double scaled = around * stops.size();
        int lower = (int) Math.floor(scaled) % stops.size();
        int upper = (lower + 1) % stops.size();
        return TextColor.lerp((float) (scaled - Math.floor(scaled)), stops.get(lower), stops.get(upper));
    }

    private static Map<TextDecoration, TextDecoration.State> states(NameStyle style) {
        Map<TextDecoration, TextDecoration.State> states = new LinkedHashMap<>();
        for (TextDecoration decoration : TextDecoration.values()) {
            states.put(decoration, style.has(decoration)
                    ? TextDecoration.State.TRUE
                    : TextDecoration.State.FALSE);
        }
        return states;
    }
}
