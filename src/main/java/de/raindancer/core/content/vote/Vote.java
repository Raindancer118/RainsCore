package de.raindancer.core.content.vote;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One question, its answers, and who has said what.
 *
 * <h2>Why the answers are held here and not in a map somewhere</h2>
 * Because a vote is a thing with rules — one ballot per person, changeable until the deadline,
 * closed after it — and those rules only hold if there is one place they are enforced. A map of
 * player to answer plus a scheduled task is the version that lets somebody vote twice.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread. A tally is a snapshot rather than a view, so nobody reads a count while it
 * is being written.
 */
public final class Vote {

    private final UUID id;
    private final UUID startedBy;
    private final String question;
    private final List<String> options;
    private final long openedAt;
    private final long closesAt;
    /** Null means everybody. A named set is a town council, a party, a staff vote. */
    private final Set<UUID> mayVote;

    private final Map<UUID, String> cast = new ConcurrentHashMap<>();
    private volatile boolean closed;

    Vote(UUID id, UUID startedBy, String question, List<String> options, long openedAt,
         long closesAt, Set<UUID> mayVote) {
        this.id = id;
        this.startedBy = startedBy;
        this.question = question;
        this.options = List.copyOf(options);
        this.openedAt = openedAt;
        this.closesAt = closesAt;
        this.mayVote = mayVote == null ? null : Set.copyOf(mayVote);
    }

    public UUID id() {
        return id;
    }

    public UUID startedBy() {
        return startedBy;
    }

    public String question() {
        return question;
    }

    /** The answers, as they were written, in the order they were given. */
    public List<String> options() {
        return options;
    }

    public long openedAt() {
        return openedAt;
    }

    public long closesAt() {
        return closesAt;
    }

    /** Whether it is still taking answers. */
    public boolean isOpen(long now) {
        return !closed && now < closesAt;
    }

    /** How long is left, for a bossbar or a countdown. Empty once it has ended. */
    public Optional<Duration> timeLeft(long now) {
        return isOpen(now) ? Optional.of(Duration.ofMillis(closesAt - now)) : Optional.empty();
    }

    /** Whether one person is being asked at all. */
    public boolean mayVote(UUID player) {
        return player != null && (mayVote == null || mayVote.contains(player));
    }

    /** Who is being asked, or empty for everybody. */
    public Optional<Set<UUID>> electorate() {
        return Optional.ofNullable(mayVote);
    }

    /** Whether this person has answered. Not <em>what</em> they answered — see the class comment. */
    public boolean hasVoted(UUID player) {
        return player != null && cast.containsKey(player);
    }

    /** How many have answered. */
    public int turnout() {
        return cast.size();
    }

    void close() {
        this.closed = true;
    }

    boolean isClosedEarly() {
        return closed;
    }

    /**
     * Records an answer.
     *
     * <p>Every rule about who may answer and when lives here rather than in the caller, because a
     * rule enforced in two places is a rule enforced in one and a half.
     */
    Ballot record(UUID player, String option, long now) {
        if (!isOpen(now)) {
            return Ballot.CLOSED;
        }
        if (!mayVote(player)) {
            return Ballot.NOT_YOURS;
        }
        String chosen = options.stream()
                .filter(candidate -> candidate.equalsIgnoreCase(option == null ? "" : option.trim()))
                .findFirst()
                .orElse(null);
        if (chosen == null) {
            return Ballot.NOT_AN_OPTION;
        }
        String before = cast.put(player, chosen);
        if (before == null) {
            return Ballot.COUNTED;
        }
        return before.equals(chosen) ? Ballot.ALREADY : Ballot.CHANGED;
    }

    /** How it stands, as a snapshot. */
    Tally tally(long now) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        options.forEach(option -> counts.put(option, 0));
        // Copied first: counting straight off the live map is how two people reading the same vote
        // come away with two different totals.
        for (String answer : new LinkedHashSet<>(cast.values()).isEmpty()
                ? List.<String>of() : List.copyOf(cast.values())) {
            counts.computeIfPresent(answer, (option, count) -> count + 1);
        }
        return new Tally(question, counts, !isOpen(now));
    }

    static String key(String option) {
        return option == null ? "" : option.trim().toLowerCase(Locale.ROOT);
    }
}
