package de.raindancer.core.world.teleport;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Optional;

/**
 * Somewhere somebody was, and why they are not there any more.
 *
 * <h2>Why the world is a name</h2>
 * The same reasoning as {@link de.raindancer.core.world.poi.Poi}: a place in a world that is not
 * loaded should be <em>unreachable until it comes back</em>, never thrown away — and holding the
 * {@link World} itself pins an unloaded world in the heap. Position rather than a {@link Location}
 * for the same reason: a Location holds a reference to its world.
 *
 * @param cause why they left — see {@link Cause}, and the note on {@link Returns#remember}
 * @param at    when, in milliseconds, so a caller can say "you died four minutes ago"
 */
public record Waypoint(String world, double x, double y, double z, float yaw, float pitch,
                       Cause cause, long at) {

    /**
     * Why somebody is not where they were.
     *
     * <p>Two, and the difference is load-bearing: a death outranks a teleport, because somebody who
     * died and was then moved still wants {@code /back} to mean their body.
     */
    public enum Cause {

        /** Something moved them — a warp, a home, a teleport request, a plugin. */
        TELEPORT("where you were"),

        /** They died there, and their things are probably still on the floor. */
        DEATH("where you died");

        private final String description;

        Cause(String description) {
            this.description = description;
        }

        /** A few words for the line that offers the way back. */
        public String describe() {
            return description;
        }
    }

    /** Where somebody is standing, as somewhere to come back to. */
    public static Waypoint of(Location where, Cause cause, long at) {
        if (where == null) {
            return null;
        }
        return new Waypoint(where.getWorld() == null ? "" : where.getWorld().getName(),
                where.getX(), where.getY(), where.getZ(), where.getYaw(), where.getPitch(),
                cause, at);
    }

    /** Whether this is somewhere at all — a waypoint with no world is not. */
    public boolean isUsable() {
        return world != null && !world.isBlank();
    }

    /** The place itself, or empty when its world is not loaded right now. */
    public Optional<Location> location() {
        if (!isUsable()) {
            return Optional.empty();
        }
        World loaded = Bukkit.getWorld(world);
        return loaded == null ? Optional.empty()
                : Optional.of(new Location(loaded, x, y, z, yaw, pitch));
    }

    /** Whether the world it is in exists on the server right now. */
    public boolean isReachable() {
        return isUsable() && Bukkit.getWorld(world) != null;
    }

    /** "x, y, z", rounded — the useful part of a place, for a lore line. */
    public String coordinates() {
        return Math.round(x) + ", " + Math.round(y) + ", " + Math.round(z);
    }
}
