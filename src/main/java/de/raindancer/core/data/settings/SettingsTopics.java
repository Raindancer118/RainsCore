package de.raindancer.core.data.settings;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The tree a plugin's settings hang in.
 *
 * <h2>Why a tree, and why it is open</h2>
 * The thing that made the old menu unusable was that every setting on the server was one click deep.
 * Sixty buttons on a flat set of pages is not a menu, it is a wall — you find a setting by reading
 * all of them. The answer is <em>depth</em>: a page holds a handful of related things, a menu holds a
 * handful of pages, and any one setting is a few deliberate clicks away instead of one scan.
 *
 * <p>An earlier version of this class fixed three roots and refused a plugin that wanted a fourth.
 * That was the wrong lever. A plugin knows what its own settings are about, and forcing the ghast
 * lines to file "cruise speed" under somebody else's idea of a category makes the menu harder to
 * read, not easier. So the tree is open: a plugin brings whatever categories it likes, at whatever
 * depth, and {@link SettingsRegistry} merges the categories of every plugin into one tree.
 *
 * <h2>Well-known names</h2>
 * What is left of the old idea is a set of names RainsCore has an opinion about — {@code player},
 * {@code management}, {@code config} and a few more. A plugin that uses one gets a good title, icon
 * and description for free, and two plugins that both use it land in the same place. A plugin that
 * uses something else gets a title derived from the path. Neither is required; it is furniture, not
 * a rule.
 */
public final class SettingsTopics {

    /** What a player sets for themselves. */
    public static final String PLAYER = "player";
    /** What somebody running a town, a claim or a crew sets for other people. */
    public static final String MANAGEMENT = "management";
    /** What the server owner sets. */
    public static final String CONFIG = "config";
    /** How the plugin looks: colours, symbols, prefixes. */
    public static final String APPEARANCE = "appearance";
    /** Kicks, bans, mutes. */
    public static final String MODERATION = "moderation";
    /** Which parts of the plugin run at all. */
    public static final String MODULES = "modules";

    /** A name RainsCore knows, so a plugin using it gets a sensible button without saying so. */
    private record Known(String title, Material icon, String description) {
    }

    private static final Map<String, Known> WELL_KNOWN = Map.of(
            PLAYER, new Known("Your settings", Material.PLAYER_HEAD,
                    "What you set for yourself. Nobody else sees these."),
            MANAGEMENT, new Known("Management", Material.IRON_AXE,
                    "What you set for the people in your town, claim or crew."),
            CONFIG, new Known("Server settings", Material.REDSTONE,
                    "What the server runs on. Changing these affects everybody."),
            APPEARANCE, new Known("Appearance", Material.PAINTING,
                    "Colours, symbols and prefixes: what everything is drawn in."),
            MODERATION, new Known("Moderation", Material.IRON_AXE,
                    "Kicks, bans and mutes: what they do and what they say."),
            MODULES, new Known("Modules", Material.COMMAND_BLOCK,
                    "Which parts of the plugin run at all."));

    private final Map<String, SettingsTopic> byPath = new LinkedHashMap<>();
    private final List<SettingsTopic> roots = new ArrayList<>();

    /**
     * Whether declaring the same topic twice is a mistake or a merge.
     *
     * <p>Within one plugin it is a mistake — nobody means to write the same {@code @Topic} twice, and
     * saying so at startup beats one of them silently winning. Across plugins it is the ordinary
     * case and the entire point: claims and the ghast lines both declaring {@code config/limits} is
     * how they end up sharing a page.
     */
    private final boolean merging;

    SettingsTopics(List<Topic> declared, String owner) {
        this(declared, owner, false);
    }

    SettingsTopics(List<Topic> declared, String owner, boolean merging) {
        this.merging = merging;
        for (Topic topic : declared) {
            declare(topic, owner);
        }
    }

