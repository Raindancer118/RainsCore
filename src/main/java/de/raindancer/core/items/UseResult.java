package de.raindancer.core.items;

import java.time.Duration;
import java.util.Optional;

/**
 * What came of trying to use an item.
 *
 * @param remainingMillis how long until it may be used again, when that is why it did not run
 * @param charges         what is left afterwards, or null when unlimited
 */
public record UseResult(UseOutcome outcome, Long remainingMillis, Integer charges) {

    static final UseResult UNKNOWN = new UseResult(UseOutcome.UNKNOWN, null, null);
    static final UseResult WRONG_TRIGGER = new UseResult(UseOutcome.WRONG_TRIGGER, null, null);
    static final UseResult NO_CHARGES = new UseResult(UseOutcome.NO_CHARGES, null, 0);
    static final UseResult DECLINED = new UseResult(UseOutcome.DECLINED, null, null);
    static final UseResult FAILED = new UseResult(UseOutcome.FAILED, null, null);

    static UseResult ran(Integer charges) {
        return new UseResult(UseOutcome.RAN, null, charges);
    }

    static UseResult cooling(long millis) {
        return new UseResult(UseOutcome.ON_COOLDOWN, millis, null);
    }

    /** Whether the effect actually happened. */
    public boolean ran() {
        return outcome == UseOutcome.RAN;
    }

    /** How long until it may be used again. */
    public Optional<Duration> remaining() {
        return Optional.ofNullable(remainingMillis).map(Duration::ofMillis);
    }

    /** What the holder has left. */
    public Optional<Integer> chargesLeft() {
        return Optional.ofNullable(charges);
    }

    /**
     * Whether the item should now be taken out of the player's hand.
     *
     * <p>True only when it ran and that was the last charge — the Hunger Games' items answer this
     * question with a boolean from {@code use()}, and it is the same question.
     */
    public boolean itemIsSpent() {
        return ran() && charges != null && charges <= 0;
    }
}
