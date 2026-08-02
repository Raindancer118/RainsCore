package de.raindancer.core.banner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static java.util.Map.entry;

/**
 * Draws a word in block letters, for the console splash.
 *
 * <h2>Why a font and not a picture</h2>
 * The splash this replaces had its logo typed out as six lines of box-drawing characters in a
 * constant. That is fine for one plugin and absurd for ten: nine more hand-drawn logos, each subtly
 * misaligned with the rule under it, each needing redrawing when a plugin is renamed. Ten lines of
 * font data draw all of them, and a plugin that genuinely wants something of its own can still hand
 * {@link Banner} a logo directly.
 *
 * <p>The glyphs are deliberately plain — a 3x5 grid of full blocks, no half-blocks or shading.
 * Consoles disagree about almost every box-drawing character there is, and a logo that renders as
 * mojibake on somebody's terminal is worse than a plain one that renders everywhere.
 */
public final class BlockLetters {

    /** Rows in a glyph. */
    public static final int HEIGHT = 5;

    /** Blank columns between two letters. */
    private static final int TRACKING = 1;

    private static final String[] UNKNOWN = {"   ", "   ", "   ", "   ", "   "};

    private static final Map<Character, String[]> GLYPHS = Map.ofEntries(
            entry('\'', new String[] {" █ ", " █ ", "   ", "   ", "   "}),
            entry('-', new String[] {"   ", "   ", "███", "   ", "   "}),
            entry('.', new String[] {"   ", "   ", "   ", "   ", " █ "}),
            entry('0', new String[] {"███", "█ █", "█ █", "█ █", "███"}),
            entry('1', new String[] {" █ ", "██ ", " █ ", " █ ", "███"}),
            entry('2', new String[] {"███", "  █", "███", "█  ", "███"}),
            entry('3', new String[] {"███", "  █", "███", "  █", "███"}),
            entry('4', new String[] {"█ █", "█ █", "███", "  █", "  █"}),
            entry('5', new String[] {"███", "█  ", "███", "  █", "███"}),
            entry('6', new String[] {"███", "█  ", "███", "█ █", "███"}),
            entry('7', new String[] {"███", "  █", " █ ", " █ ", " █ "}),
            entry('8', new String[] {"███", "█ █", "███", "█ █", "███"}),
            entry('9', new String[] {"███", "█ █", "███", "  █", "███"}),
            entry('A', new String[] {"███", "█ █", "███", "█ █", "█ █"}),
            entry('B', new String[] {"██ ", "█ █", "██ ", "█ █", "██ "}),
            entry('C', new String[] {"███", "█  ", "█  ", "█  ", "███"}),
            entry('D', new String[] {"██ ", "█ █", "█ █", "█ █", "██ "}),
            entry('E', new String[] {"███", "█  ", "██ ", "█  ", "███"}),
            entry('F', new String[] {"███", "█  ", "██ ", "█  ", "█  "}),
            entry('G', new String[] {"███", "█  ", "█ █", "█ █", "███"}),
            entry('H', new String[] {"█ █", "█ █", "███", "█ █", "█ █"}),
            entry('I', new String[] {"███", " █ ", " █ ", " █ ", "███"}),
            entry('J', new String[] {"███", "  █", "  █", "█ █", "███"}),
            entry('K', new String[] {"█ █", "█ █", "██ ", "█ █", "█ █"}),
            entry('L', new String[] {"█  ", "█  ", "█  ", "█  ", "███"}),
            entry('M', new String[] {"█ █", "███", "███", "█ █", "█ █"}),
            entry('N', new String[] {"██ ", "█ █", "█ █", "█ █", "█ █"}),
            entry('O', new String[] {"███", "█ █", "█ █", "█ █", "███"}),
            entry('P', new String[] {"███", "█ █", "███", "█  ", "█  "}),
            entry('Q', new String[] {"███", "█ █", "█ █", "███", "  █"}),
            entry('R', new String[] {"███", "█ █", "██ ", "█ █", "█ █"}),
            entry('S', new String[] {"███", "█  ", "███", "  █", "███"}),
            entry('T', new String[] {"███", " █ ", " █ ", " █ ", " █ "}),
            entry('U', new String[] {"█ █", "█ █", "█ █", "█ █", "███"}),
            entry('V', new String[] {"█ █", "█ █", "█ █", "█ █", " █ "}),
            entry('W', new String[] {"█ █", "█ █", "███", "███", "█ █"}),
            entry('X', new String[] {"█ █", "█ █", " █ ", "█ █", "█ █"}),
            entry('Y', new String[] {"█ █", "█ █", " █ ", " █ ", " █ "}),
            entry('Z', new String[] {"███", "  █", " █ ", "█  ", "███"})    );

