package de.raindancer.core.identity;

import de.raindancer.core.log.Log;
import de.raindancer.core.log.LogChannel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Who a player is, as everybody else sees them.
 *
 * <h2>Why chat and the nametag are separate</h2>
 * They look like the same thing and they are not. A chat line is a component the server builds and
 * can make as long as it likes; a nametag is drawn by the client above a moving head, has no room
 * for a sentence, and on a vanilla client is one line. So a player carries a rank prefix in both, a
 * decorative suffix in chat only, and something short above their head — and the two are set
 * independently, with the nametag falling back to the chat prefix when nobody has bothered.
 *
 * <h2>Why prefixes are stored as MiniMessage and names never are</h2>
 * A prefix is something an administrator wrote, and being able to colour it is the entire point, so
 * it is stored as markup and parsed. A player's <em>name</em> is not: it is inserted as plain text,
 * so somebody calling themselves {@code <red>} shows up as nine characters instead of recolouring
 * everybody's chat. That distinction is the one thing in this class worth getting right.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread. Identities are a {@link ConcurrentHashMap} and a flush takes a snapshot.
 */
public final class Identities {

    private static final LogChannel log = Log.of("identity");
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    /**
     * How long a nametag may be, in characters.
     *
     * <p>The client draws it over the world, above a head that moves. Past about this it stops being
     * a label and starts being a banner obscuring whatever is behind the player.
     */
    public static final int MAX_NAMETAG_CHARS = 32;

    /** How long any one piece of an identity may be, as typed. */
    private static final int MAX_STORED_CHARS = 128;

    /** Everything one player carries. Immutable; changed by replacing it. */
    private record Identity(String prefix, String suffix, String nametagPrefix, String colour,
                            String subtitle) {

        static final Identity BLANK = new Identity("", "", "", "", "");

        boolean isBlank() {
            return prefix.isEmpty() && suffix.isEmpty() && nametagPrefix.isEmpty()
                    && colour.isEmpty() && subtitle.isEmpty();
        }
    }

    private final Path file;
    private final Map<UUID, Identity> identities = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();

    public Identities(Path file) {
        this.file = file;
    }

    // ---------------------------------------------------------------------------- reading

    /**
     * A player's name as it appears in chat: prefix, the name in their colour, then suffix.
     *
     * @param name the player's actual name, inserted as text and never parsed
     */
    public Component chatName(UUID player, String name) {
        Identity identity = identityOf(player);
        Component built = Component.empty();
        if (!identity.prefix().isEmpty()) {
            built = built.append(parse(identity.prefix()));
        }
        built = built.append(colouredName(name, identity.colour()));
        if (!identity.suffix().isEmpty()) {
            built = built.append(parse(identity.suffix()));
        }
        return built;
    }

    /**
     * What floats above their head: the nametag prefix, or the chat prefix when there is none, then
     * the name — clipped to something that fits over a moving player.
     */
    public Component nametag(UUID player, String name) {
        Identity identity = identityOf(player);
        String prefix = identity.nametagPrefix().isEmpty()
                ? identity.prefix()
                : identity.nametagPrefix();
        Component built = prefix.isEmpty() ? Component.empty() : parse(prefix);
        built = built.append(colouredName(name, identity.colour()));
        return clip(built);
    }

    /** The second line under a nametag, for whoever is drawing one. */
    public Optional<Component> subtitle(UUID player) {
        String subtitle = identityOf(player).subtitle();
        return subtitle.isEmpty() ? Optional.empty() : Optional.of(parse(subtitle));
    }

    public Optional<String> prefix(UUID player) {
        return notEmpty(identityOf(player).prefix());
    }

    public Optional<String> suffix(UUID player) {
        return notEmpty(identityOf(player).suffix());
    }

    public Optional<String> nametagPrefix(UUID player) {
        return notEmpty(identityOf(player).nametagPrefix());
    }

    public Optional<String> colour(UUID player) {
        return notEmpty(identityOf(player).colour());
    }

