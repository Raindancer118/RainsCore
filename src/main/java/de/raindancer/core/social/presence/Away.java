package de.raindancer.core.social.presence;

import de.raindancer.core.platform.util.PluginCode;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Whether a player is away from the keyboard — asked by anything that pays, rewards or counts playtime,
 * answered by whichever plugin watches for it (the essentials module's {@code /afk}).
 *
 * <p>Static and process-wide, like {@code Economies}: one answer for the server however many plugins ask.
 * With no watcher registered nobody is away, and {@link #isKnown()} lets a caller fall back to its own
 * judgement rather than paying an idle player because nobody was asked.
 */
public final class Away {

    private static final List<Predicate<UUID>> watchers = new CopyOnWriteArrayList<>();

    private Away() {
    }

    /** Starts answering. Safe from any thread; the answer must be too. */
    public static void watch(Predicate<UUID> watcher) {
        if (watcher != null && !watchers.contains(watcher)) {
            watchers.add(watcher);
        }
    }

    public static void unwatch(Predicate<UUID> watcher) {
        watchers.remove(watcher);
    }

    /** Whether anything on this server watches for players being away. */
    public static boolean isKnown() {
        return !watchers.isEmpty();
    }

    public static boolean isAway(UUID player) {
        if (player == null) {
            return false;
        }
        for (Predicate<UUID> watcher : watchers) {
            try {
                if (watcher.test(player)) {
                    return true;
                }
            } catch (RuntimeException broken) {
                // One plugin's broken answer must not make everybody away, or nobody.
            }
        }
        return false;
    }

    /** Drops whatever {@code loader}'s code registered — for a plugin disabled without unwatching. */
    public static int forgetFrom(ClassLoader loader) {
        int before = watchers.size();
        watchers.removeIf(watcher -> PluginCode.isFrom(watcher, loader));
        return before - watchers.size();
    }

    /** For tests, and Core shutting down. */
    public static void clear() {
        watchers.clear();
    }
}
