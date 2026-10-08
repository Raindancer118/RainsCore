package de.raindancer.core.social.economy;

import de.raindancer.core.ui.text.NameStyle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CurrencyTest {

    private static final Currency DOLLARS = Currency.DEFAULT
            .withNames("Dollar", "Dollars").withSymbol("$").withPlacement(Currency.Placement.BEFORE)
            .withDecimals(2);

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    @DisplayName("amounts are grouped, carry their decimals, and drop .00 only when told to")
    void formatting() {
        assertThat(DOLLARS.format(Money.of(123456))).isEqualTo("$1,234.56");
        assertThat(DOLLARS.format(Money.of(-500))).isEqualTo("-$5.00");
        assertThat(DOLLARS.withTrimmedZeros(true).format(Money.of(500))).isEqualTo("$5");
        assertThat(DOLLARS.withTrimmedZeros(true).format(Money.of(550))).isEqualTo("$5.50");
        assertThat(DOLLARS.withPlacement(Currency.Placement.AFTER).withSymbol("€")
                .withSeparators('.', ',').format(Money.of(123456))).isEqualTo("1.234,56 €");
        assertThat(DOLLARS.withDecimals(0).format(Money.of(1234567))).isEqualTo("$1,234,567");
    }

    @Test
    @DisplayName("written with its name, one is singular and everything else plural")
    void names() {
        Currency coins = Currency.DEFAULT.withNames("Coin", "Coins")
                .withPlacement(Currency.Placement.NAME).withDecimals(0);
        assertThat(coins.format(Money.of(1))).isEqualTo("1 Coin");
        assertThat(coins.format(Money.of(2))).isEqualTo("2 Coins");
        assertThat(coins.format(Money.ZERO)).isEqualTo("0 Coins");
        assertThat(DOLLARS.nameFor(Money.of(100))).isEqualTo("Dollar");
        assertThat(DOLLARS.nameFor(Money.of(101))).isEqualTo("Dollars");
    }

    @Test
    @DisplayName("large amounts shorten to k, M and B, and small ones are left alone")
    void compact() {
        assertThat(DOLLARS.compact(Money.of(99_900))).isEqualTo("$999.00");
        assertThat(DOLLARS.compact(Money.of(123_456))).isEqualTo("$1.2k");
        assertThat(DOLLARS.compact(Money.of(1_000_000_00L))).isEqualTo("$1M");
        assertThat(DOLLARS.compact(Money.of(2_550_000_000_00L))).isEqualTo("$2.6B");
        assertThat(DOLLARS.compact(Money.of(-123_456))).isEqualTo("-$1.2k");
    }

    @Test
    @DisplayName("what players type is understood, and what makes no sense is refused rather than guessed")
    void parsing() {
        assertThat(DOLLARS.parse("12")).contains(Money.of(1200));
        assertThat(DOLLARS.parse("12.5")).contains(Money.of(1250));
        assertThat(DOLLARS.parse("$1,234.56")).contains(Money.of(123456));
        assertThat(DOLLARS.parse("1.5k")).contains(Money.of(150000));
        assertThat(DOLLARS.parse("2M")).contains(Money.of(200_000_000));
        assertThat(DOLLARS.parse(" 7 dollars ")).contains(Money.of(700));
        assertThat(DOLLARS.parse("12,50")).as("a German player's decimal comma").contains(Money.of(1250));

        assertThat(DOLLARS.parse("12.345")).as("a fraction of a cent").isEmpty();
        assertThat(DOLLARS.parse("-5")).isEmpty();
        assertThat(DOLLARS.parse("")).isEmpty();
        assertThat(DOLLARS.parse(null)).isEmpty();
        assertThat(DOLLARS.parse("five")).isEmpty();
        assertThat(DOLLARS.parse("NaN")).isEmpty();
        assertThat(DOLLARS.parse("99999999999999999999999")).isEmpty();
    }

    @Test
    @DisplayName("major units convert both ways without drift")
    void units() {
        assertThat(DOLLARS.unit()).isEqualTo(100);
        assertThat(DOLLARS.ofMajor(7)).isEqualTo(Money.of(700));
        assertThat(DOLLARS.withDecimals(0).ofMajor(7)).isEqualTo(Money.of(7));
        assertThat(DOLLARS.wholeUnits(Money.of(799))).isEqualTo(7);
    }

    @Test
    @DisplayName("decimals are kept inside what a long can count")
    void decimalsAreClamped() {
        assertThat(DOLLARS.withDecimals(-3).decimals()).isZero();
        assertThat(DOLLARS.withDecimals(30).decimals()).isEqualTo(Currency.MOST_DECIMALS);
    }

    @Test
    @DisplayName("rendered, the text is the same as formatted, and the symbol wears its own gradient")
    void rendering() {
        NameStyle gold = new NameStyle(List.of(TextColor.fromHexString("#ffd700"),
                TextColor.fromHexString("#ff8c00")), Set.of());
        Currency painted = DOLLARS.withSymbolStyle(gold).withAmountStyle(new NameStyle(
                List.of(NamedTextColor.WHITE), Set.of()));

        Component shown = painted.render(Money.of(123456));
        assertThat(plain(shown)).isEqualTo("$1,234.56");

        List<TextColor> colours = new ArrayList<>();
        collect(shown, colours);
        assertThat(colours).contains(TextColor.fromHexString("#ffd700"), NamedTextColor.WHITE);
    }

    @Test
    @DisplayName("the name renders in its gradient, singular or plural")
    void renderingTheName() {
        NameStyle rainbow = new NameStyle(List.of(NamedTextColor.RED, NamedTextColor.BLUE), Set.of());
        Currency painted = DOLLARS.withNameStyle(rainbow);
        assertThat(plain(painted.renderName(false))).isEqualTo("Dollar");
        assertThat(plain(painted.renderName(true))).isEqualTo("Dollars");
        List<TextColor> colours = new ArrayList<>();
        collect(painted.renderName(true), colours);
        assertThat(colours).contains(NamedTextColor.RED, NamedTextColor.BLUE);
    }

    @Test
    @DisplayName("a currency with no symbol falls back to its name rather than printing a bare number")
    void noSymbol() {
        Currency bare = DOLLARS.withSymbol("");
        assertThat(bare.format(Money.of(250))).isEqualTo("2.50 Dollars");
        assertThat(Optional.of(bare.placement())).contains(Currency.Placement.BEFORE);
    }

    private static void collect(Component component, List<TextColor> into) {
        if (component.color() != null) {
            into.add(component.color());
        }
        if (component instanceof TextComponent) {
            component.children().forEach(child -> collect(child, into));
        }
    }
}
