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
    SPIRAL("Spiralling up"),
    /** Feathered angel wings on the back, turning with the wearer and slowly flapping. */
    WINGS("Angel wings"),
    /** Pointed bat wings: a membrane stretched between finger bones. */
    BAT_WINGS("Bat wings"),
    /** Two lobes a side, fluttering quickly. */
    BUTTERFLY_WINGS("Butterfly wings"),
    /** Long narrow blades, beating so fast they blur. */
    HUMMINGBIRD_WINGS("Hummingbird wings");

    /*
     * One wing's outline per kind, seen from behind: {sideways from the spine, height}. Walked from the
     * shoulder up and out to the tip, then back in along the lower edge to the waist.
     */
    private static final double[][] ANGEL_WING = {
            {0.12, 1.45}, {0.35, 1.80}, {0.65, 2.10}, {1.00, 2.32}, {1.40, 2.45},
            {1.22, 2.15}, {1.32, 1.95}, {1.08, 1.82}, {1.16, 1.58}, {0.90, 1.50},
            {0.94, 1.24}, {0.66, 1.20}, {0.62, 0.96}, {0.38, 1.02}, {0.12, 1.15}};
    private static final double[][] BAT_WING = {
            {0.12, 1.50}, {0.45, 1.92}, {0.85, 2.18}, {1.35, 2.32}, {1.12, 1.95},
            {1.22, 1.55}, {0.95, 1.50}, {0.88, 1.12}, {0.64, 1.28}, {0.46, 0.92},
            {0.30, 1.18}, {0.12, 1.22}};
    private static final double[][] BUTTERFLY_WING = {
            {0.10, 1.50}, {0.28, 1.95}, {0.58, 2.28}, {0.95, 2.35}, {1.18, 2.12},
            {1.12, 1.80}, {0.80, 1.55}, {0.98, 1.38}, {0.95, 1.05}, {0.68, 0.82},
            {0.36, 0.92}, {0.10, 1.30}};
    private static final double[][] HUMMINGBIRD_WING = {
            {0.10, 1.48}, {0.40, 1.72}, {0.75, 1.98}, {1.10, 2.22}, {1.30, 2.36},
            {1.20, 2.18}, {0.95, 1.92}, {0.70, 1.68}, {0.42, 1.48}, {0.10, 1.36}};

    /*
     * Lines inside a wing: {from, to} as indices into its outline. A bat's finger bones run from the
     * wrist to each spike; an angel's quills from the shoulder to each feather tip; a butterfly's veins
     * from the body out across both lobes.
     */
    private static final int[][] NO_BONES = {};
    private static final int[][] BAT_BONES = {{1, 3}, {1, 5}, {1, 7}, {1, 9}};
    private static final int[][] ANGEL_QUILLS = {{0, 4}, {0, 6}, {0, 8}, {0, 10}, {0, 12}};
    private static final int[][] BUTTERFLY_VEINS = {{0, 3}, {0, 5}, {0, 8}, {0, 9}};

    /** From this density up, wings are drawn with their inside lines as well — what ops get as Ultra. */
    public static final int ULTRA = 10;

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
     * @return {x, y, z, along} — offsets from the wearer's feet, one particle each, and how far along
     *         the shape the point is (0 to 1), which is where it sits in a gradient. Wings run spine to tip
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
                        points.add(new double[]{Math.cos(at) * AURA_RADIUS, height, Math.sin(at) * AURA_RADIUS,
                                d == 1 ? 0 : k / (double) (d - 1)});
                    }
                }
            }
            case HALO -> {
                int around = 6 * d;
                for (int point = 0; point < around; point++) {
                    double at = tick * 0.3 + point * (2 * Math.PI / around);
                    // There and back round the ring, so a gradient meets itself without a seam.
                    double there = 1 - Math.abs(2.0 * point / around - 1);
                    points.add(new double[]{Math.cos(at) * HALO_RADIUS, 2.2, Math.sin(at) * HALO_RADIUS, there});
                }
            }
            case AMBIENT -> {
                for (int point = 0; point < 2 * d; point++) {
                    // Scattered, but the same scatter for the same tick: a test can pin it.
                    long seed = tick * 31 + point * 17;
                    double up = unit(seed + 7);
                    points.add(new double[]{(unit(seed) - 0.5) * 0.8, 0.2 + up * 1.6,
                            (unit(seed + 13) - 0.5) * 0.8, up});
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
                            Math.sin(at) * AURA_RADIUS, Math.min(1, climbed)});
                }
            }
            // Quills and veins only at the highest densities: with few points the inside lines would
            // take them from the outline, and the outline is what reads as a wing.
            case WINGS -> wings(points, ANGEL_WING, 3, d >= ULTRA ? ANGEL_QUILLS : NO_BONES, tick, yaw, d,
                    0.25, 0.45, 0.15);
            // One pass only: a bat's spikes are the point of it.
            case BAT_WINGS -> wings(points, BAT_WING, 1, BAT_BONES, tick, yaw, d, 0.3, 0.4, 0.18);
            case BUTTERFLY_WINGS -> wings(points, BUTTERFLY_WING, 3, d >= ULTRA ? BUTTERFLY_VEINS : NO_BONES,
                    tick, yaw, d, 0.55, 0.35, 0.25);
            // About a beat every two ticks — as fast as anything drawn this often can show.
            case HUMMINGBIRD_WINGS -> wings(points, HUMMINGBIRD_WING, 2, NO_BONES, tick, yaw, d, 2.6, 0.55, 0.5);
            case TRAIL -> {
                // A short arc behind the heels, widening with density.
                double radians = Math.toRadians(yaw);
                for (int k = 0; k < d; k++) {
                    double spread = d == 1 ? 0 : (k / (double) (d - 1) - 0.5) * 0.9;
                    double at = radians + spread;
                    points.add(new double[]{Math.sin(at) * 0.5, 0.15, -Math.cos(at) * 0.5,
                            d == 1 ? 0 : k / (double) (d - 1)});
                }
            }
        }
        return points;
    }

    /** Whether this is one of the kinds of wings, which a menu offers together. */
    public boolean isWings() {
        return this == WINGS || this == BAT_WINGS || this == BUTTERFLY_WINGS || this == HUMMINGBIRD_WINGS;
    }

    /** Every kind of wings, in the order a menu lists them. */
    public static List<ParticleShape> wings() {
        return java.util.Arrays.stream(values()).filter(ParticleShape::isWings).toList();
    }

    /**
     * Both wings of one kind, mirrored on the wearer's back.
     *
     * @param flapRate how fast they beat; {@code sweep ± swing} radians is how far back they fold.
     *                 Kept small: dust lingers up to two seconds, so a wide beat smears a wing into a cloud
     */
    private static void wings(List<double[]> points, double[][] corners, int smoothing, int[][] bones, long tick,
                              float yaw, int density, double flapRate, double sweep, double swing) {
        double[][] outline = rounded(corners, smoothing);
        double radians = Math.toRadians(yaw);
        double backX = Math.sin(radians);
        double backZ = -Math.cos(radians);
        double rightX = -Math.cos(radians);
        double rightZ = -Math.sin(radians);
        double folded = sweep + swing * Math.sin(tick * flapRate);
        List<double[]> drawn = new ArrayList<>(alongOutline(outline, 12 * density));
        drawn.addAll(filling(outline, density));
        for (int[] bone : bones) {
            // Ends left out: they are already on the outline, and a point drawn twice is the
            // "more particles on one spot" density is not supposed to mean.
            double[] from = corners[bone[0]];
            double[] to = corners[bone[1]];
            int along = 2 * density;
            for (int k = 1; k <= along; k++) {
                double t = k / (double) (along + 1);
                drawn.add(new double[]{from[0] + (to[0] - from[0]) * t, from[1] + (to[1] - from[1]) * t});
            }
        }
        double spine = Double.MAX_VALUE;
        double tip = 0;
        for (double[] corner : corners) {
            spine = Math.min(spine, corner[0]);
            tip = Math.max(tip, corner[0]);
        }
        for (double[] at : drawn) {
            double outward = (at[0] - spine) / (tip - spine);
            double out = at[0] * Math.cos(folded);
            double back = 0.3 + at[0] * Math.sin(folded);
            for (int side = -1; side <= 1; side += 2) {
                points.add(new double[]{side * out * rightX + back * backX, at[1],
                        side * out * rightZ + back * backZ, Math.clamp(outward, 0, 1)});
            }
        }
    }

    /**
     * Rounds an outline by cutting every corner {@code passes} times (Chaikin): each edge keeps its
     * middle half, so a zigzag becomes a curve. The first and last points stay — they sit on the spine.
     */
    private static double[][] rounded(double[][] outline, int passes) {
        double[][] current = outline;
        for (int pass = 0; pass < passes; pass++) {
            List<double[]> next = new ArrayList<>();
            next.add(current[0]);
            for (int i = 0; i < current.length - 1; i++) {
                double[] a = current[i];
                double[] b = current[i + 1];
                next.add(new double[]{0.75 * a[0] + 0.25 * b[0], 0.75 * a[1] + 0.25 * b[1]});
                next.add(new double[]{0.25 * a[0] + 0.75 * b[0], 0.25 * a[1] + 0.75 * b[1]});
            }
            next.add(current[current.length - 1]);
            current = next.toArray(double[][]::new);
        }
        return current;
    }

    /**
     * An even grid of points inside the outline (closed back to its first point), finer as density
     * rises — about the same spacing as along the edge, so the fill and the outline read as one.
     */
    private static List<double[]> filling(double[][] outline, int density) {
        double step = 0.24 / Math.sqrt(density);
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] p : outline) {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
        }
        List<double[]> inside = new ArrayList<>();
        // Rows offset by half a step, so the fill is a mesh of triangles rather than a chessboard.
        int row = 0;
        for (double y = minY + step / 2; y < maxY; y += step * 0.87, row++) {
            for (double x = minX + (row % 2 == 0 ? step / 2 : step); x < maxX; x += step) {
                if (isInside(outline, x, y) && distanceToEdge(outline, x, y) > step * 0.45) {
                    inside.add(new double[]{x, y});
                }
            }
        }
        return inside;
    }

    /** Even-odd ray test against the closed outline. */
    private static boolean isInside(double[][] outline, double x, double y) {
        boolean in = false;
        for (int i = 0, j = outline.length - 1; i < outline.length; j = i++) {
            double[] a = outline[i];
            double[] b = outline[j];
            if ((a[1] > y) != (b[1] > y) && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) {
                in = !in;
            }
        }
        return in;
    }

    /** How far a point is from the nearest edge, so the fill leaves the outline its own row. */
    private static double distanceToEdge(double[][] outline, double x, double y) {
        double nearest = Double.MAX_VALUE;
        for (int i = 0, j = outline.length - 1; i < outline.length; j = i++) {
            double[] a = outline[j];
            double[] b = outline[i];
            double dx = b[0] - a[0];
            double dy = b[1] - a[1];
            double length = dx * dx + dy * dy;
            double t = length == 0 ? 0 : Math.clamp(((x - a[0]) * dx + (y - a[1]) * dy) / length, 0, 1);
            nearest = Math.min(nearest, Math.hypot(x - (a[0] + t * dx), y - (a[1] + t * dy)));
        }
        return nearest;
    }

    /** {@code count} points spread evenly along a polyline, by length — an outline drawn, not its corners. */
    private static List<double[]> alongOutline(double[][] outline, int count) {
        double[] lengths = new double[outline.length - 1];
        double total = 0;
        for (int i = 0; i < lengths.length; i++) {
            lengths[i] = Math.hypot(outline[i + 1][0] - outline[i][0], outline[i + 1][1] - outline[i][1]);
            total += lengths[i];
        }
        List<double[]> points = new ArrayList<>(count);
        for (int k = 0; k < count; k++) {
            double wanted = total * k / Math.max(1, count - 1);
            int segment = 0;
            while (segment < lengths.length - 1 && wanted > lengths[segment]) {
                wanted -= lengths[segment];
                segment++;
            }
            double t = lengths[segment] == 0 ? 0 : Math.min(1, wanted / lengths[segment]);
            double[] from = outline[segment];
            double[] to = outline[segment + 1];
            points.add(new double[]{from[0] + (to[0] - from[0]) * t, from[1] + (to[1] - from[1]) * t});
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
