package de.raindancer.core.platform.rule;

import java.util.Objects;
import java.util.Optional;

/**
 * What a rule decided, and — when it refused — why, in terms the caller can turn into a sentence.
 *
 * <h2>Why a refusal carries a key and a detail rather than a message</h2>
 * Because the rule does not know who is asking or in what language. A rule says {@code error.claim-too-small}
 * with {@code 9}; whoever is talking to the player looks that up in {@code messages.yml}, where the server owner
 * may have reworded it. A rule that returned a finished sentence would have quietly taken that away.
 *
 * @param isAllowed whether it may go ahead
 * @param reason  the message key, empty when allowed
 * @param detail  the one value that wording needs — a limit, a name, a number
 */
public record Verdict(boolean isAllowed, String reason, String detail) {

    private static final Verdict ALLOWED = new Verdict(true, "", "");

    public Verdict {
        reason = reason == null ? "" : reason;
        detail = detail == null ? "" : detail;
        if (!isAllowed && reason.isBlank()) {
            // A refusal with nothing to say is the failure mode this whole type exists to prevent: the player
            // is told no, and neither they nor the operator can find out why.
            throw new IllegalArgumentException("a refusal has to say why");
        }
    }

    /** Nothing to object to. */
    public static Verdict allowed() {
        return ALLOWED;
    }

    /** No, because of this — with the value the wording needs. */
    public static Verdict refused(String reason, Object detail) {
        return new Verdict(false, reason, detail == null ? "" : String.valueOf(detail));
    }

    /** No, because of this. */
    public static Verdict refused(String reason) {
        return refused(reason, "");
    }

    public boolean isRefused() {
        return !isAllowed;
    }

    /** The reason, when there is one. */
    public Optional<String> refusal() {
        return isAllowed ? Optional.empty() : Optional.of(reason);
    }

    /** The first refusal of two, or {@code allowed} when neither refused. */
    public Verdict and(Verdict other) {
        Objects.requireNonNull(other, "a verdict cannot be combined with nothing");
        return isAllowed ? other : this;
    }
}
