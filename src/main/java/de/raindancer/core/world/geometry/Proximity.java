package de.raindancer.core.world.geometry;

import org.bukkit.Location;

import java.util.Objects;

/**
 * How far apart two places are — and whether that question even makes sense.
 *
 * <h2>Why {@code sameWorld} is checked first, always</h2>
 * {@link Location#distance(Location)} throws {@link IllegalArgumentException} across worlds, and the
 * raw coordinate numbers can be close by coincidence — the nether and the overworld both have a
 * spawn near {@code 0, 64, 0}. A caller that forgets the check either crashes on the first cross-world
 * comparison or, worse, silently believes two players are next to each other when one of them is in a
 * different dimension entirely. So every method here checks worlds before it touches a coordinate, and
 * documents what "not the same world" means for that answer rather than leaving it to guesswork.
 */
public final class Proximity {

    private Proximity() {
    }

    /**
     * Full 3D distance, in blocks.
     *
     * @return the distance, or {@link Double#POSITIVE_INFINITY} when {@code a} and {@code b} are not
     *         in the same world — they are not "far apart", they are not comparable at all, and
     *         infinity is what keeps a naive {@code < max} check from accidentally saying otherwise
     */
    public static double horizontal(Location a, Location b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        if (!sameWorld(a, b)) {
            return Double.POSITIVE_INFINITY;
        }
        return a.distance(b);
    }

    /**
     * Distance on the XZ plane only — how far apart on the ground, ignoring height.
     *
     * <p>For "did they walk away from each other" rather than "did they fall". Two players ten blocks
     * up and down a cliff face are not separated by this measure the way {@link #horizontal} would
     * count them.
     *
     * @return the flat distance, or {@link Double#POSITIVE_INFINITY} across worlds — see {@link #horizontal}
     */
    public static double flat(Location a, Location b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        if (!sameWorld(a, b)) {
            return Double.POSITIVE_INFINITY;
        }
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Whether {@code a} and {@code b} are no more than {@code max} blocks apart, in 3D.
     *
     * <p>Always {@code false} across worlds — never true just because the raw coordinates happen to
     * be small in both.
     */
    public static boolean within(Location a, Location b, double max) {
        if (!sameWorld(a, b)) {
            return false;
        }
        return horizontal(a, b) <= max;
    }

    /**
     * Whether two locations are in the same world at all — the question every other method here
     * answers first, because a coordinate is meaningless without it.
     */
    public static boolean sameWorld(Location a, Location b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        return a.getWorld() != null && a.getWorld().equals(b.getWorld());
    }
}
