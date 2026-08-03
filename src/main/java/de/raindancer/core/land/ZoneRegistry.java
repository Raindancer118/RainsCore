package de.raindancer.core.land;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps the no-claim zones and answers "may a claim exist here?". */
public final class ZoneRegistry {

    private final Map<String, NoClaimZone> byLowerName = new ConcurrentHashMap<>();

    public void add(NoClaimZone zone) {
        byLowerName.put(zone.name().toLowerCase(Locale.ROOT), zone);
    }

    public boolean remove(String name) {
        return byLowerName.remove(name.toLowerCase(Locale.ROOT)) != null;
    }

    public Optional<NoClaimZone> byName(String name) {
        return Optional.ofNullable(byLowerName.get(name.toLowerCase(Locale.ROOT)));
    }

    public Collection<NoClaimZone> all() {
        return Collections.unmodifiableCollection(byLowerName.values());
    }

    public List<NoClaimZone> sorted() {
        List<NoClaimZone> zones = new ArrayList<>(byLowerName.values());
        zones.sort((left, right) -> left.name().compareToIgnoreCase(right.name()));
        return zones;
    }

    public void clear() {
        byLowerName.clear();
    }

    public int size() {
        return byLowerName.size();
    }

    public Optional<NoClaimZone> at(Location location) {
        if (location == null || location.getWorld() == null) {
            return Optional.empty();
        }
        UUID worldId = location.getWorld().getUID();
        for (NoClaimZone zone : byLowerName.values()) {
            if (zone.worldId().equals(worldId)
                    && zone.shape().containsBlock(location.getBlockX(), location.getBlockY(), location.getBlockZ())) {
                return Optional.of(zone);
            }
        }
        return Optional.empty();
    }

    /** The first zone the given shape would collide with, if any. */
    public Optional<NoClaimZone> firstOverlap(UUID worldId, ClaimShape shape) {
        for (NoClaimZone zone : byLowerName.values()) {
            if (zone.worldId().equals(worldId) && zone.shape().intersects(shape)) {
                return Optional.of(zone);
            }
        }
        return Optional.empty();
    }
}