    private BlockLetters() {
    }

    /**
     * The word as block letters, one string per row.
     *
     * <p>Empty for an empty word, so a caller can pass a name straight in without checking.
     */
    public static List<String> render(String word) {
        if (word == null || word.isBlank()) {
            return List.of();
        }
        String letters = word.trim().toUpperCase(Locale.ROOT);
        List<StringBuilder> rows = new ArrayList<>(HEIGHT);
        for (int row = 0; row < HEIGHT; row++) {
            rows.add(new StringBuilder());
        }
        for (int index = 0; index < letters.length(); index++) {
            String[] glyph = GLYPHS.getOrDefault(letters.charAt(index), UNKNOWN);
            for (int row = 0; row < HEIGHT; row++) {
                if (index > 0) {
                    rows.get(row).append(" ".repeat(TRACKING));
                }
                rows.get(row).append(glyph[row]);
            }
        }
        return rows.stream().map(StringBuilder::toString).map(String.class::cast).toList();
    }

    /**
     * How wide that word would be, without drawing it.
     *
     * <p>Used to decide whether a name fits or has to be abbreviated.
     */
    public static int widthOf(String word) {
        List<String> drawn = render(word);
        return drawn.isEmpty() ? 0 : drawn.getFirst().length();
    }

    /**
     * A name short enough to draw: the name itself when it fits, otherwise its initials.
     *
     * <p>Preferring the whole name matters — {@code RAINSCORE} spelled out is a better logo than
     * {@code RC}, and most of these plugins do fit. {@link #initialsOf} is the fallback, and is
     * separate so that each half can be understood and tested on its own.
     */
    public static String abbreviate(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String trimmed = name.trim();
        if (widthOf(trimmed) <= Banner.MAX_WIDTH) {
            return trimmed.toUpperCase(Locale.ROOT);
        }
        return initialsOf(trimmed);
    }

    /**
     * The initials of a name, for when it is too wide to draw in full.
     *
     * <p>Splitting on capitals is what makes this useful for the names these plugins actually have:
     * {@code RainsResourcepackManager} becomes {@code RRM} rather than being cut off after four
     * letters. A run of capitals stays together, so {@code RainsTPA} keeps its {@code TPA}, which is
     * the part anybody would recognise.
     */
    public static String initialsOf(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String trimmed = name.trim();
        StringBuilder initials = new StringBuilder();
        boolean previousWasUpper = false;
        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            boolean startsWord = index == 0
                    || Character.isWhitespace(trimmed.charAt(index - 1))
                    || (Character.isUpperCase(character) && !previousWasUpper);
            // A run of capitals is an acronym somebody chose — TPA, SMP — and taking only its first
            // letter would throw away the recognisable part of the name.
            boolean insideAcronym = Character.isUpperCase(character) && previousWasUpper;
            if ((startsWord || insideAcronym) && Character.isLetterOrDigit(character)) {
                initials.append(Character.toUpperCase(character));
            }
            previousWasUpper = Character.isUpperCase(character);
        }
        String shortened = initials.toString();
        return shortened.isEmpty() ? trimmed.substring(0, 1).toUpperCase(Locale.ROOT) : shortened;
    }
}
