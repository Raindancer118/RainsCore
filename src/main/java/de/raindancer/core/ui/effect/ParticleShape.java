package de.raindancer.core.ui.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Where a worn particle goes around a player — offsets from their feet, per tick of an animation.
 * Pure maths; {@link ParticleShows} puts them in the world.
 */
public enum ParticleShape {

    /** Scattered loosely over the body, the way vanilla shows a potion effect. */
    AMBIENT("Like a potion effect"),
    /** Two arms circling the body, bobbing up and down. */
    AURA("Around you"),
    /** A ring above the head. */
    HALO("Above your head"),
    /** At the heels, just behind. */
    TRAIL("Behind you"),
    /** One point climbing round the body, feet to head, again and again. */
    SPIRAL("Spiralling up");

    public static final double AURA_RADIUS = 0.6;
    private static final double HALO_RADIUS = 0.35;

    private final String title;

    ParticleShape(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }

    /** The lightest drawing of each shape, as it always was before density meant anything to it. */
    public List<double[]> offsets(long tick, float yaw) {
        return offsets(tick, yaw, 1);
    }

    /**
     * @param tick    how many times this has been drawn — what makes the aura and halo turn
     * @param yaw     the wearer's yaw in degrees, Minecraft's: 0 faces south (+z), 90 west (-x)
     * @param density how many points the shape is drawn with, 1 the lightest. Denser is more points
     *                <em>along</em> the shape — a ring of three looks like a triangle, a ring of
     *                twenty-four like a ring — never more particles piled on the same point
     * @return {x, y, z} offsets from the wearer's feet, one particle each
     */
    public List<double[]> offsets(long tick, float yaw, int density) {
        int d = Math.clamp(density, 1, 20);
        List<double[]> points = new ArrayList<>();
        switch (this) {
            case AURA -> {
                // Two arms, each a short arc trailing behind its head as it circles.
                double angle = tick * 0.5;
                double height = 1.0 + 0.8 * Math.sin(tick * 0.3);
                for (int side = 0; side < 2; side++) {
                    for (int k = 0; k < d; k++) {
                        double at = angle + side * Math.PI - k * 0.22;
                        points.add(new double[]{Math.cos(at) * AURA_RADIUS, height, Math.sin(at) * AURA_RADIUS});
                    }
                }
            }
            case HALO -> {
                int around = 6 * d;
                for (int point = 0; point < around; point++) {
                    double at = tick * 0.3 + point * (2 * Math.PI / around);
                    points.add(new double[]{Math.cos(at) * HALO_RADIUS, 2.2, Math.sin(at) * HALO_RADIUS});
                }
            }
            case AMBIENT -> {
                for (int point = 0; point < 2 * d; point++) {
                    // Scattered, but the same scatter for the same tick: a test can pin it.
                    long seed = tick * 31 + point * 17;
                    points.add(new double[]{(unit(seed) - 0.5) * 0.8, 0.2 + unit(seed + 7) * 1.6,
                            (unit(seed + 13) - 0.5) * 0.8});
                }
            }
            case SPIRAL -> {
                // The climbing point with a tail along the path it just came up, so a dense spiral
                // reads as a line winding round the body rather than a dot.
                for (int k = 0; k < d; k++) {
                    double back = k * 0.35;
                    double at = tick * 0.6 - back;
                    double climbed = ((tick % 20) - back / 0.6) / 20.0;
                    if (climbed < 0) {
                        climbed += 1;
                    }
                    points.add(new double[]{Math.cos(at) * AURA_RADIUS, 0.1 + climbed * 1.9,
                            Math.sin(at) * AURA_RADIUS});
                }
            }
            case TRAIL -> {
                // A short arc behind the heels, widening with density.
                double radians = Math.toRadians(yaw);
                for (int k = 0; k < d; k++) {
                    double spread = d == 1 ? 0 : (k / (double) (d - 1) - 0.5) * 0.9;
                    double at = radians + spread;
                    points.add(new double[]{Math.sin(at) * 0.5, 0.15, -Math.cos(at) * 0.5});
                }
            }
        }
        return points;
    }

    /** A number in [0, 1) that looks random and is the same for the same seed. */
    private static double unit(long seed) {
        long mixed = seed * 0x9E3779B97F4A7C15L;
        mixed ^= mixed >>> 31;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 29;
        return (mixed >>> 11) * 0x1.0p-53;
    }

    /** The shape a typed word names, if any. */
    public static Optional<ParticleShape> of(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        for (ParticleShape shape : values()) {
            if (shape.name().equalsIgnoreCase(typed.trim())) {
                return Optional.of(shape);
            }
        }
        return Optional.empty();
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
