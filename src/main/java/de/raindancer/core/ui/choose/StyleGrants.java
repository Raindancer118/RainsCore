package de.raindancer.core.ui.choose;

import de.raindancer.core.ui.text.NameStyle;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What somebody may do in a {@link StyleEditor}, and what it says when they may not.
 *
 * <p>Decides only — no server, no side effects — so the editor greys a button with the same answer it
 * would refuse the click with, and a test can ask every question without a player.
 */
public record StyleGrants(boolean colour, boolean gradient, boolean anyColour, boolean animated,
                          Set<TextDecoration> decorations, Map<Feature, String> wording) {

    /** One thing the editor can grant, for wording a refusal. */
    public enum Feature {
        COLOUR("Colours are not yours to use"),
        GRADIENT("Gradients are not yours to use"),
        ANY_COLOUR("Only the palette's colours are yours"),
        ANIMATED("Flowing gradients are not yours to use"),
        DECORATION("That decoration is not yours to use");

        private final String plain;

        Feature(String plain) {
            this.plain = plain;
        }
    }

    public static final StyleGrants ALL = new StyleGrants(true, true, true, true,
            Set.of(TextDecoration.values()), Map.of());
    public static final StyleGrants NOTHING = new StyleGrants(false, false, false, false, Set.of(), Map.of());

    public StyleGrants {
        decorations = Set.copyOf(decorations == null ? Set.of() : decorations);
        wording = wording == null ? Map.of() : Map.copyOf(wording);
    }

    // ------------------------------------------------------------------ building

    public StyleGrants withColour(boolean on) {
        return new StyleGrants(on, gradient, anyColour, animated, decorations, wording);
    }

    public StyleGrants withGradient(boolean on) {
        return new StyleGrants(colour, on, anyColour, animated, decorations, wording);
    }

    public StyleGrants withAnyColour(boolean on) {
        return new StyleGrants(colour, gradient, on, animated, decorations, wording);
    }

    public StyleGrants withAnimated(boolean on) {
        return new StyleGrants(colour, gradient, anyColour, on, decorations, wording);
    }

    public StyleGrants withDecorations(Set<TextDecoration> allowed) {
        return new StyleGrants(colour, gradient, anyColour, animated, allowed, wording);
    }

    /** Says {@code sentence} instead of the plain refusal for {@code feature} — "Needs some.node", say. */
    public StyleGrants worded(Feature feature, String sentence) {
        Map<Feature, String> next = new EnumMap<>(Feature.class);
        next.putAll(wording);
        next.put(feature, sentence);
        return new StyleGrants(colour, gradient, anyColour, animated, decorations, next);
    }

    // ------------------------------------------------------------------ questions

    public Optional<String> refuseAddingStop(NameStyle style, int maxStops) {
        int stops = style.colours().size();
        if (stops >= maxStops) {
            return Optional.of("At most " + maxStops + " colours");
        }
        if (stops == 0) {
            return colour || gradient ? Optional.empty() : say(Feature.COLOUR);
        }
        return gradient ? Optional.empty() : say(Feature.GRADIENT);
    }

    public Optional<String> refuseTypedColour(NameStyle style, int maxStops) {
        Optional<String> stop = refuseAddingStop(style, maxStops);
        if (stop.isPresent()) {
            return stop;
        }
        return anyColour ? Optional.empty() : say(Feature.ANY_COLOUR);
    }

    public Optional<String> refuseFlowing(NameStyle style) {
        if (!style.isGradient()) {
            return Optional.of("Needs two colours or more");
        }
        return animated ? Optional.empty() : say(Feature.ANIMATED);
    }

    public Optional<String> refuseDecoration(TextDecoration decoration) {
        return decorations.contains(decoration) ? Optional.empty() : say(Feature.DECORATION);
    }

    /** Whether all of {@code style} may be worn, with any colour at all. */
    public boolean allows(NameStyle style) {
        return allows(style, null);
    }

    /**
     * Whether all of {@code style} may be worn.
     *
     * @param palette the colours that count as the palette's; null when any colour is fine to check
     *                only by the other rights
     */
    public boolean allows(NameStyle style, Collection<? extends TextColor> palette) {
        int stops = style.colours().size();
        if (stops == 1 && !colour && !gradient) {
            return false;
        }
        if (stops > 1 && !gradient) {
            return false;
        }
        if (style.isAnimated() && !animated) {
            return false;
        }
        if (!decorations.containsAll(style.decorations())) {
            return false;
        }
        if (!anyColour && palette != null) {
            for (TextColor stop : style.colours()) {
                if (palette.stream().noneMatch(offered -> offered.value() == stop.value())) {
                    return false;
                }
            }
        }
        return true;
    }

    private Optional<String> say(Feature feature) {
        return Optional.of(wording.getOrDefault(feature, feature.plain));
    }
}
