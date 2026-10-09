package de.raindancer.core.moderation.rules;

import de.raindancer.core.platform.util.PluginCode;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The server's rules, offered by whichever plugin keeps them (essentials' {@code /rules}) to whichever acts on
 * them (moderation punishing for one). Static and process-wide, like {@code Away}: neither knows the other.
 */
public final class ServerRules {

    private static final AtomicReference<Supplier<List<ServerRule>>> source = new AtomicReference<>();

    private ServerRules() {
    }

    public static void provide(Supplier<List<ServerRule>> rules) {
        source.set(rules);
    }

    public static void withdraw(Supplier<List<ServerRule>> rules) {
        source.compareAndSet(rules, null);
    }

    /** The rules players see, in order; empty when no plugin keeps any. */
    public static List<ServerRule> current() {
        Supplier<List<ServerRule>> rules = source.get();
        if (rules == null) {
            return List.of();
        }
        try {
            List<ServerRule> found = rules.get();
            return found == null ? List.of() : found;
        } catch (RuntimeException broken) {
            return List.of();
        }
    }

    public static Optional<ServerRule> byNumber(int number) {
        return current().stream().filter(rule -> rule.number() == number).findFirst();
    }

    public static Optional<ServerRule> byId(String id) {
        return current().stream().filter(rule -> rule.id().equals(id)).findFirst();
    }

    public static int forgetFrom(ClassLoader loader) {
        Supplier<List<ServerRule>> rules = source.get();
        if (rules != null && PluginCode.isFrom(rules, loader) && source.compareAndSet(rules, null)) {
            return 1;
        }
        return 0;
    }

    public static void clear() {
        source.set(null);
    }
}