    /** Everybody who has anything set. */
    public Set<UUID> known() {
        return Set.copyOf(identities.keySet());
    }

    // ---------------------------------------------------------------------------- writing

    /**
     * Sets the chat prefix. Null or blank clears it.
     *
     * @return whether it was accepted; false means it would not parse, or was absurdly long
     */
    public boolean setPrefix(UUID player, String miniMessage) {
        return change(player, miniMessage, (identity, value) -> new Identity(value,
                identity.suffix(), identity.nametagPrefix(), identity.colour(),
                identity.subtitle()));
    }

    public boolean setSuffix(UUID player, String miniMessage) {
        return change(player, miniMessage, (identity, value) -> new Identity(identity.prefix(),
                value, identity.nametagPrefix(), identity.colour(), identity.subtitle()));
    }

    public boolean setNametagPrefix(UUID player, String miniMessage) {
        return change(player, miniMessage, (identity, value) -> new Identity(identity.prefix(),
                identity.suffix(), value, identity.colour(), identity.subtitle()));
    }

    /** The colour their name is drawn in — a MiniMessage colour name or a hex code. */
    public boolean setColour(UUID player, String colour) {
        if (colour != null && !colour.isBlank() && !isColour(colour)) {
            return false;
        }
        return change(player, colour, (identity, value) -> new Identity(identity.prefix(),
                identity.suffix(), identity.nametagPrefix(), value, identity.subtitle()));
    }

    /** The second line under their nametag. */
    public boolean setSubtitle(UUID player, String miniMessage) {
        return change(player, miniMessage, (identity, value) -> new Identity(identity.prefix(),
                identity.suffix(), identity.nametagPrefix(), identity.colour(), value));
    }

    /** Forgets everything about a player. */
    public void clear(UUID player) {
        if (player != null && identities.remove(player) != null) {
            dirty.set(true);
        }
    }

    private boolean change(UUID player, String raw,
                           java.util.function.BiFunction<Identity, String, Identity> update) {
        if (player == null) {
            return false;
        }
        // Deliberately not trimmed: the trailing space in "<gold>[Admin] " is what separates the
        // prefix from the name, and trimming it — which the first version of this did — glued every
        // rank to every player's name.
        String value = raw == null ? "" : raw;
        if (value.isBlank()) {
            value = "";
        }
        if (value.length() > MAX_STORED_CHARS) {
            return false;
        }
        if (!value.isEmpty() && !isUsableMarkup(value)) {
            return false;
        }
        Identity updated = update.apply(identityOf(player), value);
        if (updated.isBlank()) {
            identities.remove(player);
        } else {
            identities.put(player, updated);
        }
        dirty.set(true);
        return true;
    }

    // ---------------------------------------------------------------------------- the file

    public boolean isDirty() {
        return dirty.get();
    }

