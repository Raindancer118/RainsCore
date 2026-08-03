package de.raindancer.core.land;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory index of all claims.
 * <p>
 * Lookups happen on every block break, every mob spawn and every player move, so a linear scan is out
 * of the question. Claims are bucketed by world and chunk key; a positional query only tests the
 * handful of claims whose bounding box touches that chunk.
 * <p>
 * Vertical stacking is supported: several claims may share a chunk bucket and even a column as long as
 * their Y ranges do not overlap, which is what makes hidden underground claims work.
 */
public final class ClaimRegistry {

    private final Map<UUID, Claim> byId = new ConcurrentHashMap<>();
    /** world id → chunk key → claims whose bounding box touches that chunk. */
    private final Map<UUID, Map<Long, Set<Claim>>> spatialIndex = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> claimsByOwner = new ConcurrentHashMap<>();
    /**
     * Lowercased claim name → every claim carrying it.
     * <p>
     * Names are unique <em>per owner</em>, not per server: five people each wanting their own "home" is
     * the obvious thing for them to want, and the first one to claim the word should not take it from
     * everybody else. Ambiguity is resolved when a name is looked up, not prevented up front.
     */
    private final Map<String, Set<UUID>> byLowerName = new ConcurrentHashMap<>();

    public void add(Claim claim) {
        byId.put(claim.id(), claim);
        indexSpatially(claim);
        for (UUID owner : claim.owners()) {
            claimsByOwner.computeIfAbsent(owner, key -> ConcurrentHashMap.newKeySet()).add(claim.id());
        }
        indexName(claim);
    }

    public void remove(Claim claim) {
        byId.remove(claim.id());
        unindexSpatially(claim);
        claimsByOwner.values().forEach(set -> set.remove(claim.id()));
        unindexName(claim.id());
    }

    /** Re-indexes a claim after its shape, name or owner set changed. */
    public void reindex(Claim claim) {
        unindexSpatially(claim);
        indexSpatially(claim);
        claimsByOwner.values().forEach(set -> set.remove(claim.id()));
        for (UUID owner : claim.owners()) {
            claimsByOwner.computeIfAbsent(owner, key -> ConcurrentHashMap.newKeySet()).add(claim.id());
        }
        unindexName(claim.id());
        indexName(claim);
    }

    public void clear() {
        byId.clear();
        spatialIndex.clear();
        claimsByOwner.clear();
        byLowerName.clear();
    }

    private void indexSpatially(Claim claim) {
        Map<Long, Set<Claim>> worldIndex =
                spatialIndex.computeIfAbsent(claim.worldId(), key -> new ConcurrentHashMap<>());
        for (long chunkKey : claim.shape().coveredChunkKeys()) {
            worldIndex.computeIfAbsent(chunkKey, key -> ConcurrentHashMap.newKeySet()).add(claim);
        }
    }

