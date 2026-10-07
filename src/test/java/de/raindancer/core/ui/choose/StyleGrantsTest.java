package de.raindancer.core.ui.choose;

import de.raindancer.core.ui.text.NameStyle;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** What a style editor lets somebody do, and the sentence it says when it does not. */
class StyleGrantsTest {

    private static final NameStyle ONE = NameStyle.NONE.withColour(NamedTextColor.RED);
    private static final NameStyle TWO = ONE.withStop(NamedTextColor.BLUE);

    @Test
    @DisplayName("everything allowed: any stop may be added up to the limit, then not")
    void everything() {
        StyleGrants all = StyleGrants.ALL;

        assertThat(all.refuseAddingStop(NameStyle.NONE, 8)).isEmpty();
        assertThat(all.refuseAddingStop(TWO, 2)).contains("At most 2 colours");
    }

    @Test
    @DisplayName("colour without gradient: a first stop yes, a second no — and it says which right is missing")
    void colourOnly() {
        StyleGrants grants = StyleGrants.NOTHING.withColour(true);

        assertThat(grants.refuseAddingStop(NameStyle.NONE, 8)).isEmpty();
        assertThat(grants.refuseAddingStop(ONE, 8)).contains("Gradients are not yours to use");
    }

    @Test
    @DisplayName("refusals can be worded by the caller, e.g. naming the permission node")
    void worded() {
        StyleGrants grants = StyleGrants.NOTHING.withColour(true)
                .worded(StyleGrants.Feature.GRADIENT, "Needs rainscosmetics.name.gradient");

        assertThat(grants.refuseAddingStop(ONE, 8)).contains("Needs rainscosmetics.name.gradient");
    }

    @Test
    @DisplayName("a typed hex needs any-colour on top of being allowed a stop at all")
    void typedHex() {
        StyleGrants grants = StyleGrants.ALL.withAnyColour(false);

        assertThat(grants.refuseTypedColour(NameStyle.NONE, 8)).contains("Only the palette's colours are yours");
        assertThat(StyleGrants.ALL.refuseTypedColour(NameStyle.NONE, 8)).isEmpty();
    }

    @Test
    @DisplayName("flowing needs a gradient and the right; a decoration needs its own")
    void flowingAndDecorations() {
        assertThat(StyleGrants.ALL.refuseFlowing(ONE)).contains("Needs two colours or more");
        assertThat(StyleGrants.ALL.refuseFlowing(TWO)).isEmpty();
        assertThat(StyleGrants.ALL.withAnimated(false).refuseFlowing(TWO)).isPresent();

        StyleGrants boldOnly = StyleGrants.NOTHING.withDecorations(Set.of(TextDecoration.BOLD));
        assertThat(boldOnly.refuseDecoration(TextDecoration.BOLD)).isEmpty();
        assertThat(boldOnly.refuseDecoration(TextDecoration.OBFUSCATED)).isPresent();
    }

    @Test
    @DisplayName("allows(): whether a whole style may be worn, for a preset or a paste")
    void wholeStyle() {
        StyleGrants colourOnly = StyleGrants.NOTHING.withColour(true);
        NameStyle bold = ONE.with(TextDecoration.BOLD, true);

        assertThat(colourOnly.allows(ONE)).isTrue();
        assertThat(colourOnly.allows(TWO)).isFalse();
        assertThat(colourOnly.allows(bold)).isFalse();
        assertThat(StyleGrants.ALL.allows(TWO.animated(true))).isTrue();
        assertThat(StyleGrants.ALL.withAnyColour(false)
                .allows(NameStyle.NONE.withColour(TextColor.fromHexString("#123456")),
                        List.of(NamedTextColor.RED))).isFalse();
    }
}
