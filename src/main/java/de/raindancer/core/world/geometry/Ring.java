package de.raindancer.core.world.geometry;

import java.util.ArrayList;
import java.util.List;

/**
 * Spots evenly around one circle, each facing its middle — where a game puts its players at the start.
 *
 * <p>The circle is exactly as large as it has to be for neighbours to stand {@code spacing} blocks apart
 * (the straight line between them, which is what players see), and never smaller than {@code minRadius},
 * so two or three players do not start on top of the centre and each other. Plain numbers in and out;
 * the caller turns spots into locations on the ground.
 */
public final class Ring {

    /** One place to stand, and the Minecraft yaw that faces the middle from there. */
    public record Spot(double x, double z, float yaw) {
    }

    private Ring() {
    }

    /**
     * @param count     how many people need a place
     * @param spacing   the gap, in blocks, between neighbours
     * @param minRadius the smallest circle, however few people there are
     */
    public static List<Spot> around(double centreX, double centreZ, int count, double spacing,
                                    double minRadius) {
        if (count <= 0) {
            return List.of();
        }
        double needed = count == 1 ? 0 : spacing / (2 * Math.sin(Math.PI / count));
        double radius = Math.max(minRadius, needed);
        List<Spot> spots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double angle = 2 * Math.PI * i / count;
            double x = centreX + radius * Math.cos(angle);
            double z = centreZ + radius * Math.sin(angle);
            spots.add(new Spot(x, z, yawTowards(centreX - x, centreZ - z)));
        }
        return List.copyOf(spots);
    }

    /** Minecraft's yaw for looking along {@code (dx, dz)}: 0 is +Z, 90 is -X. */
    private static float yawTowards(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }
}
