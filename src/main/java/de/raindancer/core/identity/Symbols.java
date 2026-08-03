package de.raindancer.core.identity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The characters these plugins use for the same things everywhere.
 *
 * <h2>Why a set and not just typing them</h2>
 * A tick meant "yes" in the claims menus, "done" in the pack manager and "on" in the core settings —
 * three different characters, because each was typed by hand into whichever file needed it, and two
 * of them did not render in the console. Naming them means a tick is the same tick in a menu, in
 * chat, on a sign and in a logfile, and changing one changes all of them.
 *
 * <p>Every one is a single character that exists in Minecraft's default font <em>and</em> in a
 * typical console font, which rules out most of the box-drawing and emoji ranges however nice they
 * look in an editor.
 */
public final class Symbols {

    public static final String TICK = "✔";
    public static final String CROSS = "✖";
    public static final String ARROW = "➤";
    public static final String STAR = "★";
    public static final String DOT = "•";
    public static final String CHEVRON = "»";
    public static final String HEART = "❤";
    public static final String WARNING = "⚠";
    public static final String CLOCK = "◷";
    public static final String COIN = "◈";
    public static final String LOCK = "⛨";
    public static final String HOME = "⌂";
    public static final String PIN = "⚑";
    public static final String SEPARATOR = "▸";

    private static final Map<String, String> BY_NAME = index();

    private static Map<String, String> index() {
        Map<String, String> named = new LinkedHashMap<>();
        named.put("tick", TICK);
        named.put("cross", CROSS);
        named.put("arrow", ARROW);
        named.put("star", STAR);
        named.put("dot", DOT);
        named.put("chevron", CHEVRON);
        named.put("heart", HEART);
        named.put("warning", WARNING);
        named.put("clock", CLOCK);
        named.put("coin", COIN);
        named.put("lock", LOCK);
        named.put("home", HOME);
        named.put("pin", PIN);
        named.put("separator", SEPARATOR);
        return Map.copyOf(named);
    }

    private Symbols() {
    }

    /** One symbol by name, or empty for a name nobody has heard of. */
    public static String of(String name) {
        return name == null ? "" : BY_NAME.getOrDefault(name.trim().toLowerCase(Locale.ROOT), "");
    }

    /** Every name, so a command can complete them and a menu can list them. */
    public static List<String> names() {
        return List.copyOf(BY_NAME.keySet());
    }

    /**
     * Replaces {@code :name:} placeholders in a line.
     *
     * <p>So a server owner can put a symbol in a message they typed into a config file without
     * needing a keyboard that has one. An unknown name is left exactly as it was — blanking it would
     * silently eat a time of day like {@code 12:30:00}.
     */
    public static String expand(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder built = new StringBuilder(text.length());
        int at = 0;
        while (at < text.length()) {
            int open = text.indexOf(':', at);
            int close = open < 0 ? -1 : text.indexOf(':', open + 1);
            if (open < 0 || close < 0) {
                built.append(text, at, text.length());
                break;
            }
            String name = text.substring(open + 1, close);
            String symbol = of(name);
            if (symbol.isEmpty()) {
                // Not a symbol. Copy up to and including the opening colon and carry on from there,
                // so "12:30:00" keeps both of its colons.
                built.append(text, at, open + 1);
                at = open + 1;
            } else {
                built.append(text, at, open).append(symbol);
                at = close + 1;
            }
        }
        return built.toString();
    }
}
