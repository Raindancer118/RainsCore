package de.raindancer.core.world.movement;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Objects;

/**
 * What a move actually was. Nearly every {@code PlayerMoveEvent} on a server is a turn of the head or a
 * step inside one block, so a listener's first question is "did they really go anywhere" — answered
 * once here, the same way everywhere, null-safe and world-aware.
 *
 * <pre>{@code
 * @EventHandler
 * public void onMove(PlayerMoveEvent event) {
 *     if (!Moves.changedBlock(event)) {
 *         return;                       // cheapest first: most moves end here
 *     }
 *     ...
 * }
 * }</pre>
 */
public final class Moves {

    private Moves() {
    }

    /** Whether two places are the same block of the same world. Null is the same as nothing else. */
    public static boolean sameBlock(Location one, Location other) {
        if (one == null || other == null) {
            return one == other;
        }
        return sameWorld(one, other)
                && one.getBlockX() == other.getBlockX()
                && one.getBlockY() == other.getBlockY()
                && one.getBlockZ() == other.getBlockZ();
    }

    /** Whether this move took them into another block — or another world. A move with nowhere to go did not. */
    public static boolean changedBlock(PlayerMoveEvent event) {
        return event != null && event.getTo() != null && !sameBlock(event.getFrom(), event.getTo());
    }

    /** Whether they moved at all, as opposed to only turning their head. */
    public static boolean changedPosition(PlayerMoveEvent event) {
        if (event == null || event.getTo() == null) {
            return false;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        return !sameWorld(from, to) || from.getX() != to.getX() || from.getY() != to.getY()
                || from.getZ() != to.getZ();
    }

    /** Whether this move crossed into another world. */
    public static boolean changedWorld(PlayerMoveEvent event) {
        return event != null && event.getTo() != null && !sameWorld(event.getFrom(), event.getTo());
    }

    /**
     * Keeps a player in their block while letting them look around — what a countdown or a freeze
     * wants. Cancelling the event instead snaps their head back every tick, which reads as lag.
     *
     * @return whether the move had to be held back
     */
    public static boolean holdInPlace(PlayerMoveEvent event) {
        if (!changedBlock(event)) {
            return false;
        }
        Location held = event.getFrom().clone();
        held.setYaw(event.getTo().getYaw());
        held.setPitch(event.getTo().getPitch());
        event.setTo(held);
        return true;
    }

    /** Blocks between two places on the ground, ignoring height; infinite across worlds. */
    public static double horizontalDistance(Location one, Location other) {
        if (one == null || other == null || !sameWorld(one, other)) {
            return Double.POSITIVE_INFINITY;
        }
        double dx = one.getX() - other.getX();
        double dz = one.getZ() - other.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Whether two places are in the same world. Compared by name, so a Location still holding a world
     * that has since been unloaded and loaded again is not a different place.
     */
    private static boolean sameWorld(Location one, Location other) {
        World a = worldOf(one);
        World b = worldOf(other);
        if (a == null || b == null) {
            return a == b;
        }
        return a == b || Objects.equals(a.getName(), b.getName());
    }

    /** The world, or null — never the exception a Location for an unloaded world throws. */
    private static World worldOf(Location at) {
        try {
            return at.getWorld();
        } catch (IllegalArgumentException unloaded) {
            return null;
        }
    }
}
