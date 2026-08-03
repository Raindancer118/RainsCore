package de.raindancer.core.data.settings;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.data.store.YamlStore;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * One plugin's settings, bound to a file.
 *
 * <h2>The snapshot</h2>
 * {@link #current()} hands back an immutable record. A caller may hold it for the length of a
 * method, and should not hold it for the length of the server: it is what the settings were when it
 * was taken. Anything that has to react to a change registers with {@link #onChange}, which is
 * cheaper and more obvious than re-reading a config object on every tick.
 *
 * <h2>What happens to a file with mistakes in it</h2>
 * A server owner edits this in a text editor, with the server off, and gets things wrong. None of
 * that may stop the server starting, and none of it may quietly reset the settings around it:
 *
 * <ul>
 *   <li>a value of the wrong type falls back to the default — that one setting, nothing else;</li>
 *   <li>a number outside its range is pulled inside it, rather than being thrown away;</li>
 *   <li>a key that is missing takes its default and is written back on the next save;</li>
 *   <li>a key nobody recognises is <em>left alone</em>, so downgrading and upgrading again does not
 *       delete a setting a newer version added;</li>
 *   <li>a file that is not YAML at all leaves every default in place.</li>
 * </ul>
 *
 * Every one of those is recorded in {@link #problems()} and logged, because a setting silently
 * ignored is a setting somebody will spend an evening on.
 *
 * <h2>Why a typed command is stricter than a file</h2>
 * {@link #set} refuses what {@link #load} would have clamped. The person typing is standing there
 * and can be told; the person who edited the file has gone to bed. See {@link SettingCodec}.
 */
public final class SettingsStore<T> {

    private static final LogChannel log = Log.of("settings");

    private final SettingsSchema<T> schema;
    private final Path file;
    private final YamlStore store;
    private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();
    private final List<String> problems = new ArrayList<>();

    private volatile T current;

    public SettingsStore(SettingsSchema<T> schema, Path file) {
        this.schema = schema;
        this.file = file;
        this.store = new YamlStore(file);
        // Usable before load(): a plugin that reads a setting while starting up gets the default
        // rather than a NullPointerException.
        this.current = schema.defaults();
    }

    public SettingsSchema<T> schema() {
        return schema;
    }

    /** The settings as they are now. Immutable; take a fresh one rather than holding this. */
    public T current() {
        return current;
    }

    /** What was wrong with the file, in the words the log used. Empty when it was clean. */
    public synchronized List<String> problems() {
        return List.copyOf(problems);
    }

    // ---------------------------------------------------------------------------- loading

    /**
     * Reads the file, or falls back to defaults when there is not one.
     *
     * <p>Also used to reload: listeners are told if anything actually changed.
     */
    public void load() {
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> found = new ArrayList<>();
        YamlConfiguration yaml = readFile(found);

        for (Setting<?> setting : schema.settings()) {
            values.put(setting.key(), valueFrom(yaml, setting, found));
        }

        synchronized (this) {
            problems.clear();
            problems.addAll(found);
        }
        for (String problem : found) {
            log.warn("{}: {}", file.getFileName(), problem);
        }
        publish(schema.instantiate(values));
    }

    /** The file as YAML, or an empty configuration when it is missing or unreadable. */
    private YamlConfiguration readFile(List<String> found) {
        YamlConfiguration yaml = store.read();
        // Not fatal on purpose. A broken file means this plugin runs on its defaults for one start,
        // which is far better than a server that will not come up at all — and the file is left
        // exactly as it is, so nothing the owner wrote is lost while they fix it.
        for (String problem : store.problems()) {
            found.add("the file " + problem + ", so every setting is at its default");
        }
        return yaml;
    }

    /** One setting's value out of the file, with everything that can be wrong with it handled. */
    private Object valueFrom(YamlConfiguration yaml, Setting<?> setting, List<String> found) {
        if (!yaml.contains(setting.key())) {
            return setting.defaultValue();
        }
        Optional<Object> parsed = SettingCodec.fromYaml(setting, yaml.get(setting.key()));
        if (parsed.isEmpty()) {
            found.add(setting.key() + " is not a " + readableType(setting) + " ('"
                    + yaml.get(setting.key()) + "'), so the default "
                    + SettingCodec.display(setting.defaultValue()) + " is used");
            return setting.defaultValue();
        }
        Object value = parsed.get();
        if (!SettingCodec.isInRange(setting, value)) {
            Object clamped = SettingCodec.clamp(setting, value);
            found.add(setting.key() + " is " + SettingCodec.display(value) + ", outside "
                    + setting.min() + "–" + setting.max() + ", so "
                    + SettingCodec.display(clamped) + " is used");
            return clamped;
        }
        return value;
    }

    // ---------------------------------------------------------------------------- saving

    /**
     * Writes every setting back, with its documentation above it.
     *
     * <p>Keys the schema does not know are kept: a server that downgrades and upgrades again must
     * not lose a setting the newer version added. That is also why this loads the existing file
     * first rather than writing a fresh one.
     */
    public void save() {
        T snapshot = current;
        // An update rather than a write: that is what keeps the unknown keys and whatever the owner
        // wrote around them. It is also atomic, so a server killed here has the old config or the
        // new one and never half of a file it needs to start.
        store.update(yaml -> {
            for (Setting<?> setting : schema.settings()) {
                yaml.set(setting.key(), SettingCodec.toYaml(setting.valueIn(snapshot)));
                yaml.setComments(setting.key(), commentFor(setting));
            }
        });
    }

    /**
     * The lines above a key in the file: what it is, what it does, and what it may be.
     *
     * <p>So the file can be edited without the source open, which is the only way most server
     * owners will ever meet these settings.
     */
    private List<String> commentFor(Setting<?> setting) {
        List<String> lines = new ArrayList<>();
        SettingsTopic topic = schema.topics().at(setting.topicPath()).orElse(null);
        if (topic != null && isFirstOf(topic, setting) && !topic.description().isBlank()) {
            lines.add("");
            lines.add("--- " + topic.title() + " ---");
            lines.add(topic.description());
        }
        lines.add(setting.title());
        if (!setting.description().isBlank()) {
            lines.add(setting.description());
        }
        if (setting.min() != null) {
            lines.add("From " + setting.min() + " to " + setting.max() + ".");
        }
        if (!setting.choices().isEmpty()) {
            lines.add("One of: " + String.join(", ", setting.choices()) + ".");
        }
        return lines;
    }

    private static boolean isFirstOf(SettingsTopic topic, Setting<?> setting) {
        List<Setting<?>> siblings = topic.settings();
        return !siblings.isEmpty() && siblings.getFirst().key().equals(setting.key());
    }

    // ---------------------------------------------------------------------------- changing

    /**
     * Applies text somebody typed.
     *
     * @return whether it was accepted; false leaves everything as it was
     */
    public boolean set(String key, String raw) {
        Setting<?> setting = schema.setting(key).orElse(null);
        if (setting == null) {
            return false;
        }
        Optional<Object> parsed = SettingCodec.fromText(setting, raw);
        // Refused rather than clamped: whoever typed this is standing there and can be told.
        if (parsed.isEmpty() || !SettingCodec.isInRange(setting, parsed.get())) {
            return false;
        }
        write(setting, parsed.get());
        return true;
    }

    /** Flips a flag or advances a choice, and answers with the value it landed on. */
    public Object cycle(String key) {
        Setting<?> setting = schema.setting(key).orElse(null);
        if (setting == null) {
            return null;
        }
        Object next = SettingCodec.cycle(setting, setting.valueIn(current));
        write(setting, next);
        return next;
    }

    /** Puts one setting back to what the plugin shipped with. */
    public void reset(String key) {
        schema.setting(key).ifPresent(setting -> write(setting, setting.defaultValue()));
    }

    /** Puts all of them back. */
    public void resetAll() {
        publish(schema.defaults());
    }

    /** What a person should see for this setting right now. Empty for a key nobody declared. */
    public String display(String key) {
        return schema.setting(key)
                .map(setting -> SettingCodec.display(setting.valueIn(current)))
                .orElse("");
    }

    private void write(Setting<?> setting, Object value) {
        Map<String, Object> values = new LinkedHashMap<>();
        T snapshot = current;
        for (Setting<?> each : schema.settings()) {
            values.put(each.key(), each.key().equals(setting.key())
                    ? value
                    : each.valueIn(snapshot));
        }
        publish(schema.instantiate(values));
    }

    // ---------------------------------------------------------------------------- listeners

    /**
     * Tells this listener whenever the settings change — by a command, by a click, or by a reload.
     *
     * <p>Only when something actually changed: setting a value to what it already was does not wake
     * anybody up, so a listener may rebuild something expensive without checking first.
     */
    public void onChange(Consumer<T> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    private void publish(T updated) {
        if (Objects.equals(current, updated)) {
            return;
        }
        current = updated;
        for (Consumer<T> listener : listeners) {
            try {
                listener.accept(updated);
            } catch (RuntimeException broken) {
                // One plugin's listener must not stop another's, and must not stop the save that
                // usually follows a change.
                log.error(broken, "A settings listener for {} threw.", schema.id());
            }
        }
    }

    private static String readableType(Setting<?> setting) {
        Class<?> type = setting.type();
        if (type == Boolean.class) {
            return "true/false value";
        }
        if (type == Integer.class || type == Long.class) {
            return "whole number";
        }
        if (type == Double.class) {
            return "number";
        }
        if (type == List.class) {
            return "list";
        }
        if (!setting.choices().isEmpty()) {
            return "one of " + String.join(", ", setting.choices());
        }
        return type.getSimpleName();
    }
}
