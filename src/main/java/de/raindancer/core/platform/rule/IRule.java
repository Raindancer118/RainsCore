package de.raindancer.core.platform.rule;

/**
 * One reason something might not be allowed.
 *
 * <h2>Why this exists</h2>
 * Because "may this happen?" is almost never one question. Creating a claim asks nine: is the world enabled, is
 * the name valid, is it taken, are there too many corners, is it too small, too large, wholly underground, inside
 * a no-claim zone, overlapping somebody else. Written as one method that returns early nine times, that is a
 * shape with three properties nobody wants: a plugin cannot add a tenth reason, nothing can list the reasons, and
 * a test has to construct a whole world to exercise the sixth.
 *
 * <p>As rules, each of those is a class with one method, testable on its own, and the chain is data.
 *
 * <h2>What a rule is not</h2>
 * Not a listener and not a hook. A rule <em>decides</em> and does nothing else: no messages, no saving, no state.
 * That is what lets {@link Rules} run them in any order, stop at the first refusal, or collect every refusal to
 * show somebody why their claim will not fit.
 *
 * @param <T> what is being judged — an attempted claim, a teleport, a purchase
 */
@FunctionalInterface
public interface IRule<T> {

    /**
     * Whether this rule permits it.
     *
     * <p>Must be free of side effects and safe to call from any thread. A rule that saves something, sends
     * something or waits for something is a rule that cannot be asked speculatively — and "would this be
     * allowed?" is the question a menu asks to decide whether to grey a button.
     */
    Verdict judge(T subject);

    /**
     * A name for the log line and the test failure.
     *
     * <p>Defaulted from the class name so a rule that is a lambda still says something useful, but worth
     * overriding: "the claim is not inside a no-claim zone" reads better in a diagnostic than "Rule$$Lambda".
     */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
