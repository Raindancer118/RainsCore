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
    HUMMINGBIRD_WINGS("Hummingbird wings"),
    /** Big, bony and spiked: a membrane between long finger bones, a claw at the top. */
    DRAGON_WINGS("Dragon wings"),
    /** Wide feathers that flare up into long tips — made to be drawn in flame. */
    PHOENIX_WINGS("Phoenix wings"),
    /** Two slim pairs: a long upper wing and a small lower one, fluttering. */
    FAIRY_WINGS("Fairy wings"),
    /** Two pairs of angel wings, one above the other. */
    SERAPH_WINGS("Seraph wings"),
    /** Big angel wings arching over the head, their long feathers hanging almost to the feet. */
    GRAND_WINGS("Grand angel wings");

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
    private static final double[][] DRAGON_WING = {
            {0.12, 1.55}, {0.45, 2.05}, {0.85, 2.50}, {1.25, 2.75}, {1.65, 2.85},
            {1.50, 2.45}, {1.75, 2.00}, {1.35, 1.95}, {1.50, 1.45}, {1.10, 1.50},
            {1.15, 1.00}, {0.75, 1.15}, {0.60, 0.75}, {0.35, 1.05}, {0.12, 1.20}};
    private static final double[][] PHOENIX_WING = {
            {0.12, 1.50}, {0.40, 1.80}, {0.70, 2.05}, {0.95, 2.45}, {1.00, 2.85},
            {1.12, 2.40}, {1.30, 2.62}, {1.32, 2.20}, {1.55, 2.32}, {1.45, 1.95},
            {1.62, 1.72}, {1.30, 1.55}, {1.36, 1.25}, {1.00, 1.30}, {0.95, 1.00},
            {0.65, 1.10}, {0.55, 0.80}, {0.35, 1.00}, {0.12, 1.20}};
    private static final double[][] FAIRY_UPPER = {
            {0.10, 1.55}, {0.30, 1.95}, {0.55, 2.35}, {0.85, 2.60}, {1.10, 2.55},
            {1.12, 2.25}, {0.85, 1.85}, {0.45, 1.58}, {0.10, 1.45}};
    private static final double[][] FAIRY_LOWER = {
            {0.10, 1.35}, {0.40, 1.30}, {0.72, 1.12}, {0.90, 0.85}, {0.78, 0.68},
            {0.50, 0.80}, {0.25, 1.05}, {0.10, 1.25}};

    /**
     * Lunar Client's wings, traced from a picture players sent rather than drawn by hand: one wing seen from
     * behind, one character per 0.04 blocks — {@code .} is open air, a digit is wing, 0 the darkest shade of
     * its feathers and 9 the brightest. Row 0 is {@link #GRAND_TOP} blocks above the feet, column 0 the spine.
     * The shading becomes the place in the gradient, so the feathers keep their texture in any two colours.
     */
    private static final String[] GRAND_MASK = {
".............1332",
".............1443210",
"............34554321",
"...........25437775421",
"..........135447886542",
".........2457999998886532",
"........03458998999997743210",
".......123459998899998855332",
".......13467987665789997754310",
".......24589987555689997765421",
".......34999854999567899876532",
".......359986669987766687678542",
".......569875899879854376589553",
".......7876689976578888569999852",
".......8875599976677888579999852",
"....23477744777589555788878897433",
"...2347665666557897777655678887540",
"...34696538853499999965345788896510",
"...57877889998878988877899655796532",
"..245677898889878977788999766696532",
".343125777555788875557778999975893310",
".785348655664567767776657899876885520",
"..8645964366356765877553579978988983",
"..975698753599854398777577777664497432",
"...976.7764579964488888667777653497543",
"...975..97555897657899886577543555554300",
"...9....9975.798776795687666654665544410",
".........986.67777578..98756777985434532",
"..........97....79753...7855478.36545532",
"...........87....6853...5655378..76555331",
"............9.....964.....87543...47654421",
"............9.....997.....56643....6555532",
"............6.....888......694332...67331",
"...................68......696542...673310",
"...................69.......98753...88331",
"...................7996......7875...88432",
".....................97......8975...78543",
".....................96......89789....543",
".....................96.......5899....543",
".....................5.........999....753",
"...............................999....863",
"...............................987....975",
"...............................9......965",
"...............................6......965",
".......................................68",
".......................................79",
".......................................89",
"........................................6",
    };
    private static final double GRAND_TOP = 1.94;
    private static final double MASK_CELL = 0.04;

    /*
     * Seraph's two pairs, worked out once. They used to be moved on every draw, which made a new outline —
     * and a new entry in the layout cache — every tick for as long as anybody wore them.
     */
    private static final double[][] SERAPH_UPPER = moved(ANGEL_WING, 1.0, 0.35);
    private static final double[][] SERAPH_LOWER = moved(ANGEL_WING, 0.75, -0.7);

    private static final int[][] NO_BONES = {};
    private static final int[][] DRAGON_BONES = {{1, 4}, {1, 6}, {1, 8}, {1, 10}, {1, 12}};
    private static final int[][] BAT_BONES = {{1, 3}, {1, 5}, {1, 7}, {1, 9}};
    private static final int[][] ANGEL_QUILLS = {{0, 4}, {0, 6}, {0, 8}, {0, 10}, {0, 12}};
    private static final int[][] BUTTERFLY_VEINS = {{0, 3}, {0, 5}, {0, 8}, {0, 9}};

    /** How far every kind of wings sits below where its outline is drawn — set by eye, in game. */
    private static final double LOWERED = 0.1;

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
            // Slow and heavy: a dragon's wing is big, and a fast beat on it reads as a bird.
            case DRAGON_WINGS -> wings(points, DRAGON_WING, 1, DRAGON_BONES, tick, yaw, d, 0.18, 0.4, 0.15);
            // One pass of rounding only, so the flame tips stay pointed.
            case PHOENIX_WINGS -> wings(points, PHOENIX_WING, 1, NO_BONES, tick, yaw, d, 0.22, 0.45, 0.15);
            case FAIRY_WINGS -> {
                wings(points, FAIRY_UPPER, 3, NO_BONES, tick, yaw, d, 0.9, 0.35, 0.25);
                wings(points, FAIRY_LOWER, 3, NO_BONES, tick, yaw, d, 0.9, 0.5, 0.25);
            }
            // Slow and wide, as Lunar's beat them.
            case GRAND_WINGS -> traced(points, GRAND_MASK, GRAND_TOP, tick, yaw, d, 0.2, 0.3, 0.12);
            case SERAPH_WINGS -> {
                // The upper pair raised and spread, the lower one smaller and folded further back,
                // so the two never draw over each other.
                wings(points, SERAPH_UPPER, 3, d >= ULTRA ? ANGEL_QUILLS : NO_BONES, tick, yaw,
                        d, 0.25, 0.35, 0.12);
                wings(points, SERAPH_LOWER, 3, NO_BONES, tick + 6, yaw, d, 0.25, 0.8, 0.12);
            }
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

    /**
     * How far apart a wing's points are at this density, edge and fill alike — about one trail point
     * apart from Normal up, so the wing reads as a surface. Ultra goes finer still; below it the
     * spacing stops tightening, because a wing is redrawn every tick and every point is a particle
     * every viewer receives twenty times a second.
     */
    public static double wingStep(int density) {
        return density >= ULTRA ? 0.05 : Math.max(0.065, 0.135 / Math.sqrt(Math.max(1, density)));
    }

    /** An outline scaled out from the spine and moved up or down — a second pair of the same wing. */
    private static double[][] moved(double[][] outline, double scale, double up) {
        double[][] moved = new double[outline.length][];
        for (int i = 0; i < outline.length; i++) {
            double[] at = outline[i];
            // Scaled about the shoulder height, so a smaller pair still meets the back where it should.
            moved[i] = new double[]{at[0] * scale, 1.45 + (at[1] - 1.45) * scale + up};
        }
        return moved;
    }

    private static double length(double[][] polyline) {
        double total = 0;
        for (int i = 0; i < polyline.length - 1; i++) {
            total += Math.hypot(polyline[i + 1][0] - polyline[i][0], polyline[i + 1][1] - polyline[i][1]);
        }
        return total;
    }

    /** Whether this is one of the kinds of wings, which a menu offers together. */
    public boolean isWings() {
        return this == WINGS || this == BAT_WINGS || this == BUTTERFLY_WINGS || this == HUMMINGBIRD_WINGS
                || this == DRAGON_WINGS || this == PHOENIX_WINGS || this == FAIRY_WINGS || this == SERAPH_WINGS
                || this == GRAND_WINGS;
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
    /**
     * Each wing's points, laid out flat, by its outline and density — worked out once, since only the beat
     * and the wearer's facing change from one tick to the next, and the fill alone is hundreds of tests.
     */
    private static final java.util.Map<Layout, List<double[]>> LAYOUTS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * What a layout is worked out from. Arrays compare by identity in a record, which is the point: every
     * outline here is a constant, so the cache holds one entry per wing, part and density, and no more.
     */
    private record Layout(Object outline, int smoothing, Object bones, int density) {
    }

    /** How many layouts are cached — for a test that holds the cache to its bound. */
    static int cachedLayouts() {
        return LAYOUTS.size();
    }

    private static void wings(List<double[]> points, double[][] corners, int smoothing, int[][] bones, long tick,
                              float yaw, int density, double flapRate, double sweep, double swing) {
        List<double[]> flat = LAYOUTS.computeIfAbsent(new Layout(corners, smoothing, bones, density),
                ignored -> layout(corners, smoothing, bones, density));
        onTheBack(points, flat, tick, yaw, flapRate, sweep, swing);
    }

    /** A traced wing: its cells sampled at the density's spacing, every feather's edges always kept. */
    private static void traced(List<double[]> points, String[] mask, double top, long tick, float yaw, int density,
                               double flapRate, double sweep, double swing) {
        List<double[]> flat = LAYOUTS.computeIfAbsent(new Layout(mask, 0, null, density),
                ignored -> sampled(mask, top, density));
        onTheBack(points, flat, tick, yaw, flapRate, sweep, swing);
    }

    private static List<double[]> sampled(String[] mask, double top, int density) {
        // Ultra takes every traced cell; below it a staggered grid at the density's spacing, in cells but
        // not rounded to whole ones — rounded, Normal and Dense came out the same.
        boolean every = density >= ULTRA;
        double step = wingStep(density);
        double across = step / MASK_CELL;
        double down = across * 0.87;
        List<double[]> cells = new ArrayList<>();
        List<double[]> rim = new ArrayList<>();
        for (int row = 0; row < mask.length; row++) {
            for (int column = 0; column < mask[row].length(); column++) {
                if (mask[row].charAt(column) == '.') {
                    continue;
                }
                double nx = (air(mask, row, column + 1) ? 1 : 0) - (air(mask, row, column - 1) ? 1 : 0);
                double ny = (air(mask, row - 1, column) ? 1 : 0) - (air(mask, row + 1, column) ? 1 : 0);
                if (nx != 0 || ny != 0) {
                    double length = Math.hypot(nx, ny);
                    rim.add(new double[]{(column + 0.5) * MASK_CELL, top - row * MASK_CELL, nx / length, ny / length,
                            (9 - (mask[row].charAt(column) - '0')) / 9.0});
                }
            }
        }
        if (every) {
            for (int row = 0; row < mask.length; row++) {
                for (int column = 0; column < mask[row].length(); column++) {
                    if (mask[row].charAt(column) != '.') {
                        cells.add(new double[]{(column + 0.5) * MASK_CELL, top - row * MASK_CELL,
                                (9 - (mask[row].charAt(column) - '0')) / 9.0});
                    }
                }
            }
        } else {
            // Points at the grid's own heights and places, each taking the shade of the cell it falls in —
            // snapped to the cells, the rows came out one and two cells apart in turn, and the wing striped.
            for (int k = 0; k * down < mask.length - 0.5; k++) {
                String line = mask[(int) Math.round(k * down)];
                double offset = k % 2 == 0 ? 0 : across / 2;
                for (double at = offset; at < line.length() - 0.5; at += across) {
                    char shade = line.charAt((int) Math.round(at));
                    if (shade != '.') {
                        cells.add(new double[]{(at + 0.5) * MASK_CELL, top - k * down * MASK_CELL,
                                (9 - (shade - '0')) / 9.0});
                    }
                }
            }
        }
        return fluffed(cells, rim, step);
    }

    private static boolean air(String[] mask, int row, int column) {
        return row < 0 || row >= mask.length || column < 0 || column >= mask[row].length()
                || mask[row].charAt(column) == '.';
    }

    /** How far behind the back a point may sit beyond the others — what gives a wing body. */
    private static final double DEPTH = 0.1;

    /**
     * A wing made soft: a loose fringe of feathers just outside its rim, and every point at its own depth
     * behind the back, so the wing has body rather than being a sheet. The same for the same wing, so a
     * point never jumps about from one draw to the next.
     *
     * @param body {x, y, along} — the wing itself
     * @param rim  {x, y, outward x, outward y, along} — points on its edge, which way is out
     * @return {x, y, along, depth}
     */
    private static List<double[]> fluffed(List<double[]> body, List<double[]> rim, double step) {
        double spine = Double.MAX_VALUE;
        for (double[] at : body) {
            spine = Math.min(spine, at[0]);
        }
        List<double[]> all = new ArrayList<>(body);
        double nearest = step * 0.45;
        for (int i = 0; i < rim.size(); i++) {
            double[] at = rim.get(i);
            // Not along the spine: a fringe there would stand in the wearer's back.
            if (at[2] < -0.3 && at[0] < spine + 0.2) {
                continue;
            }
            double out = step * (0.5 + 0.5 * unit(i * 7L + 1));
            double sideways = step * (unit(i * 7L + 2) - 0.5) * 0.6;
            double x = at[0] + at[2] * out - at[3] * sideways;
            double y = at[1] + at[3] * out + at[2] * sideways;
            // Nor into the wearer's back, nor into the ground under a wing that reaches it.
            if (x <= spine || y < 0.05) {
                continue;
            }
            boolean crowded = false;
            for (double[] other : all) {
                if (Math.abs(other[0] - x) < nearest && Math.abs(other[1] - y) < nearest
                        && Math.hypot(other[0] - x, other[1] - y) < nearest) {
                    crowded = true;
                    break;
                }
            }
            if (!crowded) {
                all.add(new double[]{x, y, at[4]});
            }
        }
        List<double[]> fluffed = new ArrayList<>(all.size());
        for (int i = 0; i < all.size(); i++) {
            double[] at = all.get(i);
            fluffed.add(new double[]{at[0], at[1], at[2], DEPTH * unit(i * 13L + 5)});
        }
        return List.copyOf(fluffed);
    }

    /**
     * Flat points — {outward, height, along, depth} — set on the wearer's back as both wings, beating.
     */
    private static void onTheBack(List<double[]> points, List<double[]> flat, long tick, float yaw, double flapRate,
                                  double sweep, double swing) {
        double radians = Math.toRadians(yaw);
        double backX = Math.sin(radians);
        double backZ = -Math.cos(radians);
        double rightX = -Math.cos(radians);
        double rightZ = -Math.sin(radians);
        double folded = sweep + swing * Math.sin(tick * flapRate);
        for (double[] at : flat) {
            double out = at[0] * Math.cos(folded);
            // Not closer: the head reaches 0.25 behind the centre, and a turning head would cut through the wings.
            double back = 0.3 + at[3] + at[0] * Math.sin(folded);
            for (int side = -1; side <= 1; side += 2) {
                points.add(new double[]{side * out * rightX + back * backX, at[1],
                        side * out * rightZ + back * backZ, Math.clamp(at[2], 0, 1)});
            }
        }
    }

    /** One wing laid out flat: its edge, its fill and its bones, with no two points on top of each other. */
    private static List<double[]> layout(double[][] corners, int smoothing, int[][] bones, int density) {
        double[][] outline = rounded(corners, smoothing);
        double step = wingStep(density);
        List<double[]> edge = alongOutline(outline, (int) Math.ceil(length(outline) / step));
        List<double[]> drawn = new ArrayList<>(edge);
        List<double[][]> boneLines = new ArrayList<>();
        for (int[] bone : bones) {
            boneLines.add(new double[][]{corners[bone[0]], corners[bone[1]]});
        }
        drawn.addAll(filling(outline, boneLines, step));
        for (int[] bone : bones) {
            // Ends left out: they are already on the outline, and a point drawn twice is the
            // "more particles on one spot" density is not supposed to mean.
            double[] from = corners[bone[0]];
            double[] to = corners[bone[1]];
            int along = (int) Math.max(1, Math.floor(Math.hypot(to[0] - from[0], to[1] - from[1]) / step) - 1);
            for (int k = 1; k <= along; k++) {
                double t = k / (double) (along + 1);
                double bx = from[0] + (to[0] - from[0]) * t;
                double by = from[1] + (to[1] - from[1]) * t;
                // Where a bone meets the rounded edge the edge already has a point.
                if (distanceToEdge(outline, bx, by) > step * 0.45) {
                    drawn.add(new double[]{bx, by});
                }
            }
        }
        // Where two edges meet in a narrow notch their points come close; one of them is enough.
        double nearest = step * 0.4;
        List<double[]> kept = new ArrayList<>(drawn.size());
        for (double[] candidate : drawn) {
            boolean crowded = false;
            for (double[] other : kept) {
                if (Math.abs(other[0] - candidate[0]) < nearest && Math.abs(other[1] - candidate[1]) < nearest
                        && Math.hypot(other[0] - candidate[0], other[1] - candidate[1]) < nearest) {
                    crowded = true;
                    break;
                }
            }
            if (!crowded) {
                kept.add(candidate);
            }
        }
        double spine = Double.MAX_VALUE;
        double tip = 0;
        for (double[] corner : corners) {
            spine = Math.min(spine, corner[0]);
            tip = Math.max(tip, corner[0]);
        }
        List<double[]> body = new ArrayList<>(kept.size());
        for (double[] at : kept) {
            body.add(new double[]{at[0], at[1] - LOWERED, (at[0] - spine) / (tip - spine)});
        }
        List<double[]> rim = new ArrayList<>(edge.size());
        for (int i = 0; i < edge.size(); i++) {
            double[] before = edge.get(Math.max(0, i - 1));
            double[] after = edge.get(Math.min(edge.size() - 1, i + 1));
            double tx = after[0] - before[0];
            double ty = after[1] - before[1];
            double length = Math.hypot(tx, ty);
            if (length == 0) {
                continue;
            }
            double nx = ty / length;
            double ny = -tx / length;
            double[] at = edge.get(i);
            // Whichever way round the outline runs, out is where the wing is not.
            if (isInside(outline, at[0] + nx * 0.02, at[1] + ny * 0.02)) {
                nx = -nx;
                ny = -ny;
            }
            rim.add(new double[]{at[0], at[1] - LOWERED, nx, ny, (at[0] - spine) / (tip - spine)});
        }
        return fluffed(body, rim, step);
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
    private static List<double[]> filling(double[][] outline, List<double[][]> bones, double step) {
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
                if (isInside(outline, x, y) && distanceToEdge(outline, x, y) > step * 0.45
                        && clearOf(bones, x, y, step * 0.45)) {
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

    private static boolean clearOf(List<double[][]> bones, double x, double y, double gap) {
        for (double[][] bone : bones) {
            if (distanceToSegment(bone[0], bone[1], x, y) <= gap) {
                return false;
            }
        }
        return true;
    }

    private static double distanceToSegment(double[] a, double[] b, double x, double y) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double length = dx * dx + dy * dy;
        double t = length == 0 ? 0 : Math.clamp(((x - a[0]) * dx + (y - a[1]) * dy) / length, 0, 1);
        return Math.hypot(x - (a[0] + t * dx), y - (a[1] + t * dy));
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
