package de.raindancer.core.world.manage;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Which seed a world is made with: a fresh one, the one it already had, or one somebody chose.
 *
 * <h2>Why this is Core's</h2>
 * Three modules had grown their own answer before this existed — speedrun's {@code SpeedrunSeed},
 * chained's {@code SeedChoice} and the farm worlds' fixed seed — each with its own idea of what
 * "same" means for a world that never had a seed written down. Anything that makes a world asks the
 * same question, so it is asked here once.
 *
 * <h2>Why {@link #same()} is not simply a fixed seed read beforehand</h2>
 * Because the seed it means belongs to the world at the moment it is thrown away, which is inside
 * {@link WorldRegenerator}, after occupants have been moved and possibly ticks later. A caller that
 * read it earlier would be right almost always, and wrong exactly when two regenerations overlap.
 */
public record WorldSeed(Kind kind, long value) {

    /** The three answers. */
    public enum Kind {
        /** Whatever the server picks, as for a world created by hand. */
        RANDOM,
        /** The seed the world being replaced was generated from. */
        SAME,
        /** Exactly {@link WorldSeed#value()}. */
        FIXED
    }

    public WorldSeed {
        kind = kind == null ? Kind.RANDOM : kind;
        value = kind == Kind.FIXED ? value : 0L;
    }

    public static WorldSeed random() {
        return new WorldSeed(Kind.RANDOM, 0L);
    }

    public static WorldSeed same() {
        return new WorldSeed(Kind.SAME, 0L);
    }

    public static WorldSeed fixed(long seed) {
        return new WorldSeed(Kind.FIXED, seed);
    }

    /**
     * What somebody typed.
     *
     * <p>{@code random}/{@code new} and {@code same}/{@code old}/{@code keep} are the words; a number is
     * that seed; and any other text is hashed with {@link String#hashCode()}, which is precisely what the
     * vanilla create-world screen does with it — so a seed somebody found as a word produces the map they
     * found. A number too long for a {@code long} is text by the same rule, not an error.
     *
     * @return empty for nothing typed at all, which is not the same as seed zero
     */
    public static Optional<WorldSeed> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String trimmed = text.trim();
        return Optional.of(switch (trimmed.toLowerCase(Locale.ROOT)) {
            case "random", "new" -> random();
            case "same", "old", "keep" -> same();
            default -> fixed(numberOrHash(trimmed));
        });
    }

    private static long numberOrHash(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException notANumber) {
            return text.hashCode();
        }
    }

    /**
     * The seed to hand the world creator, given the seed of the world being replaced.
     *
     * @param previous the outgoing world's seed, empty when there is no such world
     * @return empty for "let the server pick" — including {@link #same()} with nothing before it, since
     *         a new world defaulting to seed zero would be a surprising map rather than a fresh one
     */
    public OptionalLong resolve(OptionalLong previous) {
        return switch (kind) {
            case FIXED -> OptionalLong.of(value);
            case SAME -> previous == null ? OptionalLong.empty() : previous;
            case RANDOM -> OptionalLong.empty();
        };
    }

    /** For a sentence: "Regenerating farm with <description>". */
    public String describe() {
        return switch (kind) {
            case RANDOM -> "a random seed";
            case SAME -> "the same seed";
            case FIXED -> "seed " + value;
        };
    }
}
