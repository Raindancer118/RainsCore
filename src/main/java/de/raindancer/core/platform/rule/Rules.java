package de.raindancer.core.platform.rule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * A chain of {@link IRule}s, asked in order.
 *
 * <p>Immutable and cheap to copy, so a plugin can hold one chain and hand out a longer one to whichever caller
 * needs an extra rule — an admin bypassing the size limit, a world with its own rules — without either of them
 * being able to change the other's.
 *
 * <h2>First refusal, or all of them</h2>
 * {@link #judge} stops at the first no, which is what a command wants: one sentence, the most relevant one.
 * {@link #judgeAll} keeps going, which is what a screen wants — greying four buttons needs four reasons, and
 * asking four times over would re-run the cheap rules four times.
 */
public final class Rules<T> {

    private final List<IRule<T>> rules;

    private Rules(List<IRule<T>> rules) {
        this.rules = List.copyOf(rules);
    }

    @SafeVarargs
    public static <T> Rules<T> of(IRule<T>... rules) {
        return new Rules<>(List.of(rules));
    }

    public static <T> Rules<T> of(Collection<? extends IRule<T>> rules) {
        return new Rules<>(new ArrayList<>(rules));
    }

    /** The same chain with one more rule at the end. The original is untouched. */
    public Rules<T> and(IRule<T> extra) {
        List<IRule<T>> longer = new ArrayList<>(rules);
        longer.add(extra);
        return new Rules<>(longer);
    }

    /** The first refusal, or allowed. */
    public Verdict judge(T subject) {
        for (IRule<T> rule : rules) {
            Verdict verdict = rule.judge(subject);
            if (verdict.isRefused()) {
                return verdict;
            }
        }
        return Verdict.allowed();
    }

    /**
     * Every refusal, in order.
     *
     * <p>Empty means allowed. For the screens that want to say all of what is wrong at once rather than making
     * somebody fix one thing, try again, and find out about the next.
     */
    public List<Verdict> judgeAll(T subject) {
        List<Verdict> refusals = new ArrayList<>();
        for (IRule<T> rule : rules) {
            Verdict verdict = rule.judge(subject);
            if (verdict.isRefused()) {
                refusals.add(verdict);
            }
        }
        return List.copyOf(refusals);
    }

    /** Which rule refused, for a diagnostic that has to name it. */
    public Optional<IRule<T>> firstObjector(T subject) {
        for (IRule<T> rule : rules) {
            if (rule.judge(subject).isRefused()) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    public List<IRule<T>> all() {
        return rules;
    }

    public int size() {
        return rules.size();
    }
}
