package de.raindancer.core.world.spawn;

import de.raindancer.core.world.safety.Spot;

import java.util.List;

/**
 * Running one pack: putting each creature where the ring says.
 *
 * <p>Deliberately not the scheduling. A {@link Wave} is a plan with timings in it, and what turns a
 * plan into a sequence of these is the host's scheduler — which on Folia has to be the region owning
 * the ground being spawned on, and is therefore not something Core can decide on the caller's behalf.
 * What Core owns is what a pack is, where it lands, and what it means for one to half-succeed.
 */
public final class Spawns {

    private final Spawner spawner;

    public Spawns(Spawner spawner) {
        this.spawner = spawner;
    }

    /**
     * What happened, in the words a message needs.
     *
     * @param spawned how many appeared
     * @param refused how many the world would not take — an unloaded chunk, or no room
     */
    public record Arrived(int spawned, int refused) {

        public boolean isEmpty() {
            return spawned == 0;
        }
    }

    /**
     * Puts one pack down around {@code centre}.
     *
     * <p>Every creature is placed independently and a refusal costs that one creature. The version
     * that gives up on the first failure is the version where a pack aimed at a cave mouth arrives as
     * two zombies, because the third position happened to be inside the hill.
     */
    public Arrived place(Wave.Pack pack, Spot centre, long seed) {
        List<Spot> spots = Swarm.ringFor(centre, pack.creatures(), pack.radius(), seed);

        int spawned = 0;
        int refused = 0;
        for (int index = 0; index < pack.creatures().size(); index++) {
            Spot spot = spots.get(index);
            if (!spawner.isLoaded(spot) || !spawner.spawn(spot, pack.creatures().get(index))) {
                refused++;
                continue;
            }
            spawned++;
        }
        return new Arrived(spawned, refused);
    }
}