    private void unindexSpatially(Claim claim) {
        Map<Long, Set<Claim>> worldIndex = spatialIndex.get(claim.worldId());
        if (worldIndex == null) {
            return;
        }
        // Iterate the whole world index rather than the current shape: after a resize the old buckets
        // are no longer derivable from the claim.
        worldIndex.values().forEach(set -> set.remove(claim));
        worldIndex.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public Collection<Claim> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public int size() {
        return byId.size();
    }

    public Optional<Claim> byId(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    /**
     * The one claim with this name, if there is exactly one.
     * <p>
     * Empty when nothing matches <em>and</em> when several do: with the name shared, picking one of them
     * would be a guess. Callers that can do better — prefer the caller's own claim, or list the
     * candidates — use {@link #allByName(String)}.
     */
    public Optional<Claim> byName(String name) {
        List<Claim> matches = allByName(name);
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    /** Every claim carrying this name, in no particular order. */
    public List<Claim> allByName(String name) {
        Set<UUID> ids = byLowerName.get(nameKey(name));
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Claim> matches = new ArrayList<>();
        for (UUID id : ids) {
            Claim claim = byId.get(id);
            if (claim != null) {
                matches.add(claim);
            }
        }
        return matches;
    }

    /**
     * Whether this owner already has a claim by this name.
     * <p>
     * Per owner rather than per server: somebody else's "home" is not in the way of yours. A co-owned
     * claim counts for each of its owners, so two people sharing a claim cannot both add a second one
     * called the same thing.
     */
    public boolean nameTaken(String name, UUID owner) {
        for (Claim claim : allByName(name)) {
            if (owner == null || claim.isOwner(owner)) {
                return true;
            }
        }
        return false;
    }

    /** Claims a player co-owns. */
    public List<Claim> ownedBy(UUID owner) {
        Set<UUID> ids = claimsByOwner.get(owner);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Claim> claims = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Claim claim = byId.get(id);
            if (claim != null) {
                claims.add(claim);
            }
        }
        claims.sort((left, right) -> left.name().compareToIgnoreCase(right.name()));
        return claims;
    }

    /** Claims a player owns or is trusted on. */
    public List<Claim> accessibleBy(UUID player) {
        List<Claim> claims = new ArrayList<>(ownedBy(player));
        Set<UUID> seen = new HashSet<>();
        claims.forEach(claim -> seen.add(claim.id()));
        for (Claim claim : byId.values()) {
            if (!seen.contains(claim.id()) && claim.members().containsKey(player)) {
                claims.add(claim);
            }
        }
        claims.sort((left, right) -> left.name().compareToIgnoreCase(right.name()));
        return claims;
    }

    public int countOwned(UUID owner) {
        Set<UUID> ids = claimsByOwner.get(owner);
        return ids == null ? 0 : ids.size();
    }

    /**
     * The claim at a location. When several stacked claims cover the column the one with the smaller
     * vertical range wins, so a small underground claim inside a huge surface claim still applies.
     */
    public Optional<Claim> at(Location location) {
        if (location == null || location.getWorld() == null) {
            return Optional.empty();
        }
        return at(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public Optional<Claim> at(World world, int x, int y, int z) {
        Map<Long, Set<Claim>> worldIndex = spatialIndex.get(world.getUID());
        if (worldIndex == null) {
            return Optional.empty();
        }
        Set<Claim> bucket = worldIndex.get(ClaimShape.chunkKey(x >> 4, z >> 4));
        if (bucket == null || bucket.isEmpty()) {
            return Optional.empty();
        }
        Claim best = null;
        for (Claim claim : bucket) {
            if (!claim.shape().containsBlock(x, y, z)) {
                continue;
            }
            if (best == null || claim.shape().height() < best.shape().height()) {
                best = claim;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * How far outside a claim's vertical range a player may stray before they count as having left it.
     * <p>
     * A jump lifts you one block, a jump onto a slab or with a boost two. Without this, any claim whose
     * ceiling sits near standing height fires a leave and an enter on every single hop — it makes no
     * difference whether what is above the ceiling is another claim or open wilderness.
     */
    private static final int VERTICAL_GRACE = 2;

    /**
     * The claim a player counts as standing in, given the one they were in a moment ago.
     * <p>
     * Plain {@link #at(Location)} answers for a block, which is what protection wants: it asks about the
     * thing being broken, not about the person. A <em>player</em> is somewhere continuously, so a
     * one-tick excursion out of the top of their claim is not a border crossing. The previous claim
     * keeps them as long as they are still over its footprint and within {@link #VERTICAL_GRACE} of its
     * range; anything further, or a step off the footprint, is a real transition.
     * <p>
     * Self-healing: when the previous claim no longer holds, this is exactly {@link #at(Location)}.
     */
    public Optional<Claim> at(Location location, Claim previous) {
        if (previous == null || location == null || location.getWorld() == null) {
            return at(location);
        }
        boolean stillHeld = stillHolds(previous, location.getWorld().getUID(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
        return stillHeld ? Optional.of(previous) : at(location);
    }

    /**
     * Whether a claim keeps a player who has moved to this block: still over its footprint, and no
     * further than {@link #VERTICAL_GRACE} outside its vertical range.
     */
    public static boolean stillHolds(Claim previous, UUID worldId, int x, int y, int z) {
        if (previous == null || !previous.worldId().equals(worldId)) {
            return false;
        }
        ClaimShape shape = previous.shape();
        return shape.containsColumn(x, z)
                && y >= shape.minY() - VERTICAL_GRACE
                && y <= shape.maxY() + VERTICAL_GRACE;
    }

    /** Every claim covering the column, regardless of Y. Used by the border visualiser and admin tools. */
    public List<Claim> allAtColumn(World world, int x, int z) {
        Map<Long, Set<Claim>> worldIndex = spatialIndex.get(world.getUID());
        if (worldIndex == null) {
            return List.of();
        }
        Set<Claim> bucket = worldIndex.get(ClaimShape.chunkKey(x >> 4, z >> 4));
        if (bucket == null) {
            return List.of();
        }
        List<Claim> matches = new ArrayList<>();
        for (Claim claim : bucket) {
            if (claim.shape().containsColumn(x, z)) {
                matches.add(claim);
            }
        }
        return matches;
    }

    /** Claims in the world whose bounding box overlaps the given shape — the overlap pre-filter. */
    public Set<Claim> candidatesFor(UUID worldId, ClaimShape shape) {
        Map<Long, Set<Claim>> worldIndex = spatialIndex.get(worldId);
        if (worldIndex == null) {
            return Set.of();
        }
        Set<Claim> candidates = new LinkedHashSet<>();
        for (long chunkKey : shape.coveredChunkKeys()) {
            Set<Claim> bucket = worldIndex.get(chunkKey);
            if (bucket != null) {
                candidates.addAll(bucket);
            }
        }
        return candidates;
    }

    /** All claims near a location, used to decide which borders to render for a player. */
    public Set<Claim> nearby(World world, int x, int z, int chunkRadius) {
        Map<Long, Set<Claim>> worldIndex = spatialIndex.get(world.getUID());
        if (worldIndex == null) {
            return Set.of();
        }
        Set<Claim> found = new LinkedHashSet<>();
        int centreChunkX = x >> 4;
        int centreChunkZ = z >> 4;
        for (int cx = centreChunkX - chunkRadius; cx <= centreChunkX + chunkRadius; cx++) {
            for (int cz = centreChunkZ - chunkRadius; cz <= centreChunkZ + chunkRadius; cz++) {
                Set<Claim> bucket = worldIndex.get(ClaimShape.chunkKey(cx, cz));
                if (bucket != null) {
                    found.addAll(bucket);
                }
            }
        }
        return found;
    }

    public List<Claim> inWorld(UUID worldId) {
        List<Claim> claims = new ArrayList<>();
        for (Claim claim : byId.values()) {
            if (claim.worldId().equals(worldId)) {
                claims.add(claim);
            }
        }
        return claims;
    }

    /** Renames a claim while keeping the name index consistent. */
    public void rename(Claim claim, String newName) {
        unindexName(claim.id());
        claim.name(newName);
        indexName(claim);
    }

    private void indexName(Claim claim) {
        byLowerName.computeIfAbsent(nameKey(claim.name()), key -> ConcurrentHashMap.newKeySet())
                .add(claim.id());
    }

    private void unindexName(UUID claimId) {
        byLowerName.values().forEach(ids -> ids.remove(claimId));
        byLowerName.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public Map<UUID, Claim> snapshot() {
        return new HashMap<>(byId);
    }

    private static String nameKey(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
