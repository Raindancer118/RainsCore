package de.raindancer.core.items;

import de.raindancer.core.log.Log;
import de.raindancer.core.log.LogChannel;
import de.raindancer.core.store.YamlStore;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Every custom item any plugin has defined.
 *
 * <h2>What this is for</h2>
 * Every one of these plugins invents items: the claims module's selection stick, the record
 * seller's discs, the ghast lines' tickets. Each built its {@code ItemStack} by hand, stamped its
 * own key into the persistent data container, and had its own idea of how to tell one of its items
 * from a lookalike somebody crafted. Doing it once means an item defined by one plugin can be given,
 * recognised or listed by any other — and by a command and a menu — without either knowing about
 * the other.
 *
 * <h2>How a plugin uses it</h2>
 * <pre>
 * // At startup: ship a default, and leave whatever the owner has since changed alone.
 * items.defineIfAbsent(CustomItem.builder("claims", "selection-stick")
 *         .material(Material.STICK)
 *         .name("&lt;gold&gt;Claim Selection Stick")
 *         .glowing(true)
 *         .build());
 * </pre>
 * {@code defineIfAbsent} is the important half: a plugin that called {@code define} at every start
 * would undo the server owner's edits on every restart, which is the single most annoying thing a
 * plugin can do with a config file.
 */
public final class CustomItems {

    private static final LogChannel log = Log.of("items");

    private final Path file;
    private final YamlStore store;
    private final Map<String, CustomItem> byKey = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final List<String> problems = new ArrayList<>();

    public CustomItems(Path file) {
        this.file = file;
        this.store = new YamlStore(file);
    }

    // ---------------------------------------------------------------------------- defining

    /** Defines an item, replacing any with the same key. */
    public void define(CustomItem item) {
        if (item == null) {
            return;
        }
        byKey.put(item.key(), item);
        dirty.set(true);
    }

    /**
     * Defines it only if nobody has yet — how a plugin ships a default.
     *
     * @return whether it was added; false means the owner's version was kept
     */
    public boolean defineIfAbsent(CustomItem item) {
        if (item == null) {
            return false;
        }
        boolean added = byKey.putIfAbsent(item.key(), item) == null;
        if (added) {
            dirty.set(true);
        }
        return added;
    }

    /** Removes a definition. Items already made from it are unaffected. */
    public boolean undefine(String key) {
        if (key == null || byKey.remove(normalise(key)) == null) {
            return false;
        }
        dirty.set(true);
        return true;
    }

    // ---------------------------------------------------------------------------- asking

    public Optional<CustomItem> byKey(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(byKey.get(normalise(key)));
    }

    /** Everything one plugin defines. */
    public List<CustomItem> ofPlugin(String plugin) {
        if (plugin == null) {
            return List.of();
        }
        String wanted = plugin.trim().toLowerCase(Locale.ROOT);
        return byKey.values().stream().filter(item -> item.plugin().equals(wanted)).toList();
    }

    public List<CustomItem> all() {
        return List.copyOf(byKey.values());
    }

    /** Every key, for a command's tab completion. */
    public List<String> keys() {
        return List.copyOf(byKey.keySet());
    }

    /** What could not be read from the file. */
    public synchronized List<String> problems() {
        return List.copyOf(problems);
    }

    private static String normalise(String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------------------- the file

    public boolean isDirty() {
        return dirty.get();
    }

    public void load() {
        byKey.clear();
        synchronized (this) {
            problems.clear();
        }
        if (!store.exists()) {
            dirty.set(false);
            return;
        }
        YamlConfiguration yaml = store.read();
        if (!store.problems().isEmpty()) {
            carry();
            return;
        }
        ConfigurationSection section = yaml.getConfigurationSection("items");
        if (section == null) {
            dirty.set(false);
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            try {
                CustomItem item = read(key, entry);
                byKey.put(item.key(), item);
            } catch (RuntimeException broken) {
                // A block a newer server knows about, or one renamed between versions, is one item
                // lost rather than a file that will not load.
                note("'" + key + "' was skipped (" + broken.getMessage() + ")");
            }
        }
        dirty.set(false);
    }

    /** Takes over what the file itself was wrong about; the store has already logged it. */
    private void carry() {
        List<String> fromFile = store.problems();
        synchronized (this) {
            problems.addAll(fromFile);
        }
    }

    private void note(String problem) {
        synchronized (this) {
            problems.add(problem);
        }
        log.warn("{}: {}", file.getFileName(), problem);
    }

    private static CustomItem read(String key, ConfigurationSection entry) {
        int colon = key.indexOf(':');
        if (colon <= 0 || colon == key.length() - 1) {
            throw new IllegalArgumentException("'" + key + "' is not plugin:name");
        }
        String materialName = entry.getString("material", "");
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            throw new IllegalArgumentException(
                    "this server has no block called '" + materialName + "'");
        }
        CustomItem.Builder built = CustomItem.builder(key.substring(0, colon),
                        key.substring(colon + 1))
                .material(material)
                .name(entry.getString("name"))
                .lore(entry.getStringList("lore"))
                .glowing(entry.getBoolean("glowing"))
                .ability(entry.getString("ability"))
                .recipe(entry.getStringList("recipe"));
        if (entry.contains("model-data")) {
            built.modelData(entry.getInt("model-data"));
        }
        ConfigurationSection tagged = entry.getConfigurationSection("tags");
        if (tagged != null) {
            for (String tag : tagged.getKeys(false)) {
                built.tag(tag, String.valueOf(tagged.get(tag)));
            }
        }
        return built.build();
    }

    /** Writes, if anything changed. Via a temporary file, so a kill mid-write cannot truncate it. */
    public void flush() {
        if (!dirty.compareAndSet(true, false)) {
            return;
        }
        List<CustomItem> snapshot = List.copyOf(byKey.values());
        boolean written = store.write(yaml -> {
            for (CustomItem item : snapshot) {
                String path = "items." + item.key() + ".";
                yaml.set(path + "material", item.material().name());
                if (!item.displayName().isEmpty()) {
                    yaml.set(path + "name", item.displayName());
                }
                if (!item.lore().isEmpty()) {
                    yaml.set(path + "lore", item.lore());
                }
                item.modelData().ifPresent(data -> yaml.set(path + "model-data", data));
                if (item.isGlowing()) {
                    yaml.set(path + "glowing", true);
                }
                item.ability().ifPresent(ability -> yaml.set(path + "ability", ability));
                if (item.isCraftable()) {
                    yaml.set(path + "recipe", item.recipe());
                }
                item.tags().forEach((tag, value) -> yaml.set(path + "tags." + tag, value));
            }
        });
        if (!written) {
            dirty.set(true);
        }
    }
}
