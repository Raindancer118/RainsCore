package de.raindancer.core.world.movement;

import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;

/**
 * Whether an entity stands on something, decided by the server from the blocks under it.
 *
 * <p>Instead of {@code Player#isOnGround}, which Paper deprecates because the client reports it: a
 * hacked client says what it likes, and anything acting on it can be steered. The one place the
 * client's claim is wanted is a cheat check comparing it against this.
 *
 * <p>Call on the thread that owns the entity (any event about it is), since it reads the world there.
 */
public final class Footing {

    /** How far below the feet still counts as standing — less than any step or slab. */
    private static final double SLICE = 0.05;

    private Footing() {
    }

    public static boolean grounded(Entity entity) {
        if (entity == null) {
            return false;
        }
        World world = entity.getWorld();
        BoundingBox body = entity.getBoundingBox();
        if (world == null || body == null) {
            return false;
        }
        return world.hasCollisionsIn(new BoundingBox(body.getMinX(), body.getMinY() - SLICE, body.getMinZ(),
                body.getMaxX(), body.getMinY(), body.getMaxZ()));
    }
}
