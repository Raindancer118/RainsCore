package de.raindancer.core.world;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A farm world, and the two worlds that belong to it.
 *
 * <h2>Why a set rather than a world</h2>
 * A farm world is not one world, it is three: somewhere to mine, its own nether so people can farm
 * blaze rods and quartz without wrecking the main one, and its own end. The three go together —
 * a fresh overworld beside a strip-mined nether is half a farm world — and the portals between them
 * have to stay inside the set. That last one is the whole point: without it, stepping through a
 * portal in the farm world lands somebody in the <em>main</em> nether, and the farm world protects
 * nothing.
 *
 * <h2>What is here and what is not</h2>
 * Naming, membership, portal linking and the schedule. All of it decidable without a server, so all
 * of it is tested. Creating the worlds, deleting the folders and moving the players out first is
 * {@code FarmWorlds}, which cannot be.
 *
 * @param name           the overworld's name; the other two are derived from it, as vanilla does
 * @param regenerateAfter how often it is thrown away and made again, or null for never
 * @param fixedSeed      the seed to use every time, or null for a fresh one
 * @param borderRadius   how far from the middle it goes, or null for no border
 */
public record WorldSet(String name, boolean hasNether, boolean hasEnd, Duration regenerateAfter,
                       Long fixedSeed, Integer borderRadius) {

    /** Which of a set's three worlds something is. */
    public enum Part {
        OVERWORLD,
        NETHER,
        END
    }

    /** How many overworld blocks one nether block is worth. Vanilla, and players expect it. */
    private static final int NETHER_RATIO = 8;

    /**
     * Names a set may not take.
     *
     * <p>Regenerating a set deletes its folders. A set called {@code world} would delete the server,
     * which is not a mistake anybody should be able to make by typing a command.
     */
    private static final Set<String> RESERVED =
            Set.of("world", "world_nether", "world_the_end", "plugins", "logs", "cache");

    public WorldSet {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A world set needs a name.");
        }
        String cleaned = name.trim().toLowerCase(Locale.ROOT);
        if (!cleaned.matches("[a-z0-9_-]+")) {
            // It becomes a folder name, and a folder name with a slash or a dot-dot in it is a path
            // out of the server directory — which matters because regenerating deletes it.
            throw new IllegalArgumentException(
                    "'" + name + "' cannot be a world name: letters, digits, - and _ only.");
        }
        if (RESERVED.contains(cleaned)) {
            throw new IllegalArgumentException(
                    "'" + cleaned + "' is one of the server's own worlds, and regenerating it "
                            + "would delete the server.");
        }
        name = cleaned;
    }

    /** A farm world with its own nether and end, regenerated only when somebody asks. */
    public static WorldSet of(String name) {
        return new WorldSet(name, true, true, null, null, null);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    // ---------------------------------------------------------------------------- naming

    public String overworld() {
        return name;
    }

    /** Named as vanilla does, so anything reading world names recognises the shape. */
    public String nether() {
        return name + "_nether";
    }

    public String end() {
        return name + "_the_end";
    }

    /** The name of one part, whether or not this set has it. */
    public String worldFor(Part part) {
        return switch (part) {
            case OVERWORLD -> overworld();
            case NETHER -> nether();
            case END -> end();
        };
    }

    /** Every world this set actually has, overworld first. */
    public List<String> worlds() {
        List<String> all = new ArrayList<>(3);
        all.add(overworld());
        if (hasNether) {
            all.add(nether());
        }
        if (hasEnd) {
            all.add(end());
        }
        return List.copyOf(all);
    }

    // ---------------------------------------------------------------------------- membership

    /** Whether a world belongs to this set. */
    public boolean contains(String world) {
        return world != null && worlds().contains(world.trim().toLowerCase(Locale.ROOT));
    }

    /** Which part of the set a world is, or empty when it is not one of ours. */
    public Optional<Part> partOf(String world) {
        if (world == null) {
            return Optional.empty();
        }
        String wanted = world.trim().toLowerCase(Locale.ROOT);
        if (wanted.equals(overworld())) {
            return Optional.of(Part.OVERWORLD);
        }
        if (hasNether && wanted.equals(nether())) {
            return Optional.of(Part.NETHER);
        }
        if (hasEnd && wanted.equals(end())) {
            return Optional.of(Part.END);
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------------------- portals

    /**
     * Where a portal in one of this set's worlds should lead.
     *
     * <p>Empty when the world is not ours — the main world's portals are not a farm world's to
     * redirect — and empty when this set does not have the part being asked for.
     */
    public Optional<String> portalTarget(String from, Part to) {
        if (to == null || partOf(from).isEmpty()) {
            return Optional.empty();
        }
        if (to == Part.NETHER && !hasNether) {
            return Optional.empty();
        }
        if (to == Part.END && !hasEnd) {
            return Optional.empty();
        }
        return Optional.of(worldFor(to));
    }

    /**
     * A coordinate, moved between two parts of a set.
     *
     * <p>One to eight between the overworld and the nether, as everywhere else — a farm world where
     * that ratio is different is a farm world where people get lost.
     */
    public static double scaleCoordinate(double coordinate, Part from, Part to) {
        if (from == Part.OVERWORLD && to == Part.NETHER) {
            return coordinate / NETHER_RATIO;
        }
        if (from == Part.NETHER && to == Part.OVERWORLD) {
            return coordinate * NETHER_RATIO;
        }
        return coordinate;
    }

    // ---------------------------------------------------------------------------- schedule

    /**
     * How often it is thrown away and made again.
     *
     * <p>Named differently from the component it reads — {@code regenerateAfter} — because a
     * record's accessor may not change its return type, and most sets have no schedule at all.
     */
    public Optional<Duration> regenerateEvery() {
        return Optional.ofNullable(regenerateAfter);
    }

    /**
     * Whether it is time to make this set again.
     *
     * @param madeAt when it was last regenerated, or null if it never has been
     */
    public boolean isDue(Instant madeAt, Instant now) {
        if (regenerateAfter == null) {
            return false;
        }
        // Never made means due now: a set with a schedule and no world is one somebody added and
        // has been waiting for.
        return madeAt == null || !now.isBefore(madeAt.plus(regenerateAfter));
    }

    /** How long until the next regeneration, for warning players before it happens. */
    public Optional<Duration> until(Instant madeAt, Instant now) {
        if (regenerateAfter == null || madeAt == null) {
            return Optional.empty();
        }
        Duration left = Duration.between(now, madeAt.plus(regenerateAfter));
        return left.isNegative() || left.isZero() ? Optional.empty() : Optional.of(left);
    }

    // ---------------------------------------------------------------------------- the world

    /**
     * The seed for the next generation.
     *
     * <p>Fresh every time unless one was fixed — a regenerated farm world that came back identical
     * would be a strip-mined one, which is the opposite of the point.
     */
    public long nextSeed() {
        return fixedSeed != null ? fixedSeed : ThreadLocalRandom.current().nextLong();
    }

    /** How far from the middle it goes, or empty for no border. */
    public Optional<Integer> border() {
        return Optional.ofNullable(borderRadius);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WorldSet set && Objects.equals(name, set.name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }

    public static final class Builder {
        private final String name;
        private boolean nether = true;
        private boolean end = true;
        private Duration every;
        private Long seed;
        private Integer border;

        private Builder(String name) {
            this.name = name;
        }

        public Builder withNether(boolean value) {
            this.nether = value;
            return this;
        }

        public Builder withEnd(boolean value) {
            this.end = value;
            return this;
        }

        /** How often to throw it away and make it again. */
        public Builder every(Duration value) {
            this.every = value == null || value.isZero() || value.isNegative() ? null : value;
            return this;
        }

        /** The same map every time. For a server that wants a known farm world back. */
        public Builder seed(Long value) {
            this.seed = value;
            return this;
        }

        public Builder border(Integer radius) {
            this.border = radius == null || radius <= 0 ? null : radius;
            return this;
        }

        public WorldSet build() {
            return new WorldSet(name, nether, end, every, seed, border);
        }
    }
}
