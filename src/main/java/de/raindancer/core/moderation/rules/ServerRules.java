package de.raindancer.core.moderation.rules;

import de.raindancer.core.platform.util.PluginCode;

import java.util.List;
import java.util.Locale;
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

    /**
     * The rule about something: the first whose title holds one of the words, else the first whose text does.
     * "Keep the server running" may mention exploits in passing; "No cheating" says it in its title.
     */
    public static Optional<ServerRule> about(List<String> words) {
        return about(current(), words);
    }

    /** {@link #about(List)} among these rules. */
    public static Optional<ServerRule> about(List<ServerRule> rules, List<String> words) {
        Optional<ServerRule> byTitle = rules.stream().filter(rule -> mentions(rule.title(), words)).findFirst();
        return byTitle.isPresent() ? byTitle : rules.stream().filter(rule -> mentions(rule.text(), words)).findFirst();
    }

    private static boolean mentions(String text, List<String> words) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return words.stream().anyMatch(word -> lower.contains(word.toLowerCase(Locale.ROOT)));
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
