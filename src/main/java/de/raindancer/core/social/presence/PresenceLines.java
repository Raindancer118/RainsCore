package de.raindancer.core.social.presence;

import de.raindancer.core.platform.util.PluginCode;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Who says "joined" and "left" — so a faked departure (vanish) reads exactly like a real one on a server whose
 * join and quit lines are a plugin's own, not vanilla's. With no voice registered, or none that speaks, the
 * caller falls back to vanilla's line.
 */
public final class PresenceLines {

    /** One plugin's join and quit lines. */
    public interface Voice {

        /** @return whether it said something; false leaves it to the next voice, then vanilla */
        boolean arrived(Player who, Collection<? extends Player> to);

        boolean departed(Player who, Collection<? extends Player> to);
    }

    private static final List<Voice> voices = new CopyOnWriteArrayList<>();

    private PresenceLines() {
    }

    public static void speak(Voice voice) {
        if (voice != null && !voices.contains(voice)) {
            voices.add(voice);
        }
    }

    public static void silence(Voice voice) {
        voices.remove(voice);
    }

    /** @return whether a voice said it; false: say vanilla's line */
    public static boolean arrived(Player who, Collection<? extends Player> to) {
        for (Voice voice : voices) {
            try {
                if (voice.arrived(who, to)) {
                    return true;
                }
            } catch (RuntimeException broken) {
                // A broken voice falls through to the next, and in the end to vanilla.
            }
        }
        return false;
    }

    public static boolean departed(Player who, Collection<? extends Player> to) {
        for (Voice voice : voices) {
            try {
                if (voice.departed(who, to)) {
                    return true;
                }
            } catch (RuntimeException broken) {
                // As above.
            }
        }
        return false;
    }

    public static int forgetFrom(ClassLoader loader) {
        int before = voices.size();
        voices.removeIf(voice -> PluginCode.isFrom(voice, loader));
        return before - voices.size();
    }

    public static void clear() {
        voices.clear();
    }
}