    /**
     * Adds one declared topic, making any missing ancestor on the way.
     *
     * <p>Declaring only the leaf is the common case and is allowed: a plugin with one page under
     * {@code config/limits} should not have to restate what {@code config} and {@code config/limits}
     * are, especially when another plugin has already said.
     */
    private void declare(Topic topic, String owner) {
        String path = normalise(topic.path());
        if (path.isEmpty()) {
            throw new IllegalArgumentException(owner + " declares a topic with no path.");
        }
        SettingsTopic existing = byPath.get(path);
        if (existing != null && existing.wasDeclared() && !merging) {
            throw new IllegalArgumentException(owner + " declares the topic '" + path + "' twice.");
        }
        if (existing != null && existing.wasDeclared()) {
            // Merging: the second plugin gets to fill in what the first left blank, and to say
            // nothing about what the first already described. Whoever bothered to write a
            // description keeps it.
            existing.describe(
                    topic.title() == null || topic.title().isBlank()
                            ? existing.title() : topic.title(),
                    iconOr(topic.icon(), existing.icon()),
                    topic.description());
            return;
        }
        if (existing != null) {
            // Made earlier as somebody's ancestor, now declared properly: keep its place in the tree
            // and its children, and take the title, icon and description it has just been given.
            existing.describe(topic.title(), iconOr(topic.icon(), existing.icon()),
                    topic.description());
            return;
        }
        make(path, topic.title(), topic.icon(), topic.description(), true);
    }

    /** Creates the node for a path, and every ancestor it does not have yet. */
    private SettingsTopic make(String path, String title, Material icon, String description,
                               boolean declared) {
        SettingsTopic parent = null;
        int slash = path.lastIndexOf('/');
        if (slash > 0) {
            String parentPath = path.substring(0, slash);
            parent = byPath.get(parentPath);
            if (parent == null) {
                parent = furnish(parentPath);
            }
        }
        Material resolved = iconOr(icon, parent == null ? Material.AIR : parent.icon());
        SettingsTopic node = new SettingsTopic(path, title, resolved, description, parent, declared);
        byPath.put(path, node);
        if (parent == null) {
            roots.add(node);
        } else {
            parent.addChild(node);
        }
        return node;
    }

    /**
     * A node nobody declared: made because something below it was.
     *
     * <p>Given the well-known furniture if its name is one RainsCore knows, and otherwise a title
     * read out of the path — {@code ghast-lines} becomes "Ghast lines", which is very often exactly
     * right and is never worse than showing the raw path.
     */
    private SettingsTopic furnish(String path) {
        Known known = WELL_KNOWN.get(lastSegment(path));
        if (known != null) {
            return make(path, known.title(), known.icon(), known.description(), false);
        }
        return make(path, readable(lastSegment(path)), Material.AIR, "", false);
    }

    private static Material iconOr(Material declared, Material fallback) {
        return declared == null || declared == Material.AIR ? fallback : declared;
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** {@code "ghast-lines"} or {@code "fenceHeight"} becomes {@code "Ghast lines"} / "Fence height". */
    static String readable(String raw) {
        StringBuilder built = new StringBuilder();
        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);
            if (character == '-' || character == '_') {
                built.append(' ');
            } else if (Character.isUpperCase(character) && index > 0) {
                built.append(' ').append(Character.toLowerCase(character));
            } else {
                built.append(character);
            }
        }
        String words = built.toString().trim();
        return words.isEmpty() ? words
                : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    static String normalise(String path) {
        return path == null ? "" : path.trim().toLowerCase(Locale.ROOT).replaceAll("^/+|/+$", "");
    }

    // -------------------------------------------------------------------------- reading

    /** The top-level categories this plugin brought, in the order they were first needed. */
    public List<SettingsTopic> roots() {
        return List.copyOf(roots);
    }

    /** The roots that have something under them — what a menu actually offers. */
    public List<SettingsTopic> visibleRoots() {
        return roots.stream().filter(topic -> !topic.isEmpty()).toList();
    }

    /** One topic by path, or empty when nobody declared or implied it. */
    public Optional<SettingsTopic> at(String path) {
        return Optional.ofNullable(byPath.get(normalise(path)));
    }

    /** Every topic, in the order the nodes were created. */
    public List<SettingsTopic> all() {
        return List.copyOf(byPath.values());
    }

    boolean has(String path) {
        return byPath.containsKey(normalise(path));
    }

    void file(Setting<?> setting, String owner) {
        SettingsTopic topic = byPath.get(setting.topicPath());
        if (topic == null) {
            throw new IllegalArgumentException(owner + " files '" + setting.key()
                    + "' under the topic '" + setting.topicPath()
                    + "', which no @Topic declares. Declared: " + byPath.keySet());
        }
        topic.addSetting(setting);
    }
}
