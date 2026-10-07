package de.raindancer.core.moderation.vanish;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Whether something a vanished player types goes out. Vanish hides a player from the world; one chat line
 * undoes it in front of everybody, and it is the easiest slip there is. So the line is held, and the player
 * asked — with a button to send it anyway, and one to stop asking until they reappear.
 *
 * <p>Decides only; the listener does the holding and the asking.
 */
public final class VanishedSpeech {

    /** How long a "send anyway" stays good for. Longer, and a click from earlier could send a line now. */
    public static final Duration ALLOW_FOR = Duration.ofMinutes(2);

    /** Commands that put words in front of other players. Bare names; any namespace is taken off first. */
    public static final Set<String> SPEAKING = Set.of("msg", "tell", "w", "whisper", "m", "pm", "dm", "t",
            "r", "reply", "me", "say", "mail", "emsg", "etell", "ereply");

    private record Allowance(String line, long until) {
    }

    private final LongSupplier clock;
    private final Map<UUID, Allowance> allowed = new ConcurrentHashMap<>();
    private final Set<UUID> notAsking = ConcurrentHashMap.newKeySet();

    public VanishedSpeech() {
        this(System::currentTimeMillis);
    }

    public VanishedSpeech(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Whether {@code line} must be held rather than sent. Uses up a matching "send anyway"; a visible player
     * also ends any "stop asking", so the next vanish asks again.
     */
    public boolean shouldHold(UUID player, boolean vanished, String line) {
        if (!vanished) {
            notAsking.remove(player);
            return false;
        }
        if (notAsking.contains(player)) {
            return false;
        }
        Allowance allowance = allowed.get(player);
        if (allowance != null && allowance.line().equals(line) && clock.getAsLong() <= allowance.until()
                && allowed.remove(player, allowance)) {
            return false;
        }
        return true;
    }

    /** "Send anyway": exactly this line, once, soon. */
    public void allowOnce(UUID player, String line) {
        allowed.put(player, new Allowance(line, clock.getAsLong() + ALLOW_FOR.toMillis()));
    }

    /** "Stop asking" until they are visible again. */
    public void stopAsking(UUID player) {
        notAsking.add(player);
    }

    public void forget(UUID player) {
        allowed.remove(player);
        notAsking.remove(player);
    }

    /** The messaging command {@code commandLine} is, when it is one that says something; empty otherwise. */
    public static Optional<String> speaking(String commandLine) {
        if (commandLine == null) {
            return Optional.empty();
        }
        String line = commandLine.strip();
        if (line.startsWith("/")) {
            line = line.substring(1);
        }
        String[] parts = line.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            return Optional.empty();
        }
        String word = parts[0].toLowerCase(Locale.ROOT);
        int colon = word.indexOf(':');
        String bare = colon >= 0 ? word.substring(colon + 1) : word;
        return SPEAKING.contains(bare) ? Optional.of(bare) : Optional.empty();
    }
}
