package de.raindancer.core.moderation.rules;

import de.raindancer.core.platform.util.PluginCode;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handing out the next rung of a rule's ladder, offered by whichever plugin counts offences (moderation) to
 * whichever catches somebody breaking a rule on its own (the anti-cheat). Static and process-wide, like
 * {@link ServerRules}: neither knows the other.
 */
public final class RuleBreaches {

    /** What was handed out, and which offence against the rule it was, counting from one. */
    public record Outcome(RulePenalty penalty, int offence) {
    }

    @FunctionalInterface
    public interface Judge {

        /**
         * Hands out the next rung, recorded and counted like one a moderator gives. Called on the main thread.
         *
         * @param by what the record names as having handed it out, e.g. "Anti-Cheat"
         * @return empty when nothing was handed out: no ladder, or it was refused
         */
        Optional<Outcome> breach(UUID subject, String subjectName, ServerRule rule, String by, String note);
    }

    private static final AtomicReference<Judge> judge = new AtomicReference<>();

    private RuleBreaches() {
    }

    public static void provide(Judge handler) {
        judge.set(handler);
    }

    public static void withdraw(Judge handler) {
        judge.compareAndSet(handler, null);
    }

    public static boolean available() {
        return judge.get() != null;
    }

    /** @return empty when nobody judges breaches, or nothing was handed out */
    public static Optional<Outcome> breach(UUID subject, String subjectName, ServerRule rule, String by, String note) {
        Judge handler = judge.get();
        if (handler == null || subject == null || rule == null) {
            return Optional.empty();
        }
        try {
            Optional<Outcome> outcome = handler.breach(subject, subjectName, rule, by, note);
            return outcome == null ? Optional.empty() : outcome;
        } catch (RuntimeException broken) {
            return Optional.empty();
        }
    }

    public static int forgetFrom(ClassLoader loader) {
        Judge handler = judge.get();
        if (handler != null && PluginCode.isFrom(handler, loader) && judge.compareAndSet(handler, null)) {
            return 1;
        }
        return 0;
    }

    public static void clear() {
        judge.set(null);
    }
}
