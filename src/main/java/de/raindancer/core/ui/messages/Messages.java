package de.raindancer.core.ui.messages;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every message a plugin says, in a file somebody can edit.
 *
 * <h2>Why Core owns this</h2>
 * Because three plugins had written it and each got a different part wrong. It is boilerplate — but
 * with one hard rule in it: a key the owner's file does not have <b>must</b> fall back to the one the
 * plugin shipped. Get that wrong and a translation three versions old blanks out every message added
 * since, and the owner has no way of telling which of their edits did it.
 *
 * <p>Two more rules that only one of the three copies had. Anything a player typed is escaped, so a
 * home called {@code <red>} is nine characters rather than a colour change. And markup with a typo in
 * it still renders: a plugin that throws while refusing something has turned a refusal into a stack
 * trace, in front of the player it was refusing.
 *
 * <h2>Using it</h2>
 * <pre>{@code
 * Messages messages = new Messages(getDataFolder().toPath().resolve("messages.yml"));
 * messages.load(getResource("messages.yml"));
 * messages.writeIfMissing(getResource("messages.yml"));
 *
 * player.sendMessage(messages.prefixed("claimed", "blocks", 256));
 * }</pre>
 *
 * <h2>Thread safety</h2>
 * Safe to read from any thread. {@link #load} touches disk and should not be called on a timer.
 */
public final class Messages {

    private static final LogChannel log = Log.of("messages");
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** The key the prefix lives under, because every one of the three copies used this name. */
    public static final String PREFIX_KEY = "prefix";

    private final Path file;

    /** What the owner wrote. Everything they did not write falls through to the defaults. */
    private volatile Map<String, Object> theirs = Map.of();
    /** What the plugin shipped. The floor: nothing is ever missing from here. */
    private volatile Map<String, Object> shipped = Map.of();

    private final List<String> problems = new CopyOnWriteArrayList<>();
    private final List<String> missing = new CopyOnWriteArrayList<>();

    public Messages(Path file) {
        this.file = file;
    }

    // ---------------------------------------------------------------------------- loading

    /**
     * Reads the owner's file, over the bundled defaults.
     *
     * @param bundledDefaults the plugin's own {@code messages.yml}, from {@code getResource} —
     *                        closed here, so the caller does not have to
     */
    public void load(InputStream bundledDefaults) {
        problems.clear();
        missing.clear();

        Map<String, Object> defaults = new LinkedHashMap<>();
        if (bundledDefaults != null) {
            try (InputStream stream = bundledDefaults) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(stream, StandardCharsets.UTF_8));
                flatten(yaml, "", defaults);
            } catch (IOException | RuntimeException broken) {
                // The plugin's own file being unreadable is the plugin's bug, not the owner's, and
                // it leaves every message showing its key. Loud on purpose.
                problems.add("the bundled messages could not be read (" + broken.getMessage() + ")");
                log.error("The bundled messages.yml could not be read; every message will show its "
                        + "key instead. This is a fault in the plugin, not in your configuration.");
            }
        }
        shipped = Map.copyOf(defaults);

        Map<String, Object> owner = new LinkedHashMap<>();
        if (file != null && Files.isRegularFile(file)) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(Files.readString(file));
                flatten(yaml, "", owner);
            } catch (Exception broken) {
                // Their file, their mistake — and it costs them the translation rather than every
                // message the plugin has.
                problems.add(file.getFileName() + " could not be read (" + broken.getMessage()
                        + "); the built-in messages are being used");
                log.warn("{} could not be read ({}). The built-in messages are being used instead.",
                        file.getFileName(), broken.getMessage());
                owner.clear();
            }
        }
        theirs = Map.copyOf(owner);

        for (String key : shipped.keySet()) {
            if (!theirs.containsKey(key)) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty() && !theirs.isEmpty()) {
            log.info("{} is missing {} message(s) that this version added; the built-in wording is "
                    + "used for those.", file == null ? "messages.yml" : file.getFileName(),
                    missing.size());
        }
    }

    /**
     * Writes the bundled file out, if the owner does not have one.
     *
     * <p>Never over one they have. An owner who cannot see the file cannot edit it and will not know
     * it exists; an owner whose edits were overwritten will not use the plugin again.
     *
     * @return whether a file was written
     */
    public boolean writeIfMissing(InputStream bundledDefaults) {
        if (file == null || bundledDefaults == null || Files.isRegularFile(file)) {
            return false;
        }
        try (InputStream stream = bundledDefaults) {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(stream, file);
            return true;
        } catch (IOException failure) {
            log.warn("Could not write {} ({}); the built-in messages are being used.",
                    file, failure.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------------------- reading

    /**
     * One message, as it is written in the file.
     *
     * <p>A key nobody has anywhere comes back as the key itself in angle brackets, rather than as an
     * empty string: a blank in the middle of a sentence is a bug nobody can find, and the key at
     * least says which one is missing.
     */
    public String raw(String key) {
        Object mine = theirs.get(key);
        Object theirsShipped = shipped.get(key);
        Object found = mine != null ? mine : theirsShipped;
        if (found == null) {
            String problem = "no message is defined for '" + key + "'";
            if (!problems.contains(problem)) {
                problems.add(problem);
                log.warn(problem);
            }
            return "<" + key + ">";
        }
        return String.valueOf(found);
    }

    /**
     * One message, filled in and rendered.
     *
     * @param values name, value, name, value — a player's own text is escaped, always
     */
    public Component get(String key, Object... values) {
        return render(fill(raw(key), values));
    }

    /** The same, with the prefix in front. */
    public Component prefixed(String key, Object... values) {
        String prefix = theirs.containsKey(PREFIX_KEY) || shipped.containsKey(PREFIX_KEY)
                ? raw(PREFIX_KEY) : "";
        return render(prefix + fill(raw(key), values));
    }

    /** A message that is several lines — a help page, a description. */
    public List<Component> lines(String key, Object... values) {
        Object found = theirs.containsKey(key) ? theirs.get(key) : shipped.get(key);
        if (!(found instanceof List<?> list)) {
            return List.of(get(key, values));
        }
        List<Component> rendered = new ArrayList<>(list.size());
        for (Object line : list) {
            rendered.add(render(fill(String.valueOf(line), values)));
        }
        return rendered;
    }

    /** Whether a key is defined anywhere at all. */
    public boolean has(String key) {
        return theirs.containsKey(key) || shipped.containsKey(key);
    }

    /** Every key the owner's file does not have — for a line at startup, or a menu. */
    public List<String> missingFromFile() {
        return List.copyOf(missing);
    }

    /** What went wrong: an unreadable file, a key nobody defined, a bad call. */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    /** Every key there is, for tab completion or a settings page. */
    public List<String> keys() {
        List<String> all = new ArrayList<>(shipped.keySet());
        theirs.keySet().stream().filter(key -> !all.contains(key)).forEach(all::add);
        all.sort(String::compareTo);
        return all;
    }

    // ---------------------------------------------------------------------------- internals

    /**
     * Puts the values where their names are.
     *
     * <p>Escaped, every one. A value is usually something a player typed — a home's name, another
     * player's name — and pasting that into markup is how a home called {@code <rainbow>} recolours
     * the rest of the sentence.
     */
    private String fill(String message, Object... values) {
        if (values == null || values.length == 0) {
            return message;
        }
        if (values.length % 2 != 0) {
            String problem = "a message was given a placeholder name with no value";
            if (!problems.contains(problem)) {
                problems.add(problem);
                log.warn("{} ({} argument(s)); it was left as it is.", problem, values.length);
            }
            return message;
        }
        String filled = message;
        for (int at = 0; at + 1 < values.length; at += 2) {
            String name = String.valueOf(values[at]);
            String value = MINI.escapeTags(String.valueOf(values[at + 1]));
            filled = filled.replace("<" + name + ">", value);
        }
        return filled;
    }

    /**
     * Markup to something a player can see, whatever is wrong with it.
     *
     * <p>A typo in a colour name must not throw. The message still has to arrive, because the times
     * this matters are the times somebody is being told they cannot do something.
     */
    private Component render(String markup) {
        try {
            return MINI.deserialize(markup);
        } catch (RuntimeException badMarkup) {
            String problem = "a message could not be read as MiniMessage (" + badMarkup.getMessage()
                    + ")";
            if (!problems.contains(problem)) {
                problems.add(problem);
                log.warn(problem);
            }
            // The text without its markup beats nothing at all.
            return Component.text(markup.replaceAll("<[^>]*>", ""));
        }
    }

    /**
     * A nested section as flat dotted keys.
     *
     * <p>So {@code nested.deeper} works whether the owner wrote it nested or flat — which they will
     * do inconsistently, and should not have to think about.
     */
    private static void flatten(ConfigurationSection section, String prefix,
                                Map<String, Object> into) {
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof ConfigurationSection nested) {
                flatten(nested, path, into);
            } else if (value != null) {
                into.put(path, value);
            }
        }
    }
}
