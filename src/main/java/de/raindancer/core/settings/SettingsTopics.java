package de.raindancer.core.settings;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The tree a plugin's settings hang in, and the three roots every plugin shares.
 *
 * <h2>Why the roots are fixed</h2>
 * "The menu feels cluttered" was never about the number of settings — it was about not knowing where
 * to look. Nine plugins each inventing their own top-level groups gave a player nine vocabularies to
 * learn. These three are the question actually being asked when somebody opens a settings screen:
 *
 * <dl>
 *   <dt>{@code player}</dt><dd>what <em>I</em> set, for myself</dd>
 *   <dt>{@code management}</dt><dd>what I set for <em>other people</em> — my town, my claim, my crew</dd>
 *   <dt>{@code config}</dt><dd>what the <em>server owner</em> sets</dd>
 * </dl>
 *
 * A plugin declares subtopics beneath them and may not add a fourth. The refusal is deliberate and
 * loud: a root invented by one plugin is a button every other plugin's users have to read past.
 */
public final class SettingsTopics {

    /** What a player sets for themselves. */
    public static final String PLAYER = "player";
    /** What somebody running a town, a claim or a crew sets for other people. */
    public static final String MANAGEMENT = "management";
    /** What the server owner sets. */
    public static final String CONFIG = "config";

    /** The roots, in the order they are offered. Order is deliberate: nearest concern first. */
    private static final List<String> ROOTS = List.of(PLAYER, MANAGEMENT, CONFIG);

    private record RootDefaults(String title, Material icon, String description) {
    }

    private static final Map<String, RootDefaults> ROOT_DEFAULTS = Map.of(
            PLAYER, new RootDefaults("Your settings", Material.PLAYER_HEAD,
                    "What you set for yourself. Nobody else sees these."),
            MANAGEMENT, new RootDefaults("Management", Material.IRON_AXE,
                    "What you set for the people in your town, claim or crew."),
            CONFIG, new RootDefaults("Server settings", Material.REDSTONE,
                    "What the server runs on. Changing these affects everybody."));

    private final Map<String, SettingsTopic> byPath = new LinkedHashMap<>();

    SettingsTopics(List<Topic> declared, String owner) {
        for (String root : ROOTS) {
            RootDefaults defaults = ROOT_DEFAULTS.get(root);
            byPath.put(root,
                    new SettingsTopic(root, defaults.title(), defaults.icon(),
                            defaults.description(), null));
        }
        for (Topic topic : declared) {
            declare(topic, owner);
        }
    }

    /**
     * Adds one declared topic, making any missing parent on the way.
     *
     * <p>Declaring only the leaf is the common case and is allowed: a plugin with one page under
     * {@code management} should not have to restate what {@code management} is.
     */
    private void declare(Topic topic, String owner) {
        String path = normalise(topic.path());
        if (path.isEmpty()) {
            throw new IllegalArgumentException(owner + " declares a topic with no path.");
        }
        String root = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
        if (!ROOTS.contains(root)) {
            throw new IllegalArgumentException(owner + " declares the topic '" + path
                    + "', but '" + root + "' is not one of the three roots " + ROOTS
                    + ". A plugin adds subtopics under those; it does not add a root of its own — "
                    + "a fourth root is a button everybody else's users have to read past.");
        }
        if (ROOTS.contains(path)) {
            // Re-describing a root is allowed: a plugin that owns most of what is under it may well
            // have a better title for it than the generic one.
            SettingsTopic existing = byPath.get(path);
            byPath.put(path, new SettingsTopic(path, topic.title(),
                    iconOr(topic.icon(), existing.icon()), description(topic, existing), null));
            return;
        }
        if (byPath.containsKey(path)) {
            throw new IllegalArgumentException(owner + " declares the topic '" + path + "' twice.");
        }
        SettingsTopic parent = parentOf(path, owner);
        SettingsTopic node = new SettingsTopic(path, topic.title(),
                iconOr(topic.icon(), parent.icon()), topic.description(), parent);
        byPath.put(path, node);
        parent.addChild(node);
    }

    /** The topic above this path, made from its own defaults when it was never declared. */
    private SettingsTopic parentOf(String path, String owner) {
        String parentPath = path.substring(0, path.lastIndexOf('/'));
        SettingsTopic parent = byPath.get(parentPath);
        if (parent != null) {
            return parent;
        }
        // An undeclared middle: "config/limits/players" where "config/limits" was never named.
        // Making one keeps the tree whole; its title is the best guess from the path.
        SettingsTopic grandparent = parentOf(parentPath, owner);
        SettingsTopic made = new SettingsTopic(parentPath, readable(lastSegment(parentPath)),
                grandparent.icon(), "", grandparent);
        byPath.put(parentPath, made);
        grandparent.addChild(made);
        return made;
    }

    private static String description(Topic topic, SettingsTopic existing) {
        return topic.description().isBlank() ? existing.description() : topic.description();
    }

    private static Material iconOr(Material declared, Material fallback) {
        return declared == null || declared == Material.AIR ? fallback : declared;
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** {@code "fence-height"} or {@code "fenceHeight"} becomes {@code "Fence height"}. */
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

    /** All three roots, whether or not anything is under them. */
    public List<SettingsTopic> roots() {
        List<SettingsTopic> found = new ArrayList<>();
        for (String root : ROOTS) {
            found.add(byPath.get(root));
        }
        return List.copyOf(found);
    }

    /** The roots that have something under them — what the GUI actually offers. */
    public List<SettingsTopic> visibleRoots() {
        return roots().stream().filter(topic -> !topic.isEmpty()).toList();
    }

    /** One topic by path, or empty when nobody declared it. */
    public Optional<SettingsTopic> at(String path) {
        return Optional.ofNullable(byPath.get(normalise(path)));
    }

    /** Every topic, roots first, in declaration order. */
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
