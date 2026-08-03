package de.raindancer.core.ui.chat;

import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * What every window and every message from every one of Rain's plugins is drawn in.
 *
 * <h2>Why this exists</h2>
 * The colours used to live in the code, in as many vocabularies as there were plugins: the claims
 * windows said {@code <dark_gray>} and {@code <white>} in fifty-four separate title methods, the core
 * menus got the same grey by inheriting it from a separator, and the homes module had a hex palette
 * of its own. The result drifted every time a window was added — a near-white title nobody could
 * read, an item name in a colour no other plugin used — and each drift had to be found by playing
 * rather than by building. One place to say "this is what a title looks like" ends that, and hands
 * the answer to the server owner as a setting rather than keeping it as a decision only a recompile
 * can change.
 *
 * <h2>Why it is server-wide rather than per plugin</h2>
 * A player does not know which of nine jars drew the window in front of them, and should not be able
 * to tell. The palette therefore belongs to {@code RainsCore} and every plugin reads it. What stays
 * per plugin is the {@link Brand} — the short tag in front of a message — because that is the one
 * thing a player <em>does</em> benefit from telling apart.
 *
 * <h2>Why a lookup function and not a copy of the values</h2>
 * Settings change while the server is running. Holding the values would serve whatever they were at
 * startup for the rest of the server's life. Every getter asks. Never configured — in a test, or in
 * a plugin loaded without {@code RainsCore} present — every getter answers with {@link Preset#DEFAULT},
 * which is what these plugins looked like before any of it was configurable.
 */
public final class Style {

    /** The colour of the fixed part of a window title: "Claim" in "Claim ▸ base". */
    public static final String TITLE_LABEL = "style.title-label";
    /** The colour of the part that changes: "base" in "Claim ▸ base". */
    public static final String TITLE_VALUE = "style.title-value";
    /** What goes between them. */
    public static final String TITLE_SEPARATOR = "style.title-separator";
    /** The colour of a button's or an item's name. */
    public static final String ITEM_NAME = "style.item-name";
    /** The colour of the lines under it. */
    public static final String ITEM_LORE = "style.item-lore";
    /** Done, careful, and no. */
    public static final String OK = "style.ok";
    public static final String WARN = "style.warn";
    public static final String BAD = "style.bad";
    /** The title colour of a window that destroys something. */
    public static final String DANGER = "style.danger";
    /** The near end of the gradient a plugin's tag is drawn in. */
    public static final String BRAND_FROM = "style.brand-from";
    /** The far end. */
    public static final String BRAND_TO = "style.brand-to";
    /** Which whole look is in use. */
    public static final String PRESET = "style.preset";

    /**
     * How a setting key becomes a value, or {@code null} for "not set, use the preset".
     *
     * <p>A plain {@code UnaryOperator<String>} rather than an interface of its own: the only thing
     * the palette needs from a configuration system is that one question, and asking for less means
     * a plugin with a config of any shape can answer it in a lambda.
     */
    private static volatile UnaryOperator<String> source = key -> null;

    private Style() {
    }

    /**
     * Installed once, at startup, by {@code RainsCorePlugin}.
     *
     * @param settings answers a key like {@code style.item-name}; null or blank means "use the preset"
     */
    public static void configure(UnaryOperator<String> settings) {
        if (settings != null) {
            source = settings;
        }
    }

    /** The whole look currently chosen. */
    public static Preset preset() {
        return Preset.byId(text(PRESET));
    }

    public static String titleLabel() {
        return colour(TITLE_LABEL);
    }

    public static String titleValue() {
        return colour(TITLE_VALUE);
    }

    public static String itemName() {
        return colour(ITEM_NAME);
    }

    public static String itemLore() {
        return colour(ITEM_LORE);
    }

    public static String ok() {
        return colour(OK);
    }

    public static String warn() {
        return colour(WARN);
    }

    public static String bad() {
        return colour(BAD);
    }

    /** The near end of the gradient a plugin's tag is drawn in. */
    public static String brandFrom() {
        return colour(BRAND_FROM);
    }

    public static String brandTo() {
        return colour(BRAND_TO);
    }

    /**
     * The title of a window that is about to destroy something.
     *
     * <p>Darker than {@link #bad()} on purpose: a refusal in chat and a delete confirmation are not
     * the same weight, and the claims module already drew the distinction by hand.
     */
    public static String danger() {
        return colour(DANGER);
    }

    /** What separates a title's label from its value, e.g. {@code ▸}. */
    public static String separator() {
        String configured = text(TITLE_SEPARATOR);
        return configured == null || configured.isBlank() ? "▸" : configured.trim();
    }

    /**
     * A window title as a trail: everything but the last part is context, the last part is the page
     * you are looking at.
     *
     * <p>This is the shape the claims windows already had, in both directions — {@code Claim ▸ base}
     * and {@code base ▸ Details} — which only makes sense once you see that the white part is always
     * the <em>last</em> one. Written down here, that is a rule; written out in fifty-four methods, it
     * was a coincidence that three of them had already broken.
     *
     * <p>The parts are not escaped: every caller passes something the server owns — a claim name, a
     * home name, a player name — all validated where they are created, and a title is drawn once into
     * a frame rather than re-parsed.
     */
    public static String title(String... crumbs) {
        if (crumbs == null || crumbs.length == 0) {
            return "";
        }
        StringBuilder built = new StringBuilder("<").append(titleLabel()).append('>');
        for (int index = 0; index < crumbs.length; index++) {
            if (index > 0) {
                built.append(' ').append(separator()).append(' ');
            }
            if (index == crumbs.length - 1 && crumbs.length > 1) {
                built.append('<').append(titleValue()).append('>');
            }
            built.append(crumbs[index]);
        }
        return built.toString();
    }

    /**
     * One colour: what the server set, or — when that is empty — what the chosen preset says.
     *
     * <p>Empty meaning "follow the preset" is what makes both halves useful at once. A server owner
     * picks a look and is done; the one who wants that look with a different lore colour fills in one
     * line and leaves the other ten alone.
     *
     * <p>Anything that is not a colour is refused rather than passed to MiniMessage: a misspelt tag
     * there would not fail loudly, it would sit in the title as text for everybody to read.
     */
    private static String colour(String key) {
        String fallback = preset().colourFor(key);
        String configured = text(key);
        if (configured == null || configured.isBlank()) {
            return fallback;
        }
        String cleaned = configured.trim().toLowerCase(Locale.ROOT);
        if (cleaned.matches("#[0-9a-f]{6}") || cleaned.matches("[a-z_]{3,20}")) {
            return cleaned;
        }
        return fallback;
    }

    /**
     * One raw setting value.
     *
     * <p>A configuration that throws must not take the colour of every window with it: a palette
     * that falls back to the default is a server that looks plain, one that throws from a title
     * builder is a server where no window opens at all.
     */
    private static String text(String key) {
        try {
            return source.apply(key);
        } catch (RuntimeException broken) {
            return null;
        }
    }
}
