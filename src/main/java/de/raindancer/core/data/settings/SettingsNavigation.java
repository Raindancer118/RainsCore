package de.raindancer.core.data.settings;

import de.raindancer.core.ui.identity.Symbols;

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
        /** Flipped or advanced on the spot. */
        CYCLED,
        /** It needs a value typed — a number, some text, a list. */
        NEEDS_TYPING,
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
     * Whether clicking this setting can change it, or whether it has to be typed.
     *
     * <p>A flag and a choice have an obvious next value; a number does not — up by one or by a
     * hundred? — and a piece of text has nowhere to go at all.
     */
    public boolean canCycle(Setting<?> setting) {
        return setting != null
                && (setting.type() == Boolean.class || !setting.choices().isEmpty());
    }

    /** Clicks a setting: flips it, or says it needs typing. */
    public Click click(String key) {
        Optional<Setting<?>> setting = registry.setting(key);
        if (setting.isEmpty()) {
            return Click.UNKNOWN;
        }
        if (!canCycle(setting.get())) {
            return Click.NEEDS_TYPING;
        }
        registry.cycle(key);
        return Click.CYCLED;
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

        if (!setting.choices().isEmpty()) {
            lines.add("<gray>One of: <white>" + String.join(", ", setting.choices()));
        }
        if (setting.min() != null) {
            lines.add("<gray>From <white>" + setting.min() + "<gray> to <white>" + setting.max());
        }

        registry.storeOf(setting.key()).ifPresent(store ->
                lines.add("<dark_gray>from " + store.schema().id()));

        lines.add("");
        lines.add(canCycle(setting)
                ? "<yellow>" + Symbols.ARROW + " Click to change"
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
