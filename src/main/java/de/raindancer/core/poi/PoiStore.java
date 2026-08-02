package de.raindancer.core.poi;

import de.raindancer.core.log.Log;
import de.raindancer.core.log.LogChannel;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Every place any plugin has asked to remember.
 *
 * <h2>Why one store</h2>
 * There were three, behind three records with the same fields: homes had its own YAML, the ghast
 * lines had theirs, the teleport module had a third. Three answers to the same questions — what
 * happens when a world is gone, when a file is half-written, when two threads save at once — and
 * only one of the three had good answers to all of them. One store means a place saved by any plugin
 * can be listed, flown to or drawn on a map by any other, which is what makes a ghast line able to
 * fly somebody to their own home.
 *
 * <h2>Writing</h2>
 * Changes are held in memory and written when {@link #flush()} is called — on a timer, and at
 * shutdown. Writing on every change would put a disk write on the main thread every time somebody
 * sets a home; writing only at shutdown would lose everything on a crash. {@link #isDirty()} means
 * an idle server writes nothing at all.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread. The places are a {@link ConcurrentHashMap}, and a flush takes a snapshot
 * rather than holding a lock across the disk write.
 */
public final class PoiStore {

    private static final LogChannel log = Log.of("poi");

    private final Path file;
    private final Map<String, Poi> places = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final List<String> problems = new ArrayList<>();

    public PoiStore(Path file) {
        this.file = file;
    }

    // ---------------------------------------------------------------------------- writing

    /** Saves a place, replacing any with the same id. */
    public void save(Poi place) {
        if (place == null) {
            return;
        }
        places.put(place.id(), place);
        dirty.set(true);
    }

    /** Forgets one. Answers whether there was anything to forget. */
    public boolean delete(String id) {
        if (id == null || places.remove(id) == null) {
            return false;
        }
        dirty.set(true);
        return true;
    }

    /** Forgets everything one player owns — for a player who has been removed from the server. */
    public int deleteAllOwnedBy(UUID owner) {
        if (owner == null) {
            return 0;
        }
        List<String> theirs = places.values().stream()
                .filter(place -> owner.equals(place.owner()))
                .map(Poi::id)
                .toList();
        theirs.forEach(places::remove);
        if (!theirs.isEmpty()) {
            dirty.set(true);
        }
        return theirs.size();
    }

    // ---------------------------------------------------------------------------- reading

    public Optional<Poi> byId(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(places.get(id));
    }

    /**
     * One person's place of one kind, by name.
     *
     * <p>Case-insensitive, because a player who typed {@code /home Base} meant the one they made as
     * {@code base} and telling them otherwise is pedantry.
     */
    public Optional<Poi> named(UUID owner, String kind, String name) {
        if (name == null) {
            return Optional.empty();
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        return places.values().stream()
                .filter(place -> matches(place, owner, kind))
                .filter(place -> place.name().toLowerCase(Locale.ROOT).equals(wanted))
                .findFirst();
    }

    /** Everything one person owns, of one kind, in the order they were made. */
    public List<Poi> owned(UUID owner, String kind) {
        return owner == null ? List.of() : filtered(place -> matches(place, owner, kind));
    }

    /** Everything one person owns, whatever kind. */
    public List<Poi> owned(UUID owner) {
        return owned(owner, null);
    }

    /** Everything of one kind, whoever owns it. */
    public List<Poi> ofKind(String kind) {
        return kind == null ? List.of() : filtered(place -> kind.equals(place.kind()));
    }

    /** Everything whose owner has shared it. */
    public List<Poi> shared() {
        return filtered(Poi::isShared);
    }

    /** Everything in one world — so a world being removed can be dealt with deliberately. */
    public List<Poi> inWorld(String world) {
        return world == null ? List.of() : filtered(place -> world.equals(place.world()));
    }

    /** Everything, in no particular order beyond insertion. */
    public List<Poi> all() {
        return filtered(place -> true);
    }

    /** How many one person has of a kind — for a limit. */
    public int count(UUID owner, String kind) {
        return owned(owner, kind).size();
    }

    /** What could not be read from the file, in the words the log used. */
    public synchronized List<String> problems() {
        return List.copyOf(problems);
    }

    private List<Poi> filtered(java.util.function.Predicate<Poi> test) {
        return places.values().stream().filter(test).toList();
    }

    private static boolean matches(Poi place, UUID owner, String kind) {
        if (owner != null && !owner.equals(place.owner())) {
            return false;
        }
        return kind == null || kind.equals(place.kind());
    }

    // ---------------------------------------------------------------------------- the file

    /** Whether anything has changed since the last write. An idle server writes nothing. */
    public boolean isDirty() {
        return dirty.get();
    }

    /** Reads the file. A missing one is an empty store, which is what a first run is. */
    public void load() {
        places.clear();
        synchronized (this) {
            problems.clear();
        }
        if (!Files.isRegularFile(file)) {
            dirty.set(false);
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file));
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException failure) {
            // Not fatal: a broken file means the plugins start with nothing rather than not at all,
            // and the file is left untouched so whoever fixes it still has their data.
            note("the file could not be read (" + failure.getMessage() + ")");
            return;
        }
        ConfigurationSection section = yaml.getConfigurationSection("places");
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
                Poi place = read(id, entry);
                places.put(place.id(), place);
            } catch (RuntimeException broken) {
                // One bad entry is one lost place, not a lost file. The rest still load.
                note("'" + id + "' could not be read and was skipped (" + broken.getMessage() + ")");
            }
        }
        dirty.set(false);
    }

    private void note(String problem) {
        synchronized (this) {
            problems.add(problem);
        }
        log.warn("{}: {}", file.getFileName(), problem);
    }

    private static Poi read(String id, ConfigurationSection entry) {
        String name = entry.getString("name");
        String world = entry.getString("world");
        if (name == null || world == null) {
            throw new IllegalArgumentException("it has no name or no world");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        ConfigurationSection tagged = entry.getConfigurationSection("tags");
        if (tagged != null) {
            for (String key : tagged.getKeys(false)) {
                tags.put(key, String.valueOf(tagged.get(key)));
            }
        }
        Poi.Builder built = Poi.builder(name, world,
                        entry.getDouble("x"), entry.getDouble("y"), entry.getDouble("z"))
                .id(id)
                .kind(entry.getString("kind", "place"))
                .facing((float) entry.getDouble("yaw"), (float) entry.getDouble("pitch"))
                .label(entry.getString("label"))
                .shared(entry.getBoolean("shared"));
        String owner = entry.getString("owner");
        if (owner != null && !owner.isBlank()) {
            built.owner(UUID.fromString(owner));
        }
        String icon = entry.getString("icon");
        if (icon != null && !icon.isBlank()) {
            // A block renamed between versions, or one a newer server knows about, is "no icon"
            // rather than an entry that will not load.
            built.icon(Material.matchMaterial(icon));
        }
        tags.forEach(built::tag);
        return built.build();
    }

    /**
     * Writes, if anything changed.
     *
     * <p>Written to a temporary file and moved into place, so a server killed mid-write has either
     * the old file or the new one and never half of each — which is how a store of everybody's homes
     * gets truncated.
     */
    public void flush() {
        if (!dirty.compareAndSet(true, false)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        for (Poi place : List.copyOf(places.values())) {
            String path = "places." + place.id() + ".";
            yaml.set(path + "name", place.name());
            yaml.set(path + "kind", place.kind());
            yaml.set(path + "world", place.world());
            yaml.set(path + "x", place.x());
            yaml.set(path + "y", place.y());
            yaml.set(path + "z", place.z());
            if (place.yaw() != 0f || place.pitch() != 0f) {
                yaml.set(path + "yaw", place.yaw());
                yaml.set(path + "pitch", place.pitch());
            }
            if (place.owner() != null) {
                yaml.set(path + "owner", place.owner().toString());
            }
            if (place.icon() != null) {
                yaml.set(path + "icon", place.icon().name());
            }
            if (place.label() != null && !place.label().equals(place.name())) {
                yaml.set(path + "label", place.label());
            }
            if (place.isShared()) {
                yaml.set(path + "shared", true);
            }
            place.tags().forEach((key, value) -> yaml.set(path + "tags." + key, value));
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = file.resolveSibling(file.getFileName() + ".writing");
            Files.writeString(temporary, yaml.saveToString());
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            // Left dirty, so the next flush tries again rather than believing it succeeded.
            dirty.set(true);
            log.error(failure, "Could not write {}", file);
        }
    }
}
