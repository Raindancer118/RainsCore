package de.raindancer.core.poi;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A place somebody wanted remembered: a home, a stop on a ghast line, where a player died.
 *
 * <h2>Why there is one of these instead of three</h2>
 * {@code Home}, {@code Destination} and {@code Waypoint} were three records in three plugins with
 * the same five fields and — word for word — the same javadoc explaining why the world is a name.
 * Three copies is three places for a bug to be fixed in one of. This is the one of them, which also
 * means a place saved by any plugin can be listed, flown to or drawn on a map by any other.
 *
 * <h2>Why the world is a name and not a {@link World}</h2>
 * A saved place outlives the server it was saved on. Holding the world object keeps an unloaded
 * world in the heap, and a place in a world that is not loaded right now should be <em>unreachable
 * until it comes back</em>, not thrown away at load time — otherwise a multiverse server that
 * unloads a world for maintenance silently loses every home in it. Position is stored rather than
 * the {@link Location} it came from for the same reason: a Location holds a reference to its world.
 *
 * @param id      unique and permanent; survives being renamed and moved
 * @param kind    what sort of place this is — {@code "home"}, {@code "stop"}, {@code "death"} — which
 *                is how one store serves plugins that have nothing else to do with each other
 * @param tags    whatever the owning plugin needs to remember alongside it
 */
public record Poi(String id, String name, String kind, UUID owner, String world,
                  double x, double y, double z, float yaw, float pitch,
                  Material icon, String label, boolean shared, Map<String, String> tags) {

    public Poi {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A place needs a name.");
        }
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("A place needs a world.");
        }
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        kind = kind == null || kind.isBlank() ? "place" : kind;
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    public static Builder builder(String name, String world, double x, double y, double z) {
        return new Builder(name, world, x, y, z);
    }

    /** What a menu shows for it: the label its owner gave it, or its name. */
    @Override
    public String label() {
        return label == null || label.isBlank() ? name : label;
    }

    /** One of the owning plugin's own notes about this place. */
    public Optional<String> tag(String key) {
        return Optional.ofNullable(tags.get(key));
    }

    /** The place itself, or empty when its world is not loaded right now. */
    public Optional<Location> location() {
        World loaded = Bukkit.getWorld(world);
        return loaded == null ? Optional.empty()
                : Optional.of(new Location(loaded, x, y, z, yaw, pitch));
    }

    /** Whether the world this is in exists on the server right now. */
    public boolean isReachable() {
        return Bukkit.getWorld(world) != null;
    }

    /** "x, y, z", rounded — the useful part of a place, for a lore line. */
    public String coordinates() {
        return Math.round(x) + ", " + Math.round(y) + ", " + Math.round(z);
    }

    public boolean isShared() {
        return shared;
    }

    /** The same place under another name. Keeps its id, so anything pointing at it still does. */
    public Poi renamedTo(String newName) {
        return new Poi(id, newName, kind, owner, world, x, y, z, yaw, pitch, icon, label, shared,
                tags);
    }

    /** The same place, somewhere else. */
    public Poi movedTo(String newWorld, double newX, double newY, double newZ) {
        return new Poi(id, name, kind, owner, newWorld, newX, newY, newZ, yaw, pitch, icon, label,
                shared, tags);
    }

    public Poi withIcon(Material newIcon) {
        return new Poi(id, name, kind, owner, world, x, y, z, yaw, pitch, newIcon, label, shared,
                tags);
    }

    public Poi withShared(boolean nowShared) {
        return new Poi(id, name, kind, owner, world, x, y, z, yaw, pitch, icon, label, nowShared,
                tags);
    }

    public Poi withTag(String key, String value) {
        Map<String, String> updated = new LinkedHashMap<>(tags);
        if (value == null) {
            updated.remove(key);
        } else {
            updated.put(key, value);
        }
        return new Poi(id, name, kind, owner, world, x, y, z, yaw, pitch, icon, label, shared,
                updated);
    }

    /** Builds one. Only the name, world and position are required. */
    public static final class Builder {
        private final String name;
        private final String world;
        private final double x;
        private final double y;
        private final double z;
        private String id;
        private String kind = "place";
        private UUID owner;
        private float yaw;
        private float pitch;
        private Material icon;
        private String label;
        private boolean shared;
        private final Map<String, String> tags = new LinkedHashMap<>();

        private Builder(String name, String world, double x, double y, double z) {
            this.name = name;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Builder id(String value) {
            this.id = value;
            return this;
        }

        public Builder kind(String value) {
            this.kind = value;
            return this;
        }

        public Builder owner(UUID value) {
            this.owner = value;
            return this;
        }

        /** Which way the player was looking, so arriving faces the same way as leaving did. */
        public Builder facing(float newYaw, float newPitch) {
            this.yaw = newYaw;
            this.pitch = newPitch;
            return this;
        }

        public Builder icon(Material value) {
            this.icon = value;
            return this;
        }

        public Builder label(String value) {
            this.label = value;
            return this;
        }

        public Builder shared(boolean value) {
            this.shared = value;
            return this;
        }

        public Builder tag(String key, String value) {
            if (key != null && value != null) {
                tags.put(key, value);
            }
            return this;
        }

        /** Everything about where a player is standing, including which way they face. */
        public static Builder at(String name, Location where) {
            Objects.requireNonNull(where, "location");
            return new Builder(name, where.getWorld() == null ? "" : where.getWorld().getName(),
                    where.getX(), where.getY(), where.getZ())
                    .facing(where.getYaw(), where.getPitch());
        }

        public Poi build() {
            return new Poi(id, name, kind, owner, world, x, y, z, yaw, pitch, icon, label, shared,
                    tags);
        }
    }
}
