package de.raindancer.core.ui.text;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The style itself: how it combines, and how it survives being written onto an item and read back.
 * <p>
 * The round trip is the one that matters. A style lives in an item's persistent data between two
 * server sessions, so a change to the encoding that nothing checks would silently turn every dyed
 * name tag on the server back into a plain one.
 */
class NameStyleTest {

    @Test
    @DisplayName("a style survives being written and read back")
    void roundTrip() {
        NameStyle original = new NameStyle(
                List.of(NamedTextColor.DARK_RED, TextColor.fromHexString("#835432")),
                Set.of(TextDecoration.BOLD, TextDecoration.ITALIC));

        NameStyle read = NameStyle.decode(original.encodeColours(), original.encodeDecorations());

        assertThat(read).isEqualTo(original);
    }

    @Test
    @DisplayName("two styles built by different routes are equal")
    void equalityDoesNotDependOnOrder() {
        NameStyle one = new NameStyle(List.of(NamedTextColor.RED), Set.of())
                .toggle(TextDecoration.ITALIC).toggle(TextDecoration.BOLD);
        NameStyle other = new NameStyle(List.of(NamedTextColor.RED),
                Set.of(TextDecoration.BOLD, TextDecoration.ITALIC));

        // Both the equality and the encoding: a set with an unstable iteration order would make the
        // second of these pass and the first fail, or the other way round, depending on the day.
        assertThat(one).isEqualTo(other);
        assertThat(one.encodeDecorations()).isEqualTo(other.encodeDecorations());
    }

    @Test
    @DisplayName("data written by something else is read as far as it makes sense")
    void unreadableDataIsSkippedNotFatal() {
        // Off an item edited with an NBT tool, or written by a future version. Refusing the whole
        // style would leave the player holding a name tag that cannot be used at all.
        NameStyle read = NameStyle.decode("#ff0000,not-a-colour,", "bold,sideways");

        assertThat(read.colours()).containsExactly(TextColor.fromHexString("#ff0000"));
        assertThat(read.decorations()).containsExactly(TextDecoration.BOLD);
    }

