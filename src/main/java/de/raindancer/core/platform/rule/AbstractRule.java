package de.raindancer.core.platform.rule;

/**
 * A rule that knows what to call itself.
 *
 * <p>Extend this rather than implementing {@link IRule} directly. The reason is small and worth it: a rule's name
 * ends up in the diagnostic that says which one refused, and the default derived from the class name reads as
 * {@code DoesNotOverlap} — accurate and useless in a sentence. Making the name a constructor argument means no
 * rule can be written without one, and the ones that already exist read as
 * "the claim does not overlap another".
 *
 * <p>{@link IRule} stays an interface, and stays functional, because a one-line rule in a test or a plugin's own
 * short list should still be a lambda. This is for the ones that live in a file.
 *
 * @param <T> what is being judged
 */
public abstract class AbstractRule<T> implements IRule<T> {

    private final String description;

    /**
     * @param description what this rule requires, phrased so it reads in "refused because …" — for instance
     *                    "the claim does not overlap another" rather than "OverlapCheck"
     */
    protected AbstractRule(String description) {
        if (description == null || description.isBlank()) {
            // A rule with no description is one that cannot appear in a diagnostic, which is most of what the
            // description is for.
            throw new IllegalArgumentException("a rule has to say what it requires");
        }
        this.description = description.strip();
    }

    @Override
    public final String describe() {
        return description;
    }

    @Override
    public String toString() {
        return describe();
    }
}
