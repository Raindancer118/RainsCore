package de.raindancer.core.util;

/**
 * How wide a piece of text is on screen, in pixels of Minecraft's default font.
 *
 * <h2>Why not just count characters</h2>
 * Because the font is proportional and the answer is off by a factor of three between the extremes:
 * {@code lllllllllll} is 33 pixels and {@code WWWWWWWWWWW} is 77. A character budget therefore either
 * clips titles that would have fitted or lets titles through that do not, depending on what somebody
 * happened to name their claim — which is exactly what a "30 characters" limit did: it passed
 * {@code RSC » Gamerules} and also passed {@code YeukSMP » Your claims · by name}, and only the second
 * ran off the edge of the window.
 * <p>
 * Bold is a pixel wider per character, which matters here because the plugin's tag is bold and a server
 * with a seven-letter tag has noticeably less room left than one with three.
 *
 * <h2>The table</h2>
 * Widths are the glyph plus its one pixel of spacing, i.e. what the next character's x advances by.
 * Anything not listed — accented letters, box drawing, most of Unicode — is the common case of six, and
 * anything outside the printable ASCII range is treated the same way. That is a guess for a name in a
 * script this table does not cover, but it is a guess in the safe direction for the Latin text these
 * titles are actually made of.
 */
public final class FontWidth {

    /** The default advance, in pixels, for a character this class has no entry for. */
    private static final int DEFAULT = 6;

    /** Printable ASCII, from space (32) to tilde (126). */
    private static final int[] ASCII = {
        4, 2, 5, 6, 6, 6, 6, 3,   // space ! " # $ % & '
        5, 5, 5, 6, 2, 6, 2, 6,   // ( ) * + , - . /
        6, 6, 6, 6, 6, 6, 6, 6,   // 0-7
        6, 6, 2, 2, 5, 6, 5, 6,   // 8 9 : ; < = > ?
        7, 6, 6, 6, 6, 6, 6, 6,   // @ A-G
        6, 4, 6, 6, 6, 6, 6, 6,   // H I J K L M N O
        6, 6, 6, 6, 6, 6, 6, 6,   // P-W
        6, 6, 6, 4, 6, 4, 6, 6,   // X Y Z [ \ ] ^ _
        3, 6, 6, 6, 6, 6, 5, 6,   // ` a b c d e f g
        6, 2, 6, 5, 3, 6, 6, 6,   // h i j k l m n o
        6, 6, 6, 6, 4, 6, 6, 6,   // p q r s t u v w
        6, 6, 6, 5, 2, 5, 7,      // x y z { | } ~
    };

    private FontWidth() {
    }

    /** One character's advance, bold or not. */
    public static int of(char character, boolean bold) {
        int width = character >= ' ' && character - ' ' < ASCII.length
                ? ASCII[character - ' ']
                : DEFAULT;
        return bold ? width + 1 : width;
    }

    /** How wide this text renders, with no formatting. */
    public static int of(String text) {
        return of(text, false);
    }

    /** How wide this text renders. */
    public static int of(String text, boolean bold) {
        if (text == null) {
            return 0;
        }
        int total = 0;
        for (int index = 0; index < text.length(); index++) {
            total += of(text.charAt(index), bold);
        }
        return total;
    }

    /**
     * The longest prefix of {@code text} that fits in {@code pixels}.
     * <p>
     * Cuts between characters, never inside one — there is no such thing as half a glyph.
     */
    public static String fit(String text, int pixels, boolean bold) {
        if (text == null || pixels <= 0) {
            return "";
        }
        int used = 0;
        for (int index = 0; index < text.length(); index++) {
            int width = of(text.charAt(index), bold);
            if (used + width > pixels) {
                return text.substring(0, index);
            }
            used += width;
        }
        return text;
    }
}