    @Test
    @DisplayName("nothing at all decodes to nothing at all")
    void emptyDecodesToNone() {
        assertThat(NameStyle.decode("", "")).isEqualTo(NameStyle.NONE);
        assertThat(NameStyle.decode(null, null)).isEqualTo(NameStyle.NONE);
        assertThat(NameStyle.NONE.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("dyeing replaces the colour rather than adding a second stop")
    void colourIsReplaced() {
        NameStyle red = NameStyle.NONE.withColour(NamedTextColor.RED);
        assertThat(red.withColour(NamedTextColor.BLUE).colours()).containsExactly(NamedTextColor.BLUE);
    }

    @Test
    @DisplayName("a decoration flips off again, and takes the colour with it untouched")
    void toggling() {
        NameStyle red = NameStyle.NONE.withColour(NamedTextColor.RED);
        NameStyle bold = red.toggle(TextDecoration.BOLD);

        assertThat(bold.decorations()).containsExactly(TextDecoration.BOLD);
        assertThat(bold.toggle(TextDecoration.BOLD)).isEqualTo(red);
    }

    @Test
    @DisplayName("merging lays the colours end to end and pools the decorations")
    void merging() {
        NameStyle merged = NameStyle.merge(List.of(
                new NameStyle(List.of(NamedTextColor.RED), Set.of(TextDecoration.BOLD)),
                new NameStyle(List.of(NamedTextColor.BLUE), Set.of(TextDecoration.ITALIC))));

        assertThat(merged.colours()).containsExactly(NamedTextColor.RED, NamedTextColor.BLUE);
        assertThat(merged.decorations())
                .containsExactlyInAnyOrder(TextDecoration.BOLD, TextDecoration.ITALIC);
    }

    @Test
    @DisplayName("a style cannot be changed once it is on an item")
    void stylesAreImmutable() {
        java.util.List<TextColor> mutable = new java.util.ArrayList<>(List.of(NamedTextColor.RED));
        NameStyle style = new NameStyle(mutable, Set.of());
        mutable.add(NamedTextColor.BLUE);

        assertThat(style.colours()).containsExactly(NamedTextColor.RED);
        assertThat(style.colours()).isUnmodifiable();
    }

    // ------------------------------------------------------------------ one string, for a database column

    @Test
    @DisplayName("a style survives being packed into one string and read back")
    void packedRoundTrip() {
        NameStyle original = new NameStyle(
                List.of(NamedTextColor.GOLD, TextColor.fromHexString("#8e2de2"), NamedTextColor.AQUA),
                Set.of(TextDecoration.BOLD, TextDecoration.UNDERLINED));

        assertThat(NameStyle.parse(original.encode())).isEqualTo(original);
    }

    @Test
    @DisplayName("a lone colour from before styles existed still reads as that colour")
    void legacyColourStillReads() {
        // Core's identity table has held "aqua" or "#55ffff" for as long as it has existed. Reading
        // that as nothing would strip every coloured name on an upgrading server.
        assertThat(NameStyle.parse("aqua").colours()).containsExactly(NamedTextColor.AQUA);
        assertThat(NameStyle.parse("#55ffff").colours())
                .containsExactly(TextColor.fromHexString("#55ffff"));
        assertThat(NameStyle.parse("aqua").decorations()).isEmpty();
    }

    @Test
    @DisplayName("a lone colour packs to the plain form older readers understand")
    void oneColourPacksPlain() {
        // An older Core reads the column as one colour. Written plain, a solid name still shows on a
        // server that rolled Core back; written with a separator, it would show as nothing.
        assertThat(NameStyle.NONE.withColour(NamedTextColor.RED).encode()).isEqualToIgnoringCase("#ff5555");
    }

    @Test
    @DisplayName("decorations without a colour survive the trip as well")
    void decorationsOnlyRoundTrip() {
        NameStyle bold = NameStyle.NONE.toggle(TextDecoration.BOLD);
        assertThat(NameStyle.parse(bold.encode())).isEqualTo(bold);
    }

    @Test
    @DisplayName("nothing packs to nothing, and nothing parses to nothing")
    void emptyPacksEmpty() {
        assertThat(NameStyle.NONE.encode()).isEmpty();
        assertThat(NameStyle.parse("")).isEqualTo(NameStyle.NONE);
        assertThat(NameStyle.parse(null)).isEqualTo(NameStyle.NONE);
        assertThat(NameStyle.parse("  ")).isEqualTo(NameStyle.NONE);
    }

    // ------------------------------------------------------------------ building a gradient by hand

    @Test
    @DisplayName("a stop is added at the end, and the last one taken off again")
    void stopsAreAddedAndRemoved() {
        NameStyle two = NameStyle.NONE.withStop(NamedTextColor.RED).withStop(NamedTextColor.BLUE);
        assertThat(two.colours()).containsExactly(NamedTextColor.RED, NamedTextColor.BLUE);
        assertThat(two.withoutStop(0).colours()).containsExactly(NamedTextColor.BLUE);
        assertThat(two.withoutStop(7)).as("an index past the end changes nothing").isEqualTo(two);
    }

    @Test
    @DisplayName("reversing runs the gradient the other way")
    void reversing() {
        NameStyle two = NameStyle.NONE.withStop(NamedTextColor.RED).withStop(NamedTextColor.BLUE);
        assertThat(two.reversed().colours()).containsExactly(NamedTextColor.BLUE, NamedTextColor.RED);
    }

    @Test
    @DisplayName("a decoration can be set rather than flipped")
    void decorationsCanBeSet() {
        NameStyle bold = NameStyle.NONE.with(TextDecoration.BOLD, true);
        assertThat(bold.has(TextDecoration.BOLD)).isTrue();
        assertThat(bold.with(TextDecoration.BOLD, true)).isEqualTo(bold);
        assertThat(bold.with(TextDecoration.BOLD, false)).isEqualTo(NameStyle.NONE);
    }

    @Test
    @DisplayName("a gradient is more than one stop, and only that")
    void whatCountsAsAGradient() {
        assertThat(NameStyle.NONE.isGradient()).isFalse();
        assertThat(NameStyle.NONE.withColour(NamedTextColor.RED).isGradient()).isFalse();
        assertThat(NameStyle.NONE.withStop(NamedTextColor.RED).withStop(NamedTextColor.RED).isGradient())
                .isTrue();
    }

    @Test
    @DisplayName("colours read in either form a person would type")
    void coloursAreReadAsTyped() {
        assertThat(NameStyle.colourOf("dark_red")).isEqualTo(NamedTextColor.DARK_RED);
        assertThat(NameStyle.colourOf(" #8E2DE2 ")).isEqualTo(TextColor.fromHexString("#8e2de2"));
        assertThat(NameStyle.colourOf("#8e2de")).isNull();
        assertThat(NameStyle.colourOf("sideways")).isNull();
    }

    // ------------------------------------------------------------------ animated

    @Test
    @DisplayName("an animated gradient survives the one-string trip, and a still one packs as before")
    void animatedRoundTrip() {
        NameStyle flowing = NameStyle.NONE.withStop(NamedTextColor.RED).withStop(NamedTextColor.BLUE)
                .animated(true);
        assertThat(flowing.isAnimated()).isTrue();
        assertThat(NameStyle.parse(flowing.encode())).isEqualTo(flowing);

        NameStyle bold = flowing.with(TextDecoration.BOLD, true);
        assertThat(NameStyle.parse(bold.encode())).isEqualTo(bold);

        NameStyle still = flowing.animated(false);
        assertThat(still.encode()).doesNotContain("animated");
    }

    @Test
    @DisplayName("only a gradient can move — one colour has nothing to flow")
    void onlyGradientsMove() {
        assertThat(NameStyle.NONE.withColour(NamedTextColor.RED).animated(true).isAnimated()).isFalse();
    }
}
