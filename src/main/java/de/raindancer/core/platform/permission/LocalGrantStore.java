package de.raindancer.core.platform.permission;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The store a server without a permissions plugin needs: a list of nodes granted to a named person,
 * kept in memory and in {@code grants.yml}.
 *
 * <p>This is the whole of what {@link Grants} used to be before {@link LuckPermsGrantStore} existed —
 * moved here verbatim, as {@link GrantStore}'s local implementation.
 */
final class LocalGrantStore implements GrantStore {

    private static final LogChannel log = Log.of("permissions");

    private final ConcurrentHashMap<UUID, Set<String>> granted = new ConcurrentHashMap<>();
    private final YamlStore store;

    LocalGrantStore(Path folder) {
        this.store = new YamlStore(folder.resolve("grants.yml"));
    }

    @Override
    public Path file() {
        return store.file();
    }

    @Override
    public boolean grant(UUID who, String node) {
        if (who == null || node == null || node.isBlank()) {
            return false;
        }
        return nodesOf(who).add(node.trim());
    }

    @Override
    public boolean revoke(UUID who, String node) {
        if (who == null || node == null || node.isBlank()) {
            return false;
        }
        Set<String> theirs = granted.get(who);
        if (theirs == null || !theirs.remove(node.trim())) {
            return false;
        }
        forgetIfEmpty(who, theirs);
        return true;
    }

    @Override
    public boolean set(UUID who, Collection<String> nodes) {
        if (who == null) {
            return false;
        }
        if (nodes == null || nodes.isEmpty()) {
            return granted.remove(who) != null;
        }
        Set<String> fresh = ConcurrentHashMap.newKeySet();
        for (String node : nodes) {
            if (node != null && !node.isBlank()) {
                fresh.add(node.trim());
            }
        }
        if (fresh.isEmpty()) {
            return granted.remove(who) != null;
        }
        Set<String> before = granted.put(who, fresh);
        return before == null || !before.equals(fresh);
    }

    @Override
    public boolean setPreset(UUID who, String presetId, Collection<String> nodes) {
        // No group concept locally — a preset is just a set() by another name.
        return set(who, nodes);
    }

    @Override
    public boolean grantAll(UUID who, Collection<String> nodes) {
        if (who == null || nodes == null || nodes.isEmpty()) {
            return false;
        }
        Set<String> theirs = nodesOf(who);
        boolean changed = false;
        for (String node : nodes) {
            if (node != null && !node.isBlank() && theirs.add(node.trim())) {
                changed = true;
            }
        }
        return changed;
    }

    private void replaceQuietly(UUID who, Collection<String> nodes) {
        Set<String> fresh = ConcurrentHashMap.newKeySet();
        for (String node : nodes) {
            if (node != null && !node.isBlank()) {
                fresh.add(node.trim());
            }
        }
        if (fresh.isEmpty()) {
            granted.remove(who);
        } else {
            granted.put(who, fresh);
        }
    }

    @Override
    public boolean clear(UUID who) {
        return who != null && granted.remove(who) != null;
    }

    @Override
    public boolean has(UUID who, String node) {
        if (who == null || node == null || node.isBlank()) {
            return false;
        }
        Set<String> theirs = granted.get(who);
        return theirs != null && theirs.contains(node.trim());
    }

    @Override
    public Set<String> nodesFor(UUID who) {
        if (who == null) {
            return new LinkedHashSet<>();
        }
        Set<String> theirs = granted.get(who);
        return theirs == null ? new LinkedHashSet<>() : new LinkedHashSet<>(theirs);
    }

    @Override
    public int countFor(UUID who) {
        Set<String> theirs = who == null ? null : granted.get(who);
        return theirs == null ? 0 : theirs.size();
    }

    @Override
    public Set<UUID> everybody() {
        return Set.copyOf(granted.keySet());
    }

    @Override
    public void load() {
        granted.clear();
        readInto(store, this::replaceQuietly);
    }

    @Override
    public boolean flush() {
        return store.write(yaml -> granted.forEach((who, nodes) ->
                yaml.set("granted." + who, new ArrayList<>(nodes))));
    }

    private Set<String> nodesOf(UUID who) {
        return granted.computeIfAbsent(who, id -> ConcurrentHashMap.newKeySet());
    }

    private void forgetIfEmpty(UUID who, Set<String> theirs) {
        if (theirs.isEmpty()) {
            granted.remove(who, theirs);
        }
    }

    // ---------------------------------------------------------------------------- shared file format

    /**
     * Reads a {@code grants.yml}-shaped file's {@code granted} section straight into a caller-supplied
     * sink, without needing a whole store around it.
     *
     * <p>Package-private and static so {@link LuckPermsGrantStore} can read a server's pre-LuckPerms
     * {@code grants.yml} the exact same way this class does, for the one-time import.
     */
    static void readInto(YamlStore fromStore, java.util.function.BiConsumer<UUID, Collection<String>> sink) {
        ConfigurationSection root = fromStore.read().getConfigurationSection("granted");
        if (root == null) {
            return;
        }
        List<String> unreadable = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            UUID who;
            try {
                who = UUID.fromString(id);
            } catch (IllegalArgumentException notAnId) {
                unreadable.add(id);
                continue;
            }
            sink.accept(who, root.getStringList(id));
        }
        if (!unreadable.isEmpty()) {
            log.error("{} entry/entries in {} are not player ids and have been skipped: {}. Anybody "
                            + "they belonged to has no granted permissions this session.",
                    unreadable.size(), fromStore.file().getFileName(), String.join(", ", unreadable));
        }
    }
}
