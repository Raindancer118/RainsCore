package de.raindancer.core.data.settings;

import de.raindancer.core.ui.identity.Symbols;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Where a settings screen goes and what it says — everything about the settings GUI that does not
 * need a server.
 *
 * <h2>Why this is separate from the menu that draws it</h2>
 * The same reason {@link de.raindancer.core.ui.menu.MenuLayout} is separate from the menu: a window
 * cannot be opened without a server, but every decision worth getting right here is arithmetic over
 * a tree. Which page shows what, what the trail says, whether a click changes a value or has to ask
 * for one — all of that is tested rather than clicked through, and the menu is left with nothing but
 * turning it into buttons.
 */
public final class SettingsNavigation {

    /** How much of the trail fits in a window title before it starts running off the edge. */
    private static final int TRAIL_LENGTH = 3;

    /** What clicking a setting did. */
    public enum Click {
        /** Flipped on the spot — a flag, and nothing else. */
        CYCLED,
        /** It needs a value typed: free text, or a number with no range to show. */
        NEEDS_TYPING,
        /** It needs one of a named set picked — an enum, out of a page listing every answer. */
        NEEDS_OPTION_CHOICE,
        /** It needs a number picked out of its own range, rather than typed into chat. */
        NEEDS_NUMBER_CHOICE,
        /** It needs a block or item picked, out of Core's own chooser rather than typed by hand. */
        NEEDS_MATERIAL_CHOICE,
        /** It needs a colour picked from Core's swatch grid, rather than cycled or typed. */
        NEEDS_COLOR_CHOICE,
        /** Nothing answers to that key. */
        UNKNOWN
    }

    private final SettingsRegistry registry;

    public SettingsNavigation(SettingsRegistry registry) {
        this.registry = registry;
    }

    /**
     * The page at a path.
     *
     * @param path null for the front page; an unknown path also gives the front page, because a
     *             window that will not open is worse than one that opens somewhere sensible
     */
    public SettingsPage page(String path) {
        SettingsTopics topics = registry.topics();
        if (path == null || path.isBlank()) {
            return new SettingsPage(null, "Settings", topics.visibleRoots(), List.of(), List.of());
        }
        Optional<SettingsTopic> found = topics.at(path);
        if (found.isEmpty()) {
            return page(null);
        }
        SettingsTopic topic = found.get();
        return new SettingsPage(topic.path(), topic.title(), topic.visibleChildren(),
                topic.settings(), trailTo(topic));
    }

    /** The names from the root inwards, shortened to what a window title can hold. */
    private static List<String> trailTo(SettingsTopic topic) {
        List<String> names = new ArrayList<>();
        for (SettingsTopic above = topic; above != null; above = above.parent()) {
            names.addFirst(above.title());
        }
        return SettingsPage.shortenTrail(names, TRAIL_LENGTH);
    }

    // ---------------------------------------------------------------------------- clicking

    /**
     * Whether clicking this setting can change it, or whether it has to be typed or chosen.
     *
     * <p>A flag and a small, named choice have an obvious next value; a number does not — up by one or
     * by a hundred? — and a piece of text has nowhere to go at all.
     *
     * <p>{@link Material} and {@link NamedTextColor} are deliberately excluded even though
     * {@link Setting#choices()} lists every value each can be — that list exists so a hand-edited
     * {@code config.yml} and {@code /settings set}'s tab-completion show what is valid, not because
     * cycling through several hundred materials, or even sixteen colours, one click at a time is a
     * reasonable way to choose one. See {@link Click#NEEDS_MATERIAL_CHOICE} and
     * {@link Click#NEEDS_COLOR_CHOICE}.
     */
    public boolean canCycle(Setting<?> setting) {
        return setting != null && setting.type() == Boolean.class;
    }

    /** Whether this is one of a named set — an enum — with a page of answers to pick from. */
    public boolean hasOptions(Setting<?> setting) {
        return setting != null && !setting.choices().isEmpty()
                && setting.type() != Boolean.class
                && setting.type() != Material.class
                && setting.type() != NamedTextColor.class;
    }

