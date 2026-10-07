package de.raindancer.core.ui.chat;

import de.raindancer.core.ui.identity.Identities;
import de.raindancer.core.ui.text.Gradients;
import de.raindancer.core.ui.text.NameStyle;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The prefix every plugin signs its messages with, as the server's {@link PrefixDesign} says.
 *
 * <h2>Why static</h2>
 * The same reason as {@link Style}: a {@link Brand} is made by every plugin, often before Core has read
 * its files, and threading one object through every constructor of every plugin would be the change
 * that never quite reaches the last one. Every brand asks here at the moment it draws, so a design
 * changed in the menu shows on the very next message of every plugin, without a reload.
 */
public final class Prefixes {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static volatile PrefixDesign design = PrefixDesign.DEFAULT;
    private static volatile LongSupplier clock = System::currentTimeMillis;

    /** Every plugin that has introduced itself, by lower-case key, as it first wrote its name. */
    private static final Map<String, String> known = new ConcurrentHashMap<>();

    private Prefixes() {
    }

    public static void use(PrefixDesign changed) {
        design = changed == null ? PrefixDesign.DEFAULT : changed;
    }

    public static PrefixDesign design() {
        return design;
    }

    /** For tests that want a fixed moment of a flowing gradient. */
    static void clock(LongSupplier changed) {
        clock = changed == null ? System::currentTimeMillis : changed;
    }

    /** Remembers a plugin's name, so the prefix menu can list who may be given their own look. */
    public static void introduce(String plugin) {
        if (plugin != null && !plugin.isBlank()) {
            known.putIfAbsent(PrefixDesign.key(plugin), plugin.strip());
        }
    }

    public static List<String> known() {
        return known.values().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    /** The chat prefix for {@code plugin}, as MiniMessage — empty when it is switched off. */
    public static String chatPrefix(String plugin, String ownTag) {
        PrefixDesign current = design;
        if (!current.shownFor(plugin)) {
            return "";
        }
        return current.format()
                .replace(PrefixDesign.PLUGIN, de.raindancer.core.ui.text.Text.literal(ownTag == null ? plugin : ownTag))
                .replace(PrefixDesign.TAG, paintedTag(plugin, ownTag, phase()));
    }

    /** The painted tag alone, now — for a window title. */
    public static String paintedTag(String plugin, String ownTag) {
        return paintedTag(plugin, ownTag, phase());
    }

    /** The painted tag at {@code phase} (0–1) of a flowing gradient, as MiniMessage. */
    public static String paintedTag(String plugin, String ownTag, double phase) {
        PrefixDesign current = design;
        String tag = current.tagFor(plugin, ownTag == null ? plugin : ownTag);
        NameStyle style = current.styleFor(plugin);
        if (style.isEmpty()) {
            return "<gradient:" + Style.brandFrom() + ":" + Style.brandTo() + "><bold>"
                    + de.raindancer.core.ui.text.Text.literal(tag) + "</bold></gradient>";
        }
        return MINI.serialize(Gradients.styled(tag, style, phase));
    }

    private static double phase() {
        long period = Identities.ANIMATION_PERIOD_MS;
        return (double) Math.floorMod(clock.getAsLong(), period) / period;
    }
}
