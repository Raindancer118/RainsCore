package de.raindancer.core.world.visual;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * A dotted line of particles from somebody towards where they are going — "particle GPS".
 *
 * <p>{@link #toward} is the geometry, Bukkit-free; {@link #draw} shows the dots to one player only
 * ({@link Player#spawnParticle}), because a trail is that person's directions, not a light show for
 * everybody nearby. Call it again every few ticks to keep a trail that follows a moving target.
 */
public final class PathTrail {

    /** One dot of the trail. */
    public record Dot(double x, double y, double z) {
    }

    private PathTrail() {
    }

    /**
     * Dots along the straight line from {@code from} to {@code to}: the first {@code firstGap} blocks
     * ahead, then every {@code spacing} blocks, up to {@code maxLength} blocks out — and never past
     * the target itself.
     */
    public static List<Dot> toward(double fromX, double fromY, double fromZ,
                                   double toX, double toY, double toZ,
                                   double firstGap, double spacing, double maxLength) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 1e-6 || spacing <= 0 || maxLength <= 0) {
            return List.of();
        }
        double reach = Math.min(maxLength, distance);
        List<Dot> dots = new ArrayList<>();
        for (double along = Math.max(0, firstGap); along <= reach + 1e-9 && along < distance; along += spacing) {
            double t = along / distance;
            dots.add(new Dot(fromX + dx * t, fromY + dy * t, fromZ + dz * t));
        }
        return List.copyOf(dots);
    }

    /** Shows {@code dots} to {@code viewer} alone, one dust particle each. Call on the viewer's thread. */
    public static void draw(Player viewer, World world, List<Dot> dots, Particle.DustOptions dust) {
        for (Dot dot : dots) {
            viewer.spawnParticle(Particle.DUST, new Location(world, dot.x(), dot.y(), dot.z()),
                    1, 0, 0, 0, 0, dust);
        }
    }
}
