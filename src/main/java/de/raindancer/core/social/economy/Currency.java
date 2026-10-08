package de.raindancer.core.social.economy;

import de.raindancer.core.ui.text.Gradients;
import de.raindancer.core.ui.text.NameStyle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What a server's money is called and how it is written: names, symbol, decimals, separators, and the
 * colours of each part — painted the same way a player's name is, gradients and all ({@link NameStyle}).
 *
 * <p>Pure and immutable, so every plugin formats an amount identically and a test can check it
 * without a server.
 */
public record Currency(String singular, String plural, String symbol, Placement placement, int decimals,
                       char groupSeparator, char decimalSeparator, boolean trimZeros,
                       NameStyle nameStyle, NameStyle symbolStyle, NameStyle amountStyle) {

    /** Where the symbol goes. {@code NAME} writes the currency's name after the amount instead. */
    public enum Placement { BEFORE, AFTER, NAME }

    /** Ten to this still leaves a long room for amounts nobody will ever reach. */
    public static final int MOST_DECIMALS = 4;

    private static final NameStyle GOLD = new NameStyle(List.of(NamedTextColor.GOLD), Set.of());
    private static final NameStyle YELLOW = new NameStyle(List.of(NamedTextColor.YELLOW), Set.of());

    public static final Currency DEFAULT = new Currency("Coin", "Coins", "⛃", Placement.BEFORE, 2,
            ',', '.', false, GOLD, GOLD, YELLOW);

    public Currency {
        singular = singular == null || singular.isBlank() ? "Coin" : singular.strip();
        plural = plural == null || plural.isBlank() ? singular : plural.strip();
        symbol = symbol == null ? "" : symbol.strip();
        placement = placement == null ? Placement.BEFORE : placement;
        decimals = Math.max(0, Math.min(MOST_DECIMALS, decimals));
        if (groupSeparator == decimalSeparator) {
            // The same character for both makes "1,000" unreadable in either direction.
            groupSeparator = decimalSeparator == ',' ? '.' : ',';
        }
        nameStyle = nameStyle == null ? NameStyle.NONE : nameStyle;
        symbolStyle = symbolStyle == null ? NameStyle.NONE : symbolStyle;
        amountStyle = amountStyle == null ? NameStyle.NONE : amountStyle;
    }

    // ---------------------------------------------------------------------------- units

    /** Minor units in one major unit: 100 for two decimals. */
    public long unit() {
        long unit = 1;
        for (int i = 0; i < decimals; i++) {
            unit *= 10;
        }
        return unit;
    }

    public Money ofMajor(long major) {
        return Money.of(Math.multiplyExact(major, unit()));
    }

    /** Whole major units in an amount, rounded towards zero. */
    public long wholeUnits(Money money) {
        return money.minor() / unit();
    }

    /** "Coin" for exactly one major unit, "Coins" for everything else, zero included. */
    public String nameFor(Money money) {
        return money.minor() == unit() ? singular : plural;
    }

    // ---------------------------------------------------------------------------- writing

    /** The number alone: {@code 1,234.56}, or {@code 1,234} when the cents are zero and trimmed. */
    public String amount(Money money) {
        BigDecimal value = BigDecimal.valueOf(money.minor()).abs().movePointLeft(decimals);
        boolean whole = money.minor() % unit() == 0;
        String digits = trimZeros && whole
                ? value.setScale(0, RoundingMode.UNNECESSARY).toPlainString()
                : value.setScale(decimals, RoundingMode.UNNECESSARY).toPlainString();
        return grouped(digits);
    }

    /** The whole thing as plain text: {@code $1,234.56}, {@code 1.234,56 €}, {@code 3 Coins}. */
    public String format(Money money) {
        return written(money, amount(money));
    }

    /**
     * Shortened past a thousand major units: {@code $1.2k}, {@code $3M}, {@code $1.1B}. For a
     * leaderboard or an item's lore, where {@code $1,234,567.89} would not fit.
     */
    public String compact(Money money) {
        return written(money, compactNumber(money));
    }

    private String compactNumber(Money money) {
        BigDecimal major = BigDecimal.valueOf(money.minor()).abs().movePointLeft(decimals);
        String[] suffixes = {"k", "M", "B", "T"};
        BigDecimal thousand = BigDecimal.valueOf(1000);
        if (major.compareTo(thousand) < 0) {
            return amount(money);
        }
        int index = -1;
        BigDecimal scaled = major;
        while (scaled.compareTo(thousand) >= 0 && index < suffixes.length - 1) {
            scaled = scaled.divide(thousand);
            index++;
        }
        String number = scaled.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return number.replace('.', decimalSeparator) + suffixes[index];
    }

    private String written(Money money, String number) {
        String sign = money.isNegative() ? "-" : "";
        return switch (effectivePlacement()) {
            case BEFORE -> sign + symbol + number;
            case AFTER -> sign + number + " " + symbol;
            case NAME -> sign + number + " " + nameFor(money);
        };
    }

    /** A blank symbol cannot be placed, so it falls back to the name rather than a bare number. */
    private Placement effectivePlacement() {
        return symbol.isEmpty() ? Placement.NAME : placement;
    }

    private String grouped(String plain) {
        int point = plain.indexOf('.');
        String whole = point < 0 ? plain : plain.substring(0, point);
        String fraction = point < 0 ? "" : plain.substring(point + 1);
        StringBuilder built = new StringBuilder();
        for (int i = 0; i < whole.length(); i++) {
            if (i > 0 && (whole.length() - i) % 3 == 0) {
                built.append(groupSeparator);
            }
            built.append(whole.charAt(i));
        }
        if (!fraction.isEmpty()) {
            built.append(decimalSeparator).append(fraction);
        }
        return built.toString();
    }

    // ---------------------------------------------------------------------------- painting

    /** {@link #format}, painted: the number in the amount style, the symbol or name in its own. */
    public Component render(Money money) {
        return painted(money, amount(money));
    }

    /** {@link #compact}, painted. */
    public Component renderCompact(Money money) {
        return painted(money, compactNumber(money));
    }

    private Component painted(Money money, String number) {
        String sign = money.isNegative() ? "-" : "";
        Component amount = Gradients.styled(sign + number, amountStyle);
        return switch (effectivePlacement()) {
            case BEFORE -> Component.text().append(Gradients.styled(sign, amountStyle))
                    .append(Gradients.styled(symbol, symbolStyle))
                    .append(Gradients.styled(number, amountStyle)).build();
            case AFTER -> Component.text().append(amount).append(Component.text(" "))
                    .append(Gradients.styled(symbol, symbolStyle)).build();
            case NAME -> Component.text().append(amount).append(Component.text(" "))
                    .append(Gradients.styled(nameFor(money), nameStyle)).build();
        };
    }

    /** The currency's name in its own colours — for a title, a menu heading, a sentence. */
    public Component renderName(boolean pluralForm) {
        return Gradients.styled(pluralForm ? plural : singular, nameStyle);
    }

    /** The symbol in its own colours; the name when there is no symbol. */
    public Component renderSymbol() {
        return symbol.isEmpty() ? renderName(true) : Gradients.styled(symbol, symbolStyle);
    }

    // ---------------------------------------------------------------------------- reading

    /**
     * What somebody typed, as an amount: {@code 12}, {@code 12.5}, {@code $1,234.56}, {@code 1.5k},
     * {@code 7 dollars}, and {@code 12,50} from somebody used to a decimal comma.
     *
     * <p>Empty for anything negative, unreadable, too large for a long, or finer than the currency's
     * smallest unit — refused rather than rounded, because the person typing it is there to be told.
     */
    public Optional<Money> parse(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String text = typed.strip().toLowerCase(Locale.ROOT);
        for (String word : List.of(plural.toLowerCase(Locale.ROOT), singular.toLowerCase(Locale.ROOT))) {
            if (!word.isEmpty() && text.endsWith(word)) {
                text = text.substring(0, text.length() - word.length()).strip();
                break;
            }
        }
        if (!symbol.isEmpty()) {
            text = text.replace(symbol.toLowerCase(Locale.ROOT), "");
        }
        text = text.replace(" ", "").replace("_", "");
        if (text.isEmpty()) {
            return Optional.empty();
        }

        int power = 0;
        char last = text.charAt(text.length() - 1);
        if (last == 'k' || last == 'm' || last == 'b' || last == 't') {
            power = switch (last) {
                case 'k' -> 3;
                case 'm' -> 6;
                case 'b' -> 9;
                default -> 12;
            };
            text = text.substring(0, text.length() - 1);
        }

        text = normaliseSeparators(text);
        if (!text.matches("\\d+(\\.\\d+)?")) {
            return Optional.empty();
        }
        try {
            BigDecimal minor = new BigDecimal(text).movePointRight(power + decimals);
            if (minor.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
                return Optional.empty();
            }
            return Optional.of(Money.of(minor.setScale(0, RoundingMode.UNNECESSARY).longValueExact()));
        } catch (ArithmeticException finerThanACent) {
            return Optional.empty();
        }
    }

    /** Group separators out, the decimal separator turned into a point. */
    private String normaliseSeparators(String text) {
        String group = String.valueOf(groupSeparator);
        String decimal = String.valueOf(decimalSeparator);
        if (!text.contains(decimal) && decimals > 0) {
            int lastGroup = text.lastIndexOf(groupSeparator);
            int after = lastGroup < 0 ? 0 : text.length() - lastGroup - 1;
            if (lastGroup >= 0 && after >= 1 && after <= decimals && after != 3
                    && text.indexOf(groupSeparator) == lastGroup) {
                // "12,50" from a player used to a decimal comma: one separator, followed by fewer digits
                // than a thousands group, cannot have meant thousands.
                return text.substring(0, lastGroup) + "." + text.substring(lastGroup + 1);
            }
        }
        return text.replace(group, "").replace(decimal, ".");
    }

    // ---------------------------------------------------------------------------- changing

    public Currency withNames(String one, String many) {
        return new Currency(one, many, symbol, placement, decimals, groupSeparator, decimalSeparator,
                trimZeros, nameStyle, symbolStyle, amountStyle);
    }

    public Currency withSymbol(String value) {
        return new Currency(singular, plural, value, placement, decimals, groupSeparator, decimalSeparator,
                trimZeros, nameStyle, symbolStyle, amountStyle);
    }

    public Currency withPlacement(Placement value) {
        return new Currency(singular, plural, symbol, value, decimals, groupSeparator, decimalSeparator,
                trimZeros, nameStyle, symbolStyle, amountStyle);
    }

    public Currency withDecimals(int value) {
        return new Currency(singular, plural, symbol, placement, value, groupSeparator, decimalSeparator,
                trimZeros, nameStyle, symbolStyle, amountStyle);
    }

    public Currency withSeparators(char group, char decimal) {
        return new Currency(singular, plural, symbol, placement, decimals, group, decimal,
                trimZeros, nameStyle, symbolStyle, amountStyle);
    }

    public Currency withTrimmedZeros(boolean value) {
        return new Currency(singular, plural, symbol, placement, decimals, groupSeparator, decimalSeparator,
                value, nameStyle, symbolStyle, amountStyle);
    }

    public Currency withNameStyle(NameStyle value) {
        return new Currency(singular, plural, symbol, placement, decimals, groupSeparator, decimalSeparator,
                trimZeros, value, symbolStyle, amountStyle);
    }

    public Currency withSymbolStyle(NameStyle value) {
        return new Currency(singular, plural, symbol, placement, decimals, groupSeparator, decimalSeparator,
                trimZeros, nameStyle, value, amountStyle);
    }

    public Currency withAmountStyle(NameStyle value) {
        return new Currency(singular, plural, symbol, placement, decimals, groupSeparator, decimalSeparator,
                trimZeros, nameStyle, symbolStyle, value);
    }
}
