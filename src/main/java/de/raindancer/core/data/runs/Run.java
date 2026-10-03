package de.raindancer.core.data.runs;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One finished run of a game: who played, in which category, how it went.
 *
 * <p>Deliberately general. A speedrun is a time that lower wins; a hunt is a time too, or a count of
 * catches; a hunger games round is a placing. All of them are a score with a direction, a category to
 * be ranked within, the people who took part, and whatever else the game wants to keep beside it.
 *
 * @param id        unique, and the same for the same run however often it is saved
 * @param category  what it is ranked against — a mode and its variant, {@code "manhunt/1v3"},
 *                  {@code "speedrun/any%/solo"}. Runs only ever rank against their own category
 * @param score     a time in milliseconds for a timed run, otherwise the count it is judged by
 * @param lowerWins whether a smaller score is better — true for times
 * @param ranked    false for a run struck from the rankings (practice, edited, disqualified): it stays
 *                  in the history, it never counts as a best or a record
 * @param players   who took part, with the name they had then — a name changes, the history must not
 * @param splits    named points along the way, in milliseconds from the start — "nether", "dragon"
 * @param fields    anything else worth keeping — the seed, the winner's team, the kit
 */
public record Run(String id, String category, Instant startedAt, long score, boolean lowerWins,
                  boolean ranked, Map<UUID, String> players, Map<String, Long> splits,
                  Map<String, String> fields) {

    public Run {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(category, "category");
        // To the millisecond, which is what is kept: a run must equal itself after a restart.
        startedAt = startedAt == null ? Instant.EPOCH : startedAt.truncatedTo(ChronoUnit.MILLIS);
        players = players == null ? Map.of() : unmodifiable(players);
        splits = splits == null ? Map.of() : unmodifiable(splits);
        fields = fields == null ? Map.of() : unmodifiable(fields);
    }

    private static <K, V> Map<K, V> unmodifiable(Map<K, V> from) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(from));
    }

    /** A timed run, ranked: the usual shape. */
    public static Builder timed(String category, Duration took) {
        return new Builder(category, took == null ? 0 : took.toMillis(), true);
    }

    /** A run judged by a count — catches, kills, points; {@code lowerWins} for golf-like scores. */
    public static Builder scored(String category, long score, boolean lowerWins) {
        return new Builder(category, score, lowerWins);
    }

    /** The score as a time — only meaningful for a timed run. */
    public Duration time() {
        return Duration.ofMillis(score);
    }

    public boolean includes(UUID player) {
        return player != null && players.containsKey(player);
    }

    public Optional<Duration> split(String name) {
        return Optional.ofNullable(splits.get(name)).map(Duration::ofMillis);
    }

    public Optional<String> field(String name) {
        return Optional.ofNullable(fields.get(name));
    }

    /** Whether this beats {@code other} — strictly; a tie is not a beat. */
    public boolean beats(Run other) {
        return other == null || (lowerWins ? score < other.score : score > other.score);
    }

    /** The same run, struck from the rankings or restored to them. */
    public Run ranked(boolean ranked) {
        return new Run(id, category, startedAt, score, lowerWins, ranked, players, splits, fields);
    }

    /** Builds one. */
    public static final class Builder {
        private String id = UUID.randomUUID().toString();
        private final String category;
        private Instant startedAt = Instant.now();
        private final long score;
        private final boolean lowerWins;
        private boolean ranked = true;
        private final Map<UUID, String> players = new LinkedHashMap<>();
        private final Map<String, Long> splits = new LinkedHashMap<>();
        private final Map<String, String> fields = new LinkedHashMap<>();

        private Builder(String category, long score, boolean lowerWins) {
            this.category = category;
            this.score = score;
            this.lowerWins = lowerWins;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder startedAt(Instant when) {
            this.startedAt = when;
            return this;
        }

        public Builder unranked() {
            this.ranked = false;
            return this;
        }

        public Builder player(UUID who, String name) {
            if (who != null) {
                players.put(who, name == null ? "" : name);
            }
            return this;
        }

        public Builder players(Map<UUID, String> who) {
            if (who != null) {
                who.forEach(this::player);
            }
            return this;
        }

        public Builder split(String name, Duration at) {
            if (name != null && at != null) {
                splits.put(name, at.toMillis());
            }
            return this;
        }

        public Builder field(String name, Object value) {
            if (name != null && value != null) {
                fields.put(name, String.valueOf(value));
            }
            return this;
        }

        public Run build() {
            return new Run(id, category, startedAt, score, lowerWins, ranked, players, splits, fields);
        }
    }

    /** Ordering best first, then earliest first among equals — the order a leaderboard reads. */
    static int bestFirst(Run one, Run other) {
        int byScore = one.lowerWins ? Long.compare(one.score, other.score) : Long.compare(other.score, one.score);
        return byScore != 0 ? byScore : one.startedAt.compareTo(other.startedAt);
    }

}
