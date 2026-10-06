package de.raindancer.core.ui.text;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * How a name is to be painted: a list of colour stops, and the decorations that go with them.
 *
 * <p>One stop is a solid colour, two are a gradient, more are a multi-stop gradient — the single colour
 * is the special case of the list, so {@link Gradients} has one code path for all of them.
 *
 * <p>Used for item and mob names (names-module: a dyed name tag) and for players' own names
 * ({@code Identities#setNameStyle}, picked in cosmetics-module). Immutable; every method returns a new
 * style.
 *
 * <h2>Two encodings, both persisted</h2>
 * {@link #encodeColours}/{@link #encodeDecorations} are two strings in a name tag's persistent data, and
 * have been since the standalone Coloured Names plugin — changing them strips every dyed tag on a server.
 * {@link #encode}/{@link #parse} pack both into one string for a database column, and a style with one
 * colour and no decorations packs to the bare hex code, which is exactly what the identity table held
 * before styles existed — so old rows read, and a rolled-back Core still reads new solid colours.
 */
public record NameStyle(List<TextColor> colours, Set<TextDecoration> decorations, boolean animated) {

    /** No colour, no decoration. */
    public static final NameStyle NONE = new NameStyle(List.of(), Set.of(), false);

    /** The marker in {@link #encode} for a gradient that flows. */
    private static final String ANIMATED = "animated";

    /**
     * The most stops a style may carry.
     *
     * <p>A crafting grid holds eight tags beside the item, and past eight a gradient over a sixteen
     * character name changes colour every two letters and reads as noise.
     */
    public static final int MAX_STOPS = 8;

    private static final String SEPARATOR = ",";

    /** Between the colours and the decorations in {@link #encode}. Never in a hex code or a name. */
    private static final String PART = "|";

    /** A still style — the shape every style had before animation, and what items always are. */
    public NameStyle(List<TextColor> colours, Set<TextDecoration> decorations) {
        this(colours, decorations, false);
    }

    public NameStyle {
        colours = List.copyOf(colours);
        // One colour has nothing to flow; saying it is animated would only make two equal styles unequal.
        animated = animated && colours.size() > 1;
        // EnumSet keeps declaration order, so encoding is stable and two styles built by different
        // routes compare equal. A HashSet would make both accidental.
        decorations = decorations.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(decorations));
    }

    public boolean isEmpty() {
        return colours.isEmpty() && decorations.isEmpty();
    }

    /** More than one stop. Lets a caller price or permit a gradient differently from a solid colour. */
    public boolean isGradient() {
        return colours.size() > 1;
    }

    /** Whether the gradient flows along the name over time. */
    public boolean isAnimated() {
        return animated;
    }

    public NameStyle animated(boolean on) {
        return new NameStyle(colours, decorations, on);
    }

    public boolean has(TextDecoration decoration) {
        return decorations.contains(decoration);
    }

    /** Replaces the colour outright with a single stop. */
    public NameStyle withColour(TextColor colour) {
        return new NameStyle(List.of(colour), decorations, animated);
    }

    /** Adds a stop at the end of the gradient. The caller enforces {@link #MAX_STOPS}. */
    public NameStyle withStop(TextColor colour) {
        List<TextColor> next = new ArrayList<>(colours);
        next.add(colour);
        return new NameStyle(next, decorations, animated);
    }

    /** Removes the stop at {@code index}; an index that does not exist changes nothing. */
    public NameStyle withoutStop(int index) {
        if (index < 0 || index >= colours.size()) {
            return this;
        }
        List<TextColor> next = new ArrayList<>(colours);
        next.remove(index);
        return new NameStyle(next, decorations, animated);
    }

    /** The same stops, the other way round. */
    public NameStyle reversed() {
        return new NameStyle(colours.reversed(), decorations, animated);
    }

    /** The same decorations, no colour. */
    public NameStyle withoutColours() {
        return new NameStyle(List.of(), decorations, false);
    }

    /** Adds the decoration if it is missing, removes it if it is there. */
    public NameStyle toggle(TextDecoration decoration) {
        return with(decoration, !has(decoration));
    }

    public NameStyle with(TextDecoration decoration, boolean on) {
        Set<TextDecoration> next = decorations.isEmpty()
                ? EnumSet.noneOf(TextDecoration.class)
                : EnumSet.copyOf(decorations);
        if (on) {
            next.add(decoration);
        } else {
            next.remove(decoration);
        }
        return new NameStyle(colours, next, animated);
    }

    /**
     * Lays several styles end to end: the colours become the stops of one gradient, in the order given,
     * and every decoration any of them carries is kept.
     */
    public static NameStyle merge(List<NameStyle> stops) {
        List<TextColor> colours = new ArrayList<>();
        Set<TextDecoration> decorations = EnumSet.noneOf(TextDecoration.class);
        for (NameStyle stop : stops) {
            colours.addAll(stop.colours());
            decorations.addAll(stop.decorations());
        }
        return new NameStyle(colours, decorations);
    }

    // ------------------------------------------------------------------ item persistence

    /** The colours as {@code #rrggbb,#rrggbb}. Hex even for named colours: one form, readable in NBT. */
    public String encodeColours() {
        return String.join(SEPARATOR, colours.stream().map(TextColor::asHexString).toList());
    }

    public String encodeDecorations() {
        return String.join(SEPARATOR, decorations.stream()
                .map(decoration -> decoration.name().toLowerCase(Locale.ROOT)).toList());
    }

    /**
     * The inverse, forgiving of anything it does not recognise: an unreadable entry is dropped and the
     * rest kept, because these strings may come from an older build or an NBT editor, and refusing the
     * whole style turns a cosmetic mistake into an item that cannot be used.
     */
    public static NameStyle decode(String colours, String decorations) {
        List<TextColor> stops = new ArrayList<>();
        for (String part : split(colours)) {
            TextColor colour = colourOf(part);
            if (colour != null) {
                stops.add(colour);
            }
        }
        Set<TextDecoration> found = EnumSet.noneOf(TextDecoration.class);
        for (String part : split(decorations)) {
            TextDecoration decoration = TextDecoration.NAMES.value(part.toLowerCase(Locale.ROOT));
            if (decoration != null) {
                found.add(decoration);
            }
        }
        return new NameStyle(stops, found);
    }

    // ------------------------------------------------------------------ one-string persistence

    /**
     * Everything in one string: {@code #a,#b|bold,italic}, {@code #a,#b|bold|animated}, or just
     * {@code #a} for a lone colour.
     */
    public String encode() {
        if (animated) {
            return encodeColours() + PART + encodeDecorations() + PART + ANIMATED;
        }
        if (decorations.isEmpty()) {
            return encodeColours();
        }
        return encodeColours() + PART + encodeDecorations();
    }

    /** The inverse of {@link #encode}, and of the lone colour names Core stored before this existed. */
    public static NameStyle parse(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return NONE;
        }
        String[] parts = encoded.split(java.util.regex.Pattern.quote(PART), -1);
        NameStyle read = decode(parts[0], parts.length > 1 ? parts[1] : "");
        boolean flows = parts.length > 2 && parts[2].trim().equalsIgnoreCase(ANIMATED);
        return read.animated(flows);
    }

    /** A named colour ({@code dark_red}) or a hex code ({@code #f38baa}); null for anything else. */
    public static TextColor colourOf(String typed) {
        if (typed == null || typed.isBlank()) {
            return null;
        }
        String cleaned = typed.trim().toLowerCase(Locale.ROOT);
        if (cleaned.startsWith("#")) {
            return cleaned.matches("#[0-9a-f]{6}") ? TextColor.fromHexString(cleaned) : null;
        }
        return NamedTextColor.NAMES.value(cleaned);
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(SEPARATOR)).map(String::trim)
                .filter(part -> !part.isEmpty()).toList();
    }
}
