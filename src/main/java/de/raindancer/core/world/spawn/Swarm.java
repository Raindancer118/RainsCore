package de.raindancer.core.world.spawn;

import de.raindancer.core.world.safety.Spot;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Where the creatures of one pack go.
 *
 * <h2>Why a ring and not a scatter</h2>
 * Because a pack is aimed <em>at</em> somebody, and the two obvious shapes are both wrong. All of them
 * on one block is a pile that suffocates itself and lands as a single lump of damage nobody can react
 * to. A uniform scatter over a square puts half of them behind a wall and one inside the person who
 * pressed the button.
 *
 * <p>A ring at arm's length solves all three: they arrive around whoever they were aimed at, spread
 * evenly, none of them on top of anybody, and the person can see all of them at once — which is what
 * makes it read as an event rather than as a crash.
 *
 * <p>Seeded, so the same seed gives the same arrangement and a test can assert on exact positions
 * rather than on a distribution.
 */
public final class Swarm {

    /** Close enough to be one event, far enough not to be inside anybody. */
    public static final int NEAREST = 3;

    /** Beyond this they arrive out of sight, which is a lag spike rather than an event. */
    public static final int FURTHEST = 24;

    private Swarm() {
    }

    /**
     * {@code count} positions in a ring of {@code radius} around {@code centre}.
     *
     * <p>Evenly spaced by angle, with a little jitter so a pack does not arrive as a perfect circle —
     * which reads as a summoning ritual rather than as something turning up. The jitter never closes
     * the gap to the centre, so the nearest guarantee holds however the dice fall.
     *
     * @param radius clamped to {@value #NEAREST}–{@value #FURTHEST}
     */
    public static List<Spot> ring(Spot centre, int count, int radius, long seed) {
        int wanted = Math.max(1, count);
        int distance = Math.max(NEAREST, Math.min(FURTHEST, radius));
        Random random = new Random(seed);

        List<Spot> spots = new ArrayList<>(wanted);
        for (int index = 0; index < wanted; index++) {
            double angle = (2 * Math.PI * index) / wanted + random.nextDouble() * 0.3;
            // Outward only: pulling one inward could put it on top of whoever this was aimed at.
            double out = distance + random.nextInt(3);
            int x = centre.x() + (int) Math.round(Math.cos(angle) * out);
            int z = centre.z() + (int) Math.round(Math.sin(angle) * out);
            spots.add(new Spot(centre.world(), x, centre.y(), z));
        }
        return List.copyOf(spots);
    }

    /**
     * The same, for a pack of several kinds: each creature in turn gets the next position.
     *
     * <p>Interleaved rather than grouped, so a mixed pack arrives mixed. Grouped by kind, a wave of
     * skeletons and zombies is a wall of skeletons on one side and a wall of zombies on the other,
     * which is two problems in sequence instead of one fight.
     */
    public static List<Spot> ringFor(Spot centre, List<String> creatures, int radius, long seed) {
        return ring(centre, creatures.size(), radius, seed);
    }
}
