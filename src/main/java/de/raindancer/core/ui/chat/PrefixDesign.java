package de.raindancer.core.ui.chat;

import de.raindancer.core.ui.text.NameStyle;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * How every plugin's chat prefix looks — one shared tag or each plugin's own, painted like a name, in
 * whatever shape the owner writes around it.
 *
 * <p>Immutable; every {@code with…} returns a changed copy, so a menu can build a design up click by
 * click and nothing half-made is ever drawn.
 *
 * @param mode    whether every plugin signs with {@link #tag} or with its own name
 * @param shown   whether there is a prefix at all
 * @param tag     the shared tag; blank means each plugin's own even in shared mode
 * @param style   how the tag is painted; {@link NameStyle#NONE} is the theme's gradient in bold
 * @param format  MiniMessage around the tag: {@code {tag}} is the painted tag, {@code {plugin}} the
 *                plugin's own name as plain text
 * @param plugins per-plugin overrides, keyed by the plugin's name in lower case
 */
public record PrefixDesign(Mode mode, boolean shown, String tag, NameStyle style, String format,
                           Map<String, PluginPrefix> plugins) {

    public static final String TAG = "{tag}";
    public static final String PLUGIN = "{plugin}";
    public static final String DEFAULT_FORMAT = "{tag} <dark_gray>»</dark_gray> ";

    public static final PrefixDesign DEFAULT =
            new PrefixDesign(Mode.PER_PLUGIN, true, "", NameStyle.NONE, DEFAULT_FORMAT, Map.of());

    public enum Mode {
        /** Each plugin signs with its own name — "Claims »", "Homes »". */
        PER_PLUGIN,
        /** Every plugin signs with the one tag — the server speaking with one voice. */
        SHARED
    }

    /**
     * One plugin's own look, used in per-plugin mode. A blank tag keeps the plugin's own name, an
     * empty style keeps the shared style.
     */
    public record PluginPrefix(String tag, NameStyle style, boolean shown) {

        public static final PluginPrefix NONE = new PluginPrefix("", NameStyle.NONE, true);

        public PluginPrefix {
            tag = tag == null ? "" : tag.strip();
            style = style == null ? NameStyle.NONE : style;
        }

        public boolean isEmpty() {
            return tag.isEmpty() && style.isEmpty() && shown;
        }
    }

    public PrefixDesign {
        mode = mode == null ? Mode.PER_PLUGIN : mode;
        tag = tag == null ? "" : tag.strip();
        style = style == null ? NameStyle.NONE : style;
        format = format == null || format.isBlank() ? DEFAULT_FORMAT
                : format.contains(TAG) ? format : TAG + " " + format.stripLeading();
        Map<String, PluginPrefix> cleaned = new LinkedHashMap<>();
        if (plugins != null) {
            plugins.forEach((name, prefix) -> {
                if (name != null && !name.isBlank() && prefix != null && !prefix.isEmpty()) {
                    cleaned.put(key(name), prefix);
                }
            });
        }
        plugins = Map.copyOf(cleaned);
    }

    static String key(String plugin) {
        return plugin == null ? "" : plugin.strip().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ answers

    /**
     * The tag {@code plugin} signs with.
     *
     * @param plugin    the plugin's stable name, which overrides are keyed by
     * @param ownTag    what the plugin's own settings call it
     */
    public String tagFor(String plugin, String ownTag) {
        if (mode == Mode.SHARED) {
            return tag.isEmpty() ? ownTag : tag;
        }
        PluginPrefix own = plugins.get(key(plugin));
        return own != null && !own.tag().isEmpty() ? own.tag() : ownTag;
    }

    public NameStyle styleFor(String plugin) {
        if (mode == Mode.PER_PLUGIN) {
            PluginPrefix own = plugins.get(key(plugin));
            if (own != null && !own.style().isEmpty()) {
                return own.style();
            }
        }
        return style;
    }

    public boolean shownFor(String plugin) {
        if (!shown) {
            return false;
        }
        PluginPrefix own = plugins.get(key(plugin));
        return own == null || own.shown();
    }

    public PluginPrefix pluginPrefix(String plugin) {
        return plugins.getOrDefault(key(plugin), PluginPrefix.NONE);
    }

    // ------------------------------------------------------------------ changes

    public PrefixDesign withMode(Mode changed) {
        return new PrefixDesign(changed, shown, tag, style, format, plugins);
    }

    public PrefixDesign withShown(boolean changed) {
        return new PrefixDesign(mode, changed, tag, style, format, plugins);
    }

    public PrefixDesign withTag(String changed) {
        return new PrefixDesign(mode, shown, changed, style, format, plugins);
    }

    public PrefixDesign withStyle(NameStyle changed) {
        return new PrefixDesign(mode, shown, tag, changed, format, plugins);
    }

    public PrefixDesign withFormat(String changed) {
        return new PrefixDesign(mode, shown, tag, style, changed, plugins);
    }

    /** {@code plugin}'s override; {@link PluginPrefix#NONE} or null removes it. */
    public PrefixDesign withPlugin(String plugin, PluginPrefix changed) {
        Map<String, PluginPrefix> next = new LinkedHashMap<>(plugins);
        if (changed == null || changed.isEmpty()) {
            next.remove(key(plugin));
        } else {
            next.put(key(plugin), changed);
        }
        return new PrefixDesign(mode, shown, tag, style, format, next);
    }
}