    /** Whether this is a number with both ends of its range known, so it can be picked rather than typed. */
    public boolean hasRange(Setting<?> setting) {
        return setting != null && setting.min() != null && setting.max() != null
                && (setting.type() == Integer.class || setting.type() == int.class);
    }

    /** Clicks a setting: flips it, opens a chooser, or says it needs typing. */
    public Click click(String key) {
        Optional<Setting<?>> setting = registry.setting(key);
        if (setting.isEmpty()) {
            return Click.UNKNOWN;
        }
        if (canCycle(setting.get())) {
            registry.cycle(key);
            return Click.CYCLED;
        }
        // A named set is picked out of a list, never advanced one click at a time. Cycling made the
        // lore the only place that said where you had landed, and overshooting the answer you wanted
        // meant going all the way round again — on a five-value enum that is four clicks to undo one.
        if (hasOptions(setting.get())) {
            return Click.NEEDS_OPTION_CHOICE;
        }
        // And a number with both ends known is picked out of its own range: typing one into chat
        // closes the window, and nothing about "40000" is easier to get right by hand.
        if (hasRange(setting.get())) {
            return Click.NEEDS_NUMBER_CHOICE;
        }
        // A block or an item is exactly what Core's own creative-inventory chooser is for. Typed by
        // hand, this is where a server ended up with a wall built from "gray_candle" — a name that
        // parses and is wrong, discovered only by looking at the wall rather than at the setting.
        if (setting.get().type() == Material.class) {
            return Click.NEEDS_MATERIAL_CHOICE;
        }
        if (setting.get().type() == NamedTextColor.class) {
            return Click.NEEDS_COLOR_CHOICE;
        }
        return Click.NEEDS_TYPING;
    }

    // ---------------------------------------------------------------------------- describing

    /**
     * The lines under a setting's name: what it does, what it is, and how to change it.
     *
     * <p>MiniMessage, so the menu can hand them straight to an icon. The last line is always what to
     * do about it — a button that shows a value without saying how to change it is a button people
     * click hopefully.
     */
    public List<String> describe(Setting<?> setting) {
        List<String> lines = new ArrayList<>();
        if (setting == null) {
            return lines;
        }
        if (!setting.description().isBlank()) {
            lines.add("<gray>" + setting.description());
        }
        lines.add("");
        lines.add("<gray>Now: <white>" + registry.display(setting.key()));

        // Every material name this server has is hundreds of entries — useful in a hand-edited
        // config.yml, where it says what is valid, and unreadable as a line of lore on a button whose
        // whole point is now "click it and pick one visually" instead.
        if (!setting.choices().isEmpty() && setting.type() != Material.class) {
            lines.add("<gray>One of: <white>" + String.join(", ", setting.choices()));
        }
        if (setting.min() != null) {
            lines.add("<gray>From <white>" + setting.min() + "<gray> to <white>" + setting.max());
        }

        registry.storeOf(setting.key()).ifPresent(store ->
                lines.add("<dark_gray>from " + store.schema().id()));

        lines.add("");
        // "Type" is the last resort now, and it says so only where it is true: free text, and a
        // number with no range to draw a picker from.
        boolean picked = hasOptions(setting) || hasRange(setting)
                || setting.type() == Material.class || setting.type() == NamedTextColor.class;
        lines.add(canCycle(setting)
                ? "<yellow>" + Symbols.ARROW + " Click to change"
                : picked
                        ? "<yellow>" + Symbols.ARROW + " Click to choose"
                        : "<yellow>" + Symbols.ARROW + " Click to type a new value");
        return lines;
    }

    /** What a category's button says. */
    public List<String> describe(SettingsTopic topic) {
        List<String> lines = new ArrayList<>();
        if (topic == null) {
            return lines;
        }
        if (!topic.description().isBlank()) {
            lines.add("<gray>" + topic.description());
        }
        int settings = topic.allSettings().size();
        lines.add("");
        lines.add("<gray>" + settings + (settings == 1 ? " setting" : " settings"));
        lines.add("<yellow>" + Symbols.ARROW + " Click to open");
        return lines;
    }

    public SettingsRegistry registry() {
        return registry;
    }
}
