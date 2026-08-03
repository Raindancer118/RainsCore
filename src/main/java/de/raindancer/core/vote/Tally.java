package de.raindancer.core.vote;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * How a vote stands, or how it ended.
 *
 * <p>A snapshot, not a view: a result being read while it is still being written is how two people
 * come away with two different numbers from the same vote.
 */
public final class Tally {

    private final String question;
    private final Map<String, Integer> counts;
    private final boolean finished;

    Tally(String question, Map<String, Integer> counts, boolean finished) {
        this.question = question;
        this.counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
        this.finished = finished;
    }

    public String question() {
        return question;
    }

    /** Whether the vote has ended. A tally of a vote still running is a running total. */
    public boolean isFinished() {
        return finished;
    }

    /** Every answer and how many chose it, in the order they were on the ballot. */
    public Map<String, Integer> counts() {
        return counts;
    }

    /** How many chose one answer. */
    public int votesFor(String option) {
        if (option == null) {
            return 0;
        }
        return counts.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(option.trim()))
                .mapToInt(Map.Entry::getValue)
                .findFirst()
                .orElse(0);
    }

    /** How many people voted at all. */
    public int totalCast() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** What share of the votes cast one answer got, from 0 to 1. */
    public double shareOf(String option) {
        int total = totalCast();
        return total == 0 ? 0 : (double) votesFor(option) / total;
    }

    /** The answers with the most votes — more than one when it is a tie. */
    public List<String> leaders() {
        int most = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (most == 0) {
            return List.of();
        }
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() == most)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * The winner, if there is one.
     *
     * <p>Empty for a tie and empty when nobody voted. Picking one out of a tie — by ballot order, by
     * who voted first, by anything — is how a vote turns into an argument, so it deliberately will
     * not.
     */
    public Optional<String> winner() {
        List<String> leaders = leaders();
        return leaders.size() == 1 ? Optional.of(leaders.getFirst()) : Optional.empty();
    }

    public boolean isTie() {
        return leaders().size() > 1;
    }

    /** The result in one line, for chat or a log. */
    public String describe() {
        if (totalCast() == 0) {
            return "nobody voted";
        }
        StringBuilder built = new StringBuilder();
        counts.forEach((option, count) -> {
            if (!built.isEmpty()) {
                built.append(", ");
            }
            built.append(option).append(": ").append(count)
                    .append(" (").append(Math.round(shareOf(option) * 100)).append("%)");
        });
        return built.toString();
    }

    static String normalise(String option) {
        return option == null ? "" : option.trim().toLowerCase(Locale.ROOT);
    }
}
