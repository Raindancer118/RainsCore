package de.raindancer.core.ui.messages;

import net.kyori.adventure.audience.Audience;
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

    /**
     * The four places a message can come from, in the order they beat each other.
     *
     * <p>Bottom to top: what the jar shipped, what a plugin supplied in code, what the owner wrote,
     * and what a plugin insists on. The middle two are the ones this class gained later, and the
     * ordering between them is the whole design:
     *
     * <ul>
     *   <li>A <b>{@link #define}</b> is a <em>default</em>, and the lowest layer of the four: it fills
     *       a key nobody else has. Both the jar and the owner's file beat it — the jar because its
     *       lines are what an owner reads to learn what they may change, and the file because
     *       somebody who edits a line has to get that line or the file is decoration.</li>
     *   <li>A <b>{@link #force}</b> beats the file. For the few texts that must not be freely
     *       editable, and for switching wording at runtime. Rare on purpose: every use of it is a
     *       line in the owner's file that silently does nothing.</li>
     * </ul>
     */
    /** What the owner wrote. Beats the jar and anything a plugin merely suggested. */
    private volatile Map<String, Object> theirs = Map.of();
    /** What the plugin shipped in its jar. The floor: nothing is ever missing from here. */
    private volatile Map<String, Object> shipped = Map.of();
    /** Defaults a plugin supplied in code. Below the owner's file. */
    private final Map<String, Object> defined = new java.util.concurrent.ConcurrentHashMap<>();
    /** Wording a plugin insists on. Above everything. */
    private final Map<String, Object> forced = new java.util.concurrent.ConcurrentHashMap<>();

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
        Object found = lookUp(key);
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
        String prefix = has(PREFIX_KEY) ? raw(PREFIX_KEY) : "";
        return render(prefix + fill(raw(key), values));
    }

    /**
     * Sends a prefixed message. The normal case for command feedback.
     *
     * <p>Here rather than at every call site because {@code recipient.sendMessage(messages.prefixed(key, …))}
     * is the same line in three hundred places, and the one that forgets the prefix is the one nobody notices
     * until a player asks which plugin just talked to them.
     */
    public void send(Audience recipient, String key, Object... values) {
        if (recipient != null) {
            recipient.sendMessage(prefixed(key, values));
        }
    }

    /** The same without the prefix, for the rows of a list where a prefix per line is noise. */
    public void sendPlain(Audience recipient, String key, Object... values) {
        if (recipient != null) {
            recipient.sendMessage(get(key, values));
        }
    }

    /**
     * One of several wordings for the same thing, chosen at random.
     *
     * <p>For the lines a player sees over and over — a refusal, an arrival. A key whose value is a list gets
     * one of its entries; a key with a single value behaves exactly like {@link #get}, so making a message
     * varied is editing {@code messages.yml} and changing nothing in code.
     *
     * <p>Prefixed, because the callers are all feedback. The one place this matters: a variant list with one
     * entry must not read differently from a plain key, or turning a message into a list would silently move
     * the prefix.
     */
    public Component variant(String key, Object... values) {
        Object found = lookUp(key);
        if (!(found instanceof List<?> options) || options.isEmpty()) {
            return prefixed(key, values);
        }
        Object chosen = options.size() == 1
                ? options.getFirst()
                : options.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(options.size()));
        String prefix = has(PREFIX_KEY) ? raw(PREFIX_KEY) : "";
        return render(prefix + fill(String.valueOf(chosen), values));
    }

    /** A message that is several lines — a help page, a description. */
    public List<Component> lines(String key, Object... values) {
        Object found = lookUp(key);
        if (!(found instanceof List<?> list)) {
            return List.of(get(key, values));
        }
        List<Component> rendered = new ArrayList<>(list.size());
        for (Object line : list) {
            rendered.add(render(fill(String.valueOf(line), values)));
        }
        return rendered;
    }

    /**
     * The winner among the four layers, or null when nobody has this key.
     *
     * <p>One method, so every way of reading a message agrees about precedence. The first version of
     * the override API had {@code raw} and {@code lines} each work it out, and they disagreed about
     * whether a forced value beat the file.
     */
    private Object lookUp(String key) {
        Object insisted = forced.get(key);
        if (insisted != null) {
            return insisted;
        }
        Object owner = theirs.get(key);
        if (owner != null) {
            return owner;
        }
        Object bundled = shipped.get(key);
        // The bundled file last but one, above a define rather than below it. A define is a *floor*:
        // it fills a key nobody else has. Letting it beat the jar would make every line in the
        // shipped messages.yml a suggestion the code could silently ignore — and that file is the one
        // an owner reads to find out what they may change.
        return bundled != null ? bundled : defined.get(key);
    }

    /** Whether a key is defined anywhere at all. */
    public boolean has(String key) {
        return lookUp(key) != null;
    }

    // ------------------------------------------------------------------ what a plugin can say

    /**
     * Supplies a default for one message.
     *
     * <p>Used below the owner's file: for a message this version invented, or one built at runtime.
     * If the owner has written that key, theirs is what players see.
     *
     * @param value a string, or a {@link List} of them for something several lines long
     * @return whether it was taken
     */
    public boolean define(String key, Object value) {
        if (key == null || key.isBlank() || value == null) {
            return false;
        }
        defined.put(key, value);
        return true;
    }

    /** Supplies several at once — what a plugin registering its own set of messages wants. */
    public int defineAll(Map<String, ?> values) {
        if (values == null) {
            return 0;
        }
        int taken = 0;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (define(entry.getKey(), entry.getValue())) {
                taken++;
            }
        }
        return taken;
    }

    /**
     * Insists on one message, over anything the owner wrote.
     *
     * <p>Rare on purpose. Every use is a line in somebody's {@code messages.yml} that silently does
     * nothing, and an owner who cannot see why their edit is ignored will conclude the file is
     * broken. Use {@link #define} unless the text genuinely must not be editable.
     *
     * @return whether it was taken
     */
    public boolean force(String key, Object value) {
        if (key == null || key.isBlank() || value == null) {
            return false;
        }
        forced.put(key, value);
        return true;
    }

    /**
     * Stops insisting, so the owner's file comes through again.
     *
     * @return whether anything was being insisted on
     */
    public boolean release(String key) {
        return key != null && forced.remove(key) != null;
    }

    /** Which keys a plugin is insisting on — for a page that explains why an edit does nothing. */
    public List<String> forcedKeys() {
        List<String> keys = new ArrayList<>(forced.keySet());
        keys.sort(String::compareTo);
        return keys;
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
        java.util.Set<String> all = new java.util.LinkedHashSet<>(shipped.keySet());
        all.addAll(defined.keySet());
        all.addAll(theirs.keySet());
        all.addAll(forced.keySet());
        List<String> sorted = new ArrayList<>(all);
        sorted.sort(String::compareTo);
        return sorted;
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
