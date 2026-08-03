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
import java.util.function.BiConsumer;

/**
 * Permissions this server has granted somebody, remembered across restarts.
 *
 * <h2>What this is not</h2>
 * <b>Not a permissions plugin.</b> No groups, no inheritance, no contexts, no wildcards, no negative
 * nodes — and it should never grow them. LuckPerms exists and is better at every one of those. This is
 * the small thing a server <em>without</em> one needs: a list of nodes granted to a named person,
 * applied when they join, and still there tomorrow.
 *
 * <p>It layers rather than replaces. A server that later installs LuckPerms keeps whatever it grants;
 * these are applied on top through a {@link org.bukkit.permissions.PermissionAttachment}, which is the
 * per-player mechanism Bukkit already has for exactly this.
 *
 * <h2>Why it is here rather than in whichever plugin needed it first</h2>
 * The plugin that needed it first was a moderation module handing out staff presets — and those presets
 * include <em>land claim</em> permissions. A grant store inside the moderation module would be a
 * moderation module that the claims module depends on, which is the cycle the whole arrangement exists
 * to prevent. Two plugins wanted this before it was written.
 *
 * <h2>Nodes are stored exactly as written</h2>
 * Deliberately not normalised. Bukkit compares permission strings literally, so lower-casing a node
 * here would silently grant one that does not exist — and the symptom is a moderator whose commands all
 * refuse them for no visible reason.
 *
 * <h2>Thread safety</h2>
 * Read from permission checks, which happen on every command and inside render loops; written from
 * commands and menu clicks. Safe from any thread. {@link #load} and {@link #flush} touch disk and
 * should not be called on the server thread.
 */
public final class Grants {

    private static final LogChannel log = Log.of("permissions");

    private final ConcurrentHashMap<UUID, Set<String>> granted = new ConcurrentHashMap<>();
    private final YamlStore store;

    public Grants(Path folder) {
        this.store = new YamlStore(folder.resolve("grants.yml"));
    }

    /** Where they are kept — for a diagnostic, and for a test that wants to break the file. */
    public Path file() {
        return store.file();
    }

    // ---------------------------------------------------------------------------- granting

    /** Adds one node. @return whether this changed anything */
    public boolean grant(UUID who, String node) {
        if (who == null || node == null || node.isBlank()) {
            return false;
        }
        return nodesOf(who).add(node.trim());
    }

    /** Takes one back. @return whether they had it */
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

    /**
     * Replaces everything they have with this set.
     *
     * <p>Replaces rather than adds, which is the whole point when a preset is applied: somebody moved
     * from Moderator down to Helper has to <em>lose</em> what Helper does not have. A version that only
     * added would make every demotion a no-op — and the demotion nobody notices failed is the dangerous
     * direction.
     */
    public void set(UUID who, Collection<String> nodes) {
        if (who == null) {
            return;
        }
        if (nodes == null || nodes.isEmpty()) {
            granted.remove(who);
            return;
        }
        Set<String> fresh = ConcurrentHashMap.newKeySet();
        for (String node : nodes) {
            if (node != null && !node.isBlank()) {
                fresh.add(node.trim());
            }
        }
        if (fresh.isEmpty()) {
            granted.remove(who);
            return;
        }
        granted.put(who, fresh);
    }

    /** Takes everything away. @return whether they had anything */
    public boolean clear(UUID who) {
        return who != null && granted.remove(who) != null;
    }

    // ---------------------------------------------------------------------------- asking

    /** Whether this server has granted them this node. */
    public boolean has(UUID who, String node) {
        if (who == null || node == null || node.isBlank()) {
            return false;
        }
        Set<String> theirs = granted.get(who);
        return theirs != null && theirs.contains(node.trim());
    }

    /** Everything granted to them, as a copy. */
    public Set<String> nodesFor(UUID who) {
        if (who == null) {
            return new LinkedHashSet<>();
        }
        Set<String> theirs = granted.get(who);
        return theirs == null ? new LinkedHashSet<>() : new LinkedHashSet<>(theirs);
    }

    /** How many nodes they hold. */
    public int countFor(UUID who) {
        Set<String> theirs = who == null ? null : granted.get(who);
        return theirs == null ? 0 : theirs.size();
    }

    /** Everybody with anything granted at all. */
    public Set<UUID> everybody() {
        return Set.copyOf(granted.keySet());
    }

    // ---------------------------------------------------------------------------- applying

    /**
     * Hands every granted node to whatever sets permissions.
     *
     * <p>Takes a consumer rather than a {@code Player} so the interesting half — which nodes, and how
     * many times each — is testable without a server. The Bukkit side is
     * {@code GrantListener}, which passes {@code attachment::setPermission}.
     */
    public void applyTo(UUID who, BiConsumer<String, Boolean> setter) {
        if (who == null || setter == null) {
            return;
        }
        for (String node : nodesFor(who)) {
            setter.accept(node, true);
        }
    }

    // ---------------------------------------------------------------------------- persistence

    /** Reads what is on disk, replacing what is held. */
    public void load() {
        granted.clear();
        ConfigurationSection root = store.read().getConfigurationSection("granted");
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
            set(who, root.getStringList(id));
        }
        if (!unreadable.isEmpty()) {
            // Loud, because the consequence is somebody who should have permissions and silently does
            // not — which they will report as "my commands stopped working".
            log.error("{} entry/entries in grants.yml are not player ids and have been skipped: {}. "
                            + "Anybody they belonged to has no granted permissions this session.",
                    unreadable.size(), String.join(", ", unreadable));
        }
    }

    /** Writes the lot. @return whether it reached the disk */
    public boolean flush() {
        return store.write(yaml -> granted.forEach((who, nodes) ->
                yaml.set("granted." + who, new ArrayList<>(nodes))));
    }

    // ---------------------------------------------------------------------------- internals

    private Set<String> nodesOf(UUID who) {
        return granted.computeIfAbsent(who, id -> ConcurrentHashMap.newKeySet());
    }

    /**
     * Drops the entry when the last node goes.
     *
     * <p>Otherwise the map grows by one empty set per player who has ever been staff and keeps it for
     * the life of the server — the same leak a listener that is never told a player left has.
     */
    private void forgetIfEmpty(UUID who, Set<String> theirs) {
        if (theirs.isEmpty()) {
            granted.remove(who, theirs);
        }
    }
}
