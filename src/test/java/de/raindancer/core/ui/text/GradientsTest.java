package de.raindancer.core.ui.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Painting a name.
 * <p>
 * Two of these are about the thing that makes this feature either work or look broken, and neither
 * is obvious from reading the code: a gradient has to reach its last colour on the last character
 * rather than somewhere near the end, and every decoration has to be written out even when it is
 * off, because "unset" on an item name means "italic".
 */
class GradientsTest {

    private static final TextColor RED = NamedTextColor.RED;
    private static final TextColor BLUE = NamedTextColor.BLUE;

    @Test
    @DisplayName("a gradient starts on the first character and ends on the last")
    void gradientSpansTheWholeName() {
        List<TextColor> stops = List.of(RED, BLUE);

        assertThat(Gradients.colourAt(stops, 0, 5)).isEqualTo(RED);
        assertThat(Gradients.colourAt(stops, 4, 5)).isEqualTo(BLUE);
        // Halfway along is halfway between, and not either end.
        assertThat(Gradients.colourAt(stops, 2, 5)).isNotEqualTo(RED).isNotEqualTo(BLUE);
    }

    @Test
    @DisplayName("a name with one character takes the first colour rather than dividing by zero")
    void oneCharacterName() {
        assertThat(Gradients.colourAt(List.of(RED, BLUE), 0, 1)).isEqualTo(RED);
    }

    @Test
    @DisplayName("a two-character name shows both ends of the gradient, not just the start")
    void twoCharacterNameReachesBothStops() {
        // The obvious index/length formula would put the second character halfway, so a two-letter
        // name would never actually be blue anywhere. This is the case that catches it.
        assertThat(Gradients.colourAt(List.of(RED, BLUE), 1, 2)).isEqualTo(BLUE);
    }

    @Test
    @DisplayName("a three-stop gradient hits the middle stop in the middle")
    void middleStopIsReached() {
        TextColor green = NamedTextColor.GREEN;
        assertThat(Gradients.colourAt(List.of(RED, green, BLUE), 2, 5)).isEqualTo(green);
    }

    @Test
    @DisplayName("every decoration is stated, so an item name never inherits vanilla's italics")
    void decorationsAreAlwaysExplicit() {
        Component painted = Gradients.styled("Sword", new NameStyle(List.of(RED), Set.of(TextDecoration.BOLD)));

        assertThat(painted.style().decoration(TextDecoration.BOLD)).isEqualTo(TextDecoration.State.TRUE);
        // The one that matters: not NOT_SET, which would render as italic on an item.
        assertThat(painted.style().decoration(TextDecoration.ITALIC)).isEqualTo(TextDecoration.State.FALSE);
        assertThat(painted.style().decoration(TextDecoration.UNDERLINED)).isEqualTo(TextDecoration.State.FALSE);
    }

    @Test
    @DisplayName("a style with no colours decorates the name it is given without flattening it")
    void decorationOnlyKeepsTheExistingColours() {
        Component gradient = Gradients.styled("Sword", new NameStyle(List.of(RED, BLUE), Set.of()));
        Component bolded = Gradients.apply(gradient, new NameStyle(List.of(), Set.of(TextDecoration.BOLD)));

        assertThat(bolded.style().decoration(TextDecoration.BOLD)).isEqualTo(TextDecoration.State.TRUE);
        // Still one child per character, each with its own colour: bolding a gradient must not
        // collapse it back to a single flat component.
        assertThat(bolded.children()).hasSize("Sword".length());
        assertThat(bolded.children().getFirst().color()).isEqualTo(RED);
        assertThat(bolded.children().getLast().color()).isEqualTo(BLUE);
    }

    @Test
    @DisplayName("painting keeps the text exactly, whatever it contains")
    void textSurvives() {
        String name = "Rain's  Sword ✦ 2";
        Component painted = Gradients.styled(name, new NameStyle(List.of(RED, BLUE), Set.of()));
        assertThat(PlainTextComponentSerializer.plainText().serialize(painted)).isEqualTo(name);
    }

    @Test
    @DisplayName("an empty style leaves the name completely alone")
    void emptyStyleIsANoOp() {
        Component original = Component.text("Sword");
        assertThat(Gradients.apply(original, NameStyle.NONE)).isSameAs(original);
    }

    @Test
    @DisplayName("a character outside the basic plane is painted whole, never split in two")
    void surrogatePairsStayWhole() {
        // A nickname can carry an emoji, which Java holds as two chars. Painted char by char, each half
        // becomes its own component and the client draws two replacement boxes instead of the emoji.
        String name = "A\uD83D\uDD25B";
        Component painted = Gradients.styled(name, new NameStyle(List.of(RED, BLUE), Set.of()));

        assertThat(PlainTextComponentSerializer.plainText().serialize(painted)).isEqualTo(name);
        assertThat(painted.children()).hasSize(3);
        assertThat(painted.children().getLast().color()).isEqualTo(BLUE);
    }

    @Test
    @DisplayName("an animated gradient shifts with the phase, and comes round to where it started")
    void phaseShifts() {
        NameStyle flowing = new NameStyle(List.of(RED, BLUE), Set.of()).animated(true);

        Component start = Gradients.styled("Raindancer", flowing, 0.0);
        Component later = Gradients.styled("Raindancer", flowing, 0.25);
        Component round = Gradients.styled("Raindancer", flowing, 1.0);

        assertThat(start.children().getFirst().color()).isEqualTo(RED);
        assertThat(later.children().getFirst().color()).isNotEqualTo(RED);
        assertThat(round.children().getFirst().color()).isEqualTo(RED);
    }

    @Test
    @DisplayName("a moving gradient loops: it runs back to the first colour rather than jumping")
    void loopsSmoothly() {
        // Cyclic: half way round, the far end of a two-colour gradient is blue.
        assertThat(Gradients.cyclicColourAt(List.of(RED, BLUE), 0.5)).isEqualTo(BLUE);
        assertThat(Gradients.cyclicColourAt(List.of(RED, BLUE), 0.0)).isEqualTo(RED);
        assertThat(Gradients.cyclicColourAt(List.of(RED, BLUE), 0.999)).isNotEqualTo(BLUE);
    }

    @Test
    @DisplayName("a still gradient ignores the phase")
    void stillIgnoresPhase() {
        NameStyle still = new NameStyle(List.of(RED, BLUE), Set.of());
        assertThat(Gradients.styled("Raindancer", still, 0.4)).isEqualTo(Gradients.styled("Raindancer", still));
    }
}
