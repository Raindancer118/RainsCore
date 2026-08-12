package de.raindancer.core.world.speedrun;

import org.bukkit.WorldCreator;

/**
 * How a reset world's terrain is chosen: a fixed seed, so every attempt at a run generates the same
 * world, or a random one, so it does not. A closed choice — see {@code Tag} for this codebase's other
 * use of the shape — rather than a nullable {@code Long}, so "no seed given" cannot be confused with
 * "seed zero".
 */
public sealed interface SpeedrunSeed {

    /** Applies this choice to {@code creator} and returns it, for chaining. */
    WorldCreator apply(WorldCreator creator);

    static SpeedrunSeed fixed(long seed) {
        return new Fixed(seed);
    }

    static SpeedrunSeed random() {
        return Random.INSTANCE;
    }

    record Fixed(long seed) implements SpeedrunSeed {
        @Override
        public WorldCreator apply(WorldCreator creator) {
            return creator.seed(seed);
        }
    }

    /** Leaves the creator untouched, so Bukkit generates its own seed the way it would for any new world. */
    record Random() implements SpeedrunSeed {
        private static final Random INSTANCE = new Random();

        @Override
        public WorldCreator apply(WorldCreator creator) {
            return creator;
        }
    }
}
