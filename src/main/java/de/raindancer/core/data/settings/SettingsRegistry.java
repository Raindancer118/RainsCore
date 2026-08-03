package de.raindancer.core.data.settings;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every plugin's settings, merged into one tree.
 *
 * <h2>Why they merge rather than sitting side by side</h2>
 * A player opening the settings does not know which of nine jars owns the thing they are looking
 * for, and should not have to. Nine menus, one per plugin, is exactly the wall this refactor exists
 * to remove. So the categories merge: claims and the ghast lines both putting something under
 * {@code config/limits} produce one Limits page with both settings on it, and neither plugin knows
 * the other exists.
 *
 * <h2>The one thing merging cannot paper over</h2>
 * Two plugins using the same key. A command saying {@code /settings set cruise-speed 7} has to
 * reach exactly one setting, and if two answer to that name then whichever was registered first
 * wins — silently, and differently depending on load order. So clashes are <em>reported</em>
 * ({@link #clashes()}), the ambiguous short name keeps working for whichever came first, and both
 * remain reachable by their full {@code plugin:key} name. Nothing is hidden and nothing is lost.
 */
public final class SettingsRegistry {

    private static final LogChannel log = Log.of("settings");

    private final List<SettingsStore<?>> stores = new CopyOnWriteArrayList<>();

    /** Adds a plugin's settings to the combined tree. */
    public void add(SettingsStore<?> store) {
        if (store == null) {
            return;
        }
        stores.add(store);
        for (Map.Entry<String, List<String>> clash : clashes().entrySet()) {
            log.warn("The setting '{}' is declared by more than one plugin ({}). A command using "
                            + "that name alone reaches the first; use plugin:key to be sure.",
                    clash.getKey(), String.join(", ", clash.getValue()));
        }
    }

    public List<SettingsStore<?>> stores() {
        return List.copyOf(stores);
    }

    // ---------------------------------------------------------------------------- the tree

    /**
     * The combined tree, built fresh each time.
     *
     * <p>Rebuilt rather than cached because it is asked for when a menu opens — a handful of times
     * an hour — and a cache would need invalidating whenever a plugin is enabled, which is a bug
     * waiting to happen for no measurable gain.
     */
    public SettingsTopics topics() {
        List<Topic> declared = new ArrayList<>();
        for (SettingsStore<?> store : stores) {
            for (SettingsTopic topic : store.schema().topics().all()) {
                declared.add(asDeclaration(topic));
            }
        }
        SettingsTopics merged = new SettingsTopics(declared, "the combined settings", true);
        for (SettingsStore<?> store : stores) {
            for (Setting<?> setting : store.schema().settings()) {
                merged.file(setting, "the combined settings");
            }
        }
        return merged;
    }

    /**
     * One schema's topic, as something {@link SettingsTopics} can be given again.
     *
     * <p>A blank description is passed through as blank on purpose: {@code SettingsTopics} keeps the
     * description a node already has when a later declaration does not supply one, which is what
     * lets a plugin declare a shared category without blanking out the sentence another plugin
     * wrote for it.
     */
    private static Topic asDeclaration(SettingsTopic topic) {
        return new Topic() {
            @Override
            public Class<Topic> annotationType() {
                return Topic.class;
            }

            @Override
            public String path() {
                return topic.path();
            }

            @Override
            public String title() {
                return topic.title();
            }

            @Override
            public Material icon() {
                return topic.icon();
            }

            @Override
            public String description() {
                return topic.description();
            }
        };
    }

    // ---------------------------------------------------------------------------- reading

    /** Every key, short where it is unambiguous. For a command's tab completion. */
    public List<String> keys() {
        List<String> keys = new ArrayList<>();
        for (SettingsStore<?> store : stores) {
            keys.addAll(store.schema().keys());
        }
        return List.copyOf(keys);
    }

    /**
     * Which plugin owns a setting.
     *
     * @param key either the short key, or {@code plugin:key} when two plugins share a name
     */
    public Optional<SettingsStore<?>> storeOf(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        String wanted = key.trim();
        int colon = wanted.indexOf(':');
        if (colon > 0) {
            String plugin = wanted.substring(0, colon).toLowerCase(Locale.ROOT);
            String bare = wanted.substring(colon + 1);
            for (SettingsStore<?> store : stores) {
                if (store.schema().id().equals(plugin)
                        && store.schema().setting(bare).isPresent()) {
                    return Optional.of(store);
                }
            }
            return Optional.empty();
        }
        // A plain loop rather than a stream: the wildcard capture that comes out of
        // stores.stream() is a different type on every element as far as the compiler is
        // concerned, and casting it back is noisier than not using a stream at all.
        for (SettingsStore<?> store : stores) {
            if (store.schema().setting(wanted).isPresent()) {
                return Optional.of(store);
            }
        }
        return Optional.empty();
    }

    /** The setting itself, wherever it lives. */
    public Optional<Setting<?>> setting(String key) {
        return storeOf(key).flatMap(store -> store.schema().setting(bareKey(key)));
    }

    /** What it currently says, for a menu or a command. Empty when nothing answers to that key. */
    public String display(String key) {
        return storeOf(key).map(store -> store.display(bareKey(key))).orElse("");
    }

    // ---------------------------------------------------------------------------- writing

    /** Applies typed text to whichever plugin owns the setting. */
    public boolean set(String key, String raw) {
        return storeOf(key).map(store -> store.set(bareKey(key), raw)).orElse(false);
    }

    /** Flips a flag or advances a choice, wherever it lives. */
    public Object cycle(String key) {
        return storeOf(key).map(store -> store.cycle(bareKey(key))).orElse(null);
    }

    /** Puts one setting back to what its plugin shipped with. */
    public void reset(String key) {
        storeOf(key).ifPresent(store -> store.reset(bareKey(key)));
    }

    /** Writes every plugin's file. */
    public void saveAll() {
        for (SettingsStore<?> store : stores) {
            store.save();
        }
    }

    // ---------------------------------------------------------------------------- clashes

    /**
     * Keys more than one plugin declares, and who declares them.
     *
     * <p>Empty on a healthy server. Worth showing at startup and in a diagnostic command, because
     * the symptom otherwise is a command that changes the wrong plugin's setting depending on which
     * jar happened to load first.
     */
    public Map<String, List<String>> clashes() {
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        for (SettingsStore<?> store : stores) {
            for (String key : store.schema().keys()) {
                byKey.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(store.schema().id());
            }
        }
        Map<String, List<String>> clashing = new LinkedHashMap<>();
        byKey.forEach((key, plugins) -> {
            if (plugins.size() > 1) {
                clashing.put(key, List.copyOf(plugins));
            }
        });
        return Map.copyOf(clashing);
    }

    private static String bareKey(String key) {
        int colon = key == null ? -1 : key.indexOf(':');
        return colon > 0 ? key.substring(colon + 1) : key;
    }
}