    public void load() {
        identities.clear();
        if (!Files.isRegularFile(file)) {
            dirty.set(false);
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file));
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException failure) {
            log.error(failure, "Could not read {}; nobody has a prefix this session.", file);
            return;
        }
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section == null) {
            dirty.set(false);
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) {
                continue;
            }
            try {
                Identity identity = new Identity(
                        entry.getString("prefix", ""),
                        entry.getString("suffix", ""),
                        entry.getString("nametag-prefix", ""),
                        entry.getString("colour", ""),
                        entry.getString("subtitle", ""));
                if (!identity.isBlank()) {
                    identities.put(UUID.fromString(id), identity);
                }
            } catch (RuntimeException broken) {
                // One unreadable player is one player without a prefix, not a file nobody can load.
                log.warn("{}: '{}' could not be read and was skipped ({})",
                        file.getFileName(), id, broken.getMessage());
            }
        }
        dirty.set(false);
    }

    /** Writes, if anything changed. Via a temporary file, so a kill mid-write cannot truncate it. */
    public void flush() {
        if (!dirty.compareAndSet(true, false)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        identities.forEach((player, identity) -> {
            String path = "players." + player + ".";
            put(yaml, path + "prefix", identity.prefix());
            put(yaml, path + "suffix", identity.suffix());
            put(yaml, path + "nametag-prefix", identity.nametagPrefix());
            put(yaml, path + "colour", identity.colour());
            put(yaml, path + "subtitle", identity.subtitle());
        });
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = file.resolveSibling(file.getFileName() + ".writing");
            Files.writeString(temporary, yaml.saveToString());
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            dirty.set(true);
            log.error(failure, "Could not write {}", file);
        }
    }

    private static void put(YamlConfiguration yaml, String path, String value) {
        if (!value.isEmpty()) {
            yaml.set(path, value);
        }
    }

    // ---------------------------------------------------------------------------- internals

    private Identity identityOf(UUID player) {
        return player == null ? Identity.BLANK : identities.getOrDefault(player, Identity.BLANK);
    }

    /**
     * The player's name, in their colour, as text.
     *
     * <p>{@link Component#text} rather than MiniMessage, deliberately and permanently: a name is not
     * markup, and parsing it is how a player called {@code <rainbow>} recolours everybody's chat.
     */
    private static Component colouredName(String name, String colour) {
        Component text = Component.text(name == null ? "" : name);
        if (colour.isEmpty()) {
            return text;
        }
        net.kyori.adventure.text.format.TextColor parsed = colour.startsWith("#")
                ? net.kyori.adventure.text.format.TextColor.fromHexString(colour)
                : net.kyori.adventure.text.format.NamedTextColor.NAMES.value(colour);
        return parsed == null ? text : text.color(parsed);
    }

    private static Component parse(String miniMessage) {
        try {
            return MINI.deserialize(miniMessage);
        } catch (RuntimeException broken) {
            // Stored values are checked on the way in, so this is a file edited by hand. One bad
            // prefix must not stop the player being named at all.
            return Component.text(miniMessage);
        }
    }

    /**
     * Whether this is markup MiniMessage actually understands.
     *
     * <p>Not simply "does it parse": MiniMessage does not throw on a tag it has never heard of, it
     * renders it as text — so {@code <notatag>[Oops] } would be stored happily and then appear
     * literally in front of the player's name for ever. And strict mode is no use either, because it
     * insists every tag be closed, which would reject the perfectly ordinary {@code <gold>[Admin] }.
     *
     * <p>So: parse it, and see whether anything that looks like a tag survived into the rendered
     * text. If it did, MiniMessage did not recognise it, and whoever typed it should be told now
     * rather than discovering it in chat.
     */
    private static boolean isUsableMarkup(String miniMessage) {
        try {
            String rendered = PLAIN.serialize(MINI.deserialize(miniMessage));
            return !UNPARSED_TAG.matcher(rendered).find();
        } catch (RuntimeException broken) {
            return false;
        }
    }

    /** Something shaped like a tag, left over after parsing — i.e. one nothing recognised. */
    private static final java.util.regex.Pattern UNPARSED_TAG =
            java.util.regex.Pattern.compile("<[a-zA-Z_][a-zA-Z0-9_:#-]*>");

    private static boolean isColour(String colour) {
        String cleaned = colour.trim().toLowerCase(java.util.Locale.ROOT);
        return cleaned.matches("#[0-9a-f]{6}")
                || net.kyori.adventure.text.format.NamedTextColor.NAMES.value(cleaned) != null;
    }

    /** Cuts a nametag down to something that fits over a moving player, tags kept whole. */
    private static Component clip(Component nametag) {
        String plain = PLAIN.serialize(nametag);
        if (plain.length() <= MAX_NAMETAG_CHARS) {
            return nametag;
        }
        // Rebuilt from plain text rather than walked: a nametag long enough to need clipping is one
        // somebody has abused, and keeping its gradient intact is not worth the complexity.
        return Component.text(plain.substring(0, MAX_NAMETAG_CHARS - 1) + "…");
    }

    private static Optional<String> notEmpty(String value) {
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}
