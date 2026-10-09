package de.raindancer.core.moderation.rules;

import java.util.List;
import java.util.Optional;

/**
 * One of the server's rules, as every plugin sees it.
 *
 * @param id     stable through renames and moves — what offences against it are counted by
 * @param number as players see it in {@code /rules}
 * @param ladder what the first, second, … offence costs; past the top it stays on the top. Empty: no fixed penalty
 */
public record ServerRule(String id, int number, String title, String text, List<RulePenalty> ladder) {

    public ServerRule {
        ladder = ladder == null ? List.of() : List.copyOf(ladder);
    }

    /** @param prior how often they broke it before */
    public Optional<RulePenalty> forOffence(int prior) {
        if (ladder.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ladder.get(Math.max(0, Math.min(ladder.size() - 1, prior))));
    }
}
