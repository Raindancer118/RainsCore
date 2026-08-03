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

    /** What the host says it is called; see prefixFrom. Null means read the prefix key. */
    private volatile java.util.function.Supplier<String> prefixSource;

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

    /**
     * Adds the keys this version introduced to a file that predates them, and changes nothing else.
     *
     * <p>{@link #writeIfMissing} is right the first time and useless every time after: a server that
     * upgrades across a release keeps a file that simply does not mention the new messages. Nothing
     * breaks, because every read falls back to the wording in the jar, but an owner opening the file
     * cannot see the new lines, let alone reword them. After one release here that was over a hundred.
     *
     * <p>The rules, each of which is a way this could go wrong:
     * <ul>
     *   <li><b>An existing key is never touched</b>, whatever its value. Somebody who set a message to
     *       an empty string wanted silence, and filling it back in is the merge undoing a decision.</li>
     *   <li><b>A key the jar no longer has is left alone.</b> It may be a leftover, or something a fork
     *       reads. It is reported through {@link #problems()}, never removed.</li>
     *   <li><b>Nothing is written when nothing is missing</b>, down to the file's timestamp.</li>
     *   <li><b>A file that will not parse is left exactly as it is.</b> Half a parse is somebody
     *       mid-edit, or a write cut short by a full disk, and rewriting from it loses the rest.</li>
     *   <li><b>The old file is copied aside first.</b> This is the one place the plugin edits something
     *       a person wrote, and being able to undo it is the difference between a merge owners accept
     *       and one they turn off.</li>
     * </ul>
     *
     * <p>The merge is done on the text rather than by re-dumping the parsed tree, which would be a few
     * lines shorter and would throw away every comment in the file along with the owner's spacing and
     * quoting style. A messages.yml is mostly comments explaining what each key does.
     *
     * @return how many keys were added; {@code -1} if there was no file and the bundled one was
     *         written whole; {@code -2} if the file could not be read and was left alone
     */
    public int mergeMissing(InputStream bundledDefaults) {
        if (file == null || bundledDefaults == null) {
            return -2;
        }
        if (!Files.isRegularFile(file)) {
            return writeIfMissing(bundledDefaults) ? -1 : -2;
        }

        Map<String, Object> fromJar = new LinkedHashMap<>();
        try (InputStream stream = bundledDefaults) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            flatten(yaml, "", fromJar);
        } catch (IOException | RuntimeException broken) {
            log.error("The bundled messages.yml could not be read; {} was left untouched.",
                    file.getFileName());
            return -2;
        }

        String text;
        Map<String, Object> owned = new LinkedHashMap<>();
        try {
            text = Files.readString(file);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(text);
            flatten(yaml, "", owned);
        } catch (Exception broken) {
            // Deliberately not repaired. Somebody is mid-edit, or the disk filled during a write, and
            // a rewrite from half a parse loses whatever is not in the half that parsed.
            log.warn("{} could not be read ({}), so no new messages were merged into it. Fix the file "
                    + "and restart; nothing was changed.", file.getFileName(), broken.getMessage());
            return -2;
        }

        List<String> added = new ArrayList<>();
        for (String key : fromJar.keySet()) {
            if (!owned.containsKey(key)) {
                added.add(key);
            }
        }
        if (added.isEmpty()) {
            return 0;
        }

        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        for (String key : added) {
            insert(lines, key, fromJar.get(key));
        }

        try {
            Path backup = file.resolveSibling(file.getFileName() + "."
                    + java.time.LocalDateTime.now().format(
                            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".bak");
            Files.copy(file, backup);
            Files.writeString(file, String.join("\n", lines));
            log.info("Added {} new message(s) to {}; your wording was kept and the previous file is "
                    + "beside it as {}.", added.size(), file.getFileName(), backup.getFileName());
        } catch (IOException failure) {
            log.warn("Could not update {} ({}); the built-in wording is used for the new messages.",
                    file.getFileName(), failure.getMessage());
            return -2;
        }
        return added.size();
    }

    /**
     * Puts one key into the file's text, under whichever part of its path already exists.
     *
     * <p>{@code claim.flag.pvp} in a file that has a {@code claim:} section but no {@code flag:} under
     * it appends {@code flag:} to the end of {@code claim:} and {@code pvp:} under that — so an added
     * key lands with its relatives rather than at the bottom under a second copy of a heading that is
     * already there. A duplicate heading is not a cosmetic problem: SnakeYAML rejects the file outright
     * on the next load, which turns "you have new messages" into "you have no messages".
     */
    private static void insert(List<String> lines, String key, Object value) {
        String[] parts = key.split("\\.");

        // The deepest ancestor that is already written, and where its last line is.
        int depth = 0;
        int after = lines.size();
        int indent = 0;
        for (int part = 1; part < parts.length; part++) {
            String ancestor = String.join(".", List.of(parts).subList(0, part));
            int header = lineOf(lines, ancestor);
            if (header < 0) {
                break;
            }
            depth = part;
            indent = indentOf(lines.get(header)) + 2;
            after = endOfSection(lines, header, indentOf(lines.get(header)));
        }

        List<String> written = new ArrayList<>();
        for (int part = depth; part < parts.length - 1; part++) {
            written.add(" ".repeat(indent) + parts[part] + ":");
            indent += 2;
        }
        written.addAll(render(" ".repeat(indent), parts[parts.length - 1], value));
        lines.addAll(after, written);
    }

    /** The line index of a dotted path's own line, or {@code -1}. */
    private static int lineOf(List<String> lines, String path) {
        String[] parts = path.split("\\.");
        int at = 0;
        int depth = 0;
        int inside = -1;
        for (int line = 0; line < lines.size(); line++) {
            String text = lines.get(line);
            if (text.isBlank() || text.stripLeading().startsWith("#")) {
                continue;
            }
            int indent = indentOf(text);
            if (indent != depth * 2) {
                if (indent < depth * 2 && at > 0) {
                    return -1;      // the section we were in ended without the key
                }
                continue;
            }
            String name = text.strip();
            int colon = name.indexOf(':');
            if (colon < 0) {
                continue;
            }
            if (!name.substring(0, colon).strip().equals(parts[at])) {
                continue;
            }
            inside = line;
            at++;
            if (at == parts.length) {
                return inside;
            }
            depth++;
        }
        return -1;
    }

    /** One past the last line belonging to the section whose header is at {@code header}. */
    private static int endOfSection(List<String> lines, int header, int headerIndent) {
        int end = header + 1;
        for (int line = header + 1; line < lines.size(); line++) {
            String text = lines.get(line);
            if (text.isBlank()) {
                continue;       // trailing blanks belong to whatever comes next, not to this section
            }
            if (indentOf(text) <= headerIndent) {
                break;
            }
            end = line + 1;
        }
        return end;
    }

    private static int indentOf(String line) {
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') {
            spaces++;
        }
        return spaces;
    }

    /** A key and its value as YAML lines, quoted the way the bundled file quotes things. */
    private static List<String> render(String indent, String name, Object value) {
        if (value instanceof List<?> items) {
            List<String> out = new ArrayList<>();
            out.add(indent + name + ":");
            for (Object item : items) {
                out.add(indent + "  - " + quoted(item));
            }
            return out;
        }
        return List.of(indent + name + ": " + quoted(value));
    }

    private static String quoted(Object value) {
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        return "\"" + String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
        return render(prefix() + fill(raw(key), values));
    }

    /**
     * Tells this server what it is called, so every message says so.
     *
     * <p>Without it, {@code prefixed()} reads the {@code prefix} message key — of which there is exactly one on
     * a server. RainsCore's own bundled wording defines <code>[Core]</code>, a module's wording arrives as a
     * <em>floor</em>, and the bundled file sits above a floor. So every message a module sent went out as
     * <code>[Core]</code> and the gradient tag it was branded with, already on every window title, was nowhere
     * in chat.
     *
     * <p>Usually {@code brand::chatPrefix}. Asked every time rather than copied, so a server renaming itself
     * does not need a restart to be believed — and because {@link de.raindancer.core.ui.chat.Brand} decides for
     * itself whether the tag is shown at all.
     *
     * <p>One identity per server, whether its features arrive as one plugin or as six modules: a player does not
     * care which jar a line came from. {@code null} puts it back to reading the key.
     */
    public void prefixFrom(java.util.function.Supplier<String> source) {
        this.prefixSource = source;
    }

    /**
     * The tag to put in front of a message.
     *
     * <p>A source that throws costs the prefix and nothing else. Losing the message with it would be the
     * framework swallowing somebody's command output over a decoration.
     */
    private String prefix() {
        java.util.function.Supplier<String> source = prefixSource;
        if (source != null) {
            try {
                String given = source.get();
                return given == null ? "" : given;
            } catch (RuntimeException noBrandYet) {
                log.debug("The message prefix source failed ({}); sending unprefixed.",
                        noBrandYet.getMessage());
                return "";
            }
        }
        return has(PREFIX_KEY) ? raw(PREFIX_KEY) : "";
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
     * Takes a module's own bundled wording as a floor.
     *
     * <p>A module is not a plugin: it has no data folder and no {@code messages.yml} on disk, it ships its
     * wording inside its jar and runs on whichever {@code Messages} its host owns. This is how that wording
     * gets in. Every key lands at {@link #define} level, which means the owner's file wins, the host's own
     * bundled file wins, and two modules that name the same key do not fight — the first one keeps it.
     *
     * <p>Skipping this is not a subtle failure: every key the module uses comes back as the key itself.
     * The claims module shipped a full {@code messages.yml} and nothing that read it, and {@code /claim}
     * answered {@code claim.nonehere}.
     *
     * @param bundled the module's {@code messages.yml}, from {@code getResourceAsStream} — closed here.
     *                {@code null} (a file that is not in the jar) is nothing to do rather than a crash
     * @return how many keys were taken up
     */
    public int defineFrom(InputStream bundled) {
        if (bundled == null) {
            return 0;
        }
        Map<String, Object> wording = new LinkedHashMap<>();
        try (InputStream stream = bundled) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            flatten(yaml, "", wording);
        } catch (IOException | RuntimeException broken) {
            // The module's bug, and it costs that module its wording rather than the server its start.
            problems.add("a module's bundled messages could not be read (" + broken.getMessage() + ")");
            log.warn("A module's bundled messages could not be read ({}); its messages will show their "
                    + "keys. This is a fault in that module, not in your configuration.",
                    broken.getMessage());
            return 0;
        }
        // First one in keeps the key, unlike define(), which is last-wins because a plugin redefining its
        // own wording means it. Between modules there is no "its own": load order decides who runs first,
        // and a module silently rewording another because of that is a bug nobody can see from either jar.
        int taken = 0;
        for (Map.Entry<String, Object> entry : wording.entrySet()) {
            if (!defined.containsKey(entry.getKey()) && define(entry.getKey(), entry.getValue())) {
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
