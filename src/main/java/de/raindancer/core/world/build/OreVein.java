package de.raindancer.core.world.build;

import de.raindancer.core.world.safety.Spot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The shape of an ore vein: which blocks it would occupy, and nothing about placing them.
 *
 * <h2>Why the shape is its own thing</h2>
 * Because it is the half worth testing and the half with the surprises in it. A vein has to look like
 * a vein — a lump, not a sphere and not a cube — it has to contain the number of blocks it was asked
 * for, and it must never wander so far from its centre that somebody standing at the spot they aimed
 * at cannot find it. All three are arithmetic, and arithmetic is testable without a server.
 *
 * <h2>How the lump is made</h2>
 * A random walk from the centre that prefers to stay near it: each new block is grown off one already
 * in the vein, so the result is always connected — which is what makes it read as one vein rather than
 * as a scatter of ore in a box. The walk is seeded, so the same seed gives the same vein and a test
 * can assert on an exact shape rather than on a distribution.
 *
 * @param blocks every position the vein occupies, the centre first
 */
public record OreVein(List<Spot> blocks) {

    /** Sixty-four is two double chests of ore, which is already an unreasonable gift. */
    public static final int MOST_BLOCKS = 64;

    public OreVein {
        blocks = List.copyOf(blocks);
    }

    public int size() {
        return blocks.size();
    }

    /**
     * A lump of {@code size} blocks grown around {@code centre}.
     *
     * @param size how many blocks; clamped to 1–{@value #MOST_BLOCKS}, because a vein of zero is a
     *             command that silently does nothing and a vein of ten thousand is a crater
     * @param seed the same seed gives the same vein
     */
    public static OreVein around(Spot centre, int size, long seed) {
        int wanted = Math.max(1, Math.min(MOST_BLOCKS, size));
        Random random = new Random(seed);

        Set<Spot> taken = new LinkedHashSet<>();
        taken.add(centre);
        List<Spot> grown = new ArrayList<>();
        grown.add(centre);

        // Grown off a block already in the vein, so it is connected by construction. Fifty tries per
        // block before giving up: in a space this small the walk can box itself in, and a vein one
        // block short is better than a loop nobody can see the end of.
        int attempts = 0;
        while (taken.size() < wanted && attempts < wanted * 50) {
            attempts++;
            Spot from = grown.get(random.nextInt(grown.size()));
            Spot next = switch (random.nextInt(6)) {
                case 0 -> from.offset(1, 0, 0);
                case 1 -> from.offset(-1, 0, 0);
                case 2 -> from.offset(0, 1, 0);
                case 3 -> from.offset(0, -1, 0);
                case 4 -> from.offset(0, 0, 1);
                default -> from.offset(0, 0, -1);
            };
            // Kept within reach of where it was aimed. Without this the walk drifts, and a vein whose
            // far end is twelve blocks away is one the person who placed it cannot find.
            if (next.distanceSquaredTo(centre) > reach(wanted)) {
                continue;
            }
            if (taken.add(next)) {
                grown.add(next);
            }
        }
        return new OreVein(List.copyOf(taken));
    }

    /**
     * How far from the centre a vein of this size may reach, squared.
     *
     * <p>Grows with the cube root of the size, because the vein is a lump in three dimensions: a
     * radius proportional to the count would make a big vein a thin thread across the map.
     */
    private static long reach(int size) {
        int radius = Math.max(2, (int) Math.ceil(Math.cbrt(size)) + 1);
        return (long) radius * radius;
    }
}
