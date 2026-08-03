package de.raindancer.core.loot;

import de.raindancer.core.log.Log;
import de.raindancer.core.log.LogChannel;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Every loot table any plugin has defined.
 *
 * <p>The same shape as {@link de.raindancer.core.items.CustomItems}, and for the same reasons: a
 * plugin ships defaults with {@link #defineIfAbsent} so a restart does not undo the owner's edits,
 * one bad entry is one lost entry rather than an unreadable file, and the file is written through a
 * temporary so a kill mid-write cannot truncate it.
 */
public final class LootTables {

    private static final LogChannel log = Log.of("loot");

    private final Path file;
    private final Map<String, LootTable> byKey = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final List<String> problems = new ArrayList<>();

    public LootTables(Path file) {
        this.file = file;
    }

    // ---------------------------------------------------------------------------- defining

    public void define(LootTable table) {
        if (table != null) {
            byKey.put(table.key(), table);
            dirty.set(true);
        }
    }

    /** Defines it only if nobody has — how a plugin ships a default without undoing edits. */
    public boolean defineIfAbsent(LootTable table) {
        if (table == null) {
            return false;
        }
        boolean added = byKey.putIfAbsent(table.key(), table) == null;
        if (added) {
            dirty.set(true);
        }
        return added;
    }

    public boolean undefine(String key) {
        if (key == null || byKey.remove(key.trim().toLowerCase(Locale.ROOT)) == null) {
            return false;
        }
        dirty.set(true);
        return true;
    }

    // ---------------------------------------------------------------------------- asking

    public Optional<LootTable> byKey(String key) {
        return key == null ? Optional.empty()
                : Optional.ofNullable(byKey.get(key.trim().toLowerCase(Locale.ROOT)));
    }

    public List<LootTable> all() {
        return List.copyOf(byKey.values());
    }

    public List<String> keys() {
        return List.copyOf(byKey.keySet());
    }

    public List<LootTable> ofPlugin(String plugin) {
        if (plugin == null) {
            return List.of();
        }
        String wanted = plugin.trim().toLowerCase(Locale.ROOT);
        return byKey.values().stream().filter(table -> table.plugin().equals(wanted)).toList();
    }

    /** Every table of one tier — the ordinary chests, or the supply drops. */
    public List<LootTable> ofTier(int tier) {
        return byKey.values().stream().filter(table -> table.tier() == tier).toList();
    }

    /** Which tiers exist, in order. */
    public List<Integer> tiers() {
        return byKey.values().stream().map(LootTable::tier).distinct().sorted().toList();
    }

    public synchronized List<String> problems() {
        return List.copyOf(problems);
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
        if (!Files.isRegularFile(file)) {
            dirty.set(false);
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file));
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException failure) {
            note("the file could not be read (" + failure.getMessage() + ")");
            return;
        }
        ConfigurationSection section = yaml.getConfigurationSection("tables");
        if (section == null) {
            dirty.set(false);
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            int colon = key.indexOf(':');
            if (entry == null || colon <= 0) {
                note("'" + key + "' is not plugin:id");
                continue;
            }
            try {
                LootTable.Builder built = LootTable.builder(key.substring(0, colon),
                                key.substring(colon + 1))
                        .tier(entry.getInt("tier", 1))
                        .fillPercent(entry.getInt("fill-percent", 30));
                for (Map<?, ?> raw : entry.getMapList("entries")) {
                    readEntry(key, raw).ifPresent(built::entry);
                }
                LootTable table = built.build();
                byKey.put(table.key(), table);
            } catch (RuntimeException broken) {
                note("'" + key + "' was skipped (" + broken.getMessage() + ")");
            }
        }
        dirty.set(false);
    }

    /** One entry, or empty when it names something this server does not have. */
    private Optional<LootEntry> readEntry(String tableKey, Map<?, ?> raw) {
        int weight = raw.get("weight") instanceof Number number ? number.intValue() : 1;
        int least = raw.get("min") instanceof Number number ? number.intValue() : 1;
        int most = raw.get("max") instanceof Number number ? number.intValue() : least;

        Object custom = raw.get("item");
        if (custom != null && !String.valueOf(custom).isBlank()) {
            return Optional.of(LootEntry.ofCustomItem(String.valueOf(custom), weight)
                    .amount(least, most));
        }
        String materialName = String.valueOf(raw.get("material"));
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            // One entry lost, not a table — a block renamed between versions must not take the
            // whole pool with it.
            note("'" + tableKey + "' names '" + materialName
                    + "', which this server has no block for; that entry is dropped");
            return Optional.empty();
        }
        return Optional.of(LootEntry.of(material, weight).amount(least, most));
    }

    private void note(String problem) {
        synchronized (this) {
            problems.add(problem);
        }
        log.warn("{}: {}", file.getFileName(), problem);
    }

    /** Writes, if anything changed. Via a temporary file, so a kill mid-write cannot truncate it. */
    public void flush() {
        if (!dirty.compareAndSet(true, false)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        for (LootTable table : List.copyOf(byKey.values())) {
            String path = "tables." + table.key() + ".";
            yaml.set(path + "tier", table.tier());
            yaml.set(path + "fill-percent", table.fillPercent());
            yaml.set(path + "entries", table.entries().stream()
                    .map(LootTables::asMap)
                    .collect(Collectors.toList()));
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
            dirty.set(true);
            log.error(failure, "Could not write {}", file);
        }
    }

    private static Map<String, Object> asMap(LootEntry entry) {
        Map<String, Object> written = new java.util.LinkedHashMap<>();
        if (entry.isCustom()) {
            written.put("item", entry.customKey());
        } else {
            written.put("material", entry.material().name());
        }
        written.put("weight", entry.weight());
        if (entry.minimum() != 1 || entry.maximum() != 1) {
            written.put("min", entry.minimum());
            written.put("max", entry.maximum());
        }
        return written;
    }
}
