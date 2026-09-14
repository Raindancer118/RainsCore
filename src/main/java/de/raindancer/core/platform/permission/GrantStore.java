package de.raindancer.core.platform.permission;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Where a granted node actually lives — {@link Grants}' one seam.
 *
 * <p>Two implementations: {@link LocalGrantStore}, the file-and-attachment mechanism this module has
 * always had, and {@link LuckPermsGrantStore}, which hands the same questions to a LuckPerms
 * installation instead. {@link Grants} does not know which one it is holding — it only adds the
 * change-notification every mutator needs, which is why that stays in {@link Grants} rather than being
 * duplicated in both stores.
 *
 * <p>Every mutator returns whether it actually changed anything, exactly as {@link Grants}' own methods
 * always have, so {@link Grants} can decide whether to announce the change without either store having
 * to know about watchers at all.
 */
interface GrantStore {

    /** Adds one node directly to somebody. @return whether this changed anything */
    boolean grant(UUID who, String node);

    /** Takes one node back. @return whether they had it */
    boolean revoke(UUID who, String node);

    /** Replaces everything held directly with exactly this set. @return whether this changed anything */
    boolean set(UUID who, Collection<String> nodes);

    /**
     * Replaces the preset somebody holds — a named, coarse-grained bundle such as a staff rank — with
     * exactly this node set, leaving anything granted individually alone.
     *
     * <p>The local store has no concept of a preset and treats this exactly like {@link #set}, ignoring
     * {@code presetId}. The LuckPerms store uses it to name and maintain a group.
     *
     * @return whether this changed anything
     */
    boolean setPreset(UUID who, String presetId, Collection<String> nodes);

    /** Adds every node in the collection, leaving whatever is already held alone. @return whether new */
    boolean grantAll(UUID who, Collection<String> nodes);

    /** Takes away everything this store granted them — the preset and anything granted individually. */
    boolean clear(UUID who);

    /** Whether they effectively hold this node, however it reached them. */
    boolean has(UUID who, String node);

    /** Everything they effectively hold, as a copy. */
    Set<String> nodesFor(UUID who);

    /** How many nodes they hold. */
    int countFor(UUID who);

    /** Everybody this store has anything on record for. */
    Set<UUID> everybody();

    /** Reads whatever this store keeps of its own — a no-op for a store that keeps nothing durable. */
    void load();

    /** Writes whatever this store keeps of its own. @return whether it reached disk */
    boolean flush();

    /** Where this store's own bookkeeping lives, for a diagnostic — never where the grants themselves are held. */
    Path file();
}
