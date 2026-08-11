package de.raindancer.core.world.teleport;

import java.util.Random;

/**
 * Somewhere different each time, within a ring around a middle.
 *
 * <h2>Why this is Core's</h2>
 * It was written once for the farm worlds, where sending everybody to the same coordinates strips the
 * ground bare within a day. A random teleport wants exactly the same arithmetic — somewhere within a
 * minimum and a maximum distance of a centre, picked so the outer part of the ring is not starved of
 * arrivals — and a second module writing its own copy is the wheel {@code ReuseTest} exists to catch.
 * Moved here on the strength of the second consumer, the same bar every other extraction in
 * {@code MODULE-LAYOUT.md} was held to.
 *
 * <h2>Why the picking is its own class</h2>
 * Because it is arithmetic that is wrong in a way nobody sees. Picked naively — an angle and a radius
 * both uniform — arrivals cluster hard at the middle: half of them land within half the radius, which
 * covers a quarter of the ground. That is the bug this class exists to not have, and the only way to
 * know it is absent is to draw thousands of points and count them, which needs no server and therefore
 * happens on every build.
 *
 * @param enabled  whether to scatter at all; off, everybody lands at the middle
 * @param nearest  how close to the middle anybody may land. Not zero by default in most uses: the
 *                 middle is often where the portals, the roads or whatever an admin built are
 * @param furthest how far out anybody may land. Also how much of the world gets generated over the
 *                 life of whatever is using this, which is disk nobody notices until it is full
 */
public record Scatter(boolean enabled, int nearest, int furthest) {

    /**
     * How far out this may reach at all.
     *
     * <p>Not a technical limit — it is a hundred thousand blocks of terrain the server would generate
     * one arrival at a time, and a number typed with an extra zero is how that happens by accident.
     */
    public static final int FURTHEST_ALLOWED = 100_000;

    /** The smallest a ring worth scattering in can be. */
    public static final int NEAREST_ALLOWED = 16;

    /**
     * How much room to leave inside a world border.
     *
     * <p>Landing against the border wall is landing somewhere a player cannot walk in three of four
     * directions, which reads as a broken teleport rather than as a border.
     */
    public static final int BORDER_MARGIN = 32;

    /** Everybody at the middle, wherever that is. */
    public static final Scatter NOWHERE = new Scatter(false, 0, NEAREST_ALLOWED);

    public Scatter {
        furthest = Math.max(NEAREST_ALLOWED, Math.min(FURTHEST_ALLOWED, furthest));
        nearest = Math.max(0, Math.min(FURTHEST_ALLOWED, nearest));
        if (nearest >= furthest) {
            // Swapped, or set to the same number. Refusing would mean a config typo that switches
            // arrivals off, and an owner who typed the two the other way round wants a ring either way
            // round — so the pair is read as the range it obviously means.
            int low = Math.min(nearest, furthest);
            int high = Math.max(nearest, furthest);
            nearest = low == high ? Math.max(0, high - NEAREST_ALLOWED) : low;
            furthest = high;
        }
    }

    /** Whether anybody is scattered at all. */
    public boolean isOn() {
        return enabled && furthest > nearest;
    }

    /**
     * The same, kept inside a world border.
     *
     * <p>A border of five thousand and a scatter radius of eight thousand would put most arrivals
     * outside the wall, where the server refuses to move them — a teleport that fails for a reason
     * nothing on screen could explain. Clamped rather than refused, because the two numbers are set in
     * different places by different people at different times.
     *
     * @param borderRadius how far from the middle the world goes, or null for no border
     */
    public Scatter within(Integer borderRadius) {
        if (borderRadius == null || borderRadius <= 0) {
            return this;
        }
        int room = borderRadius - BORDER_MARGIN;
        if (room <= 0) {
            // A border smaller than the margin. There is nowhere to scatter to, and pretending
            // otherwise means arrivals against the wall — so this becomes "at the middle".
            return new Scatter(false, 0, NEAREST_ALLOWED);
        }
        if (furthest <= room) {
            return this;
        }
        return new Scatter(enabled, Math.min(nearest, Math.max(0, room - NEAREST_ALLOWED)), room);
    }

    /**
     * Somewhere to land, as block offsets from the middle.
     *
     * <h2>Why the radius comes out of a square root</h2>
     * Because a ring twice as far out holds twice as much ground. Drawing the radius straight from a
     * uniform generator would put as many arrivals in the innermost hundred blocks as in the outermost
     * hundred, which is the clustering this class exists to avoid — and the square root is what makes
     * every square metre of the ring equally likely.
     *
     * @param random passed in rather than taken, so that a test can hand in a known sequence and this
     *               stays a function of its arguments
     */
    public Point pick(Random random) {
        if (!isOn() || random == null) {
            return new Point(0, 0);
        }
        double angle = random.nextDouble() * 2 * Math.PI;
        double inner = (double) nearest * nearest;
        double outer = (double) furthest * furthest;
        double radius = Math.sqrt(inner + random.nextDouble() * (outer - inner));
        return new Point((int) Math.round(Math.cos(angle) * radius),
                (int) Math.round(Math.sin(angle) * radius));
    }

    /** Somewhere on the flat, in blocks. Not a {@code Location}: nothing here needs a world. */
    public record Point(int x, int z) {

        /** How far from the middle, for a line that tells somebody where they ended up. */
        public int distance() {
            return (int) Math.round(Math.sqrt((double) x * x + (double) z * z));
        }
    }
}
