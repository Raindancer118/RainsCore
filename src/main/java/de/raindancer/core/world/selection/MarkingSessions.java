package de.raindancer.core.world.selection;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is marking something out right now.
 *
 * <p>Concurrent because on Folia two players clicking in different regions are two threads, and a
 * plain map would drop one of them or throw.
 */
public final class MarkingSessions {

    private final Map<UUID, MarkingSession> byPlayer = new ConcurrentHashMap<>();

    /** Starts a fresh marking, discarding whatever that player had going. */
    public MarkingSession begin(UUID player, String world, MarkingSession.Mode mode) {
        MarkingSession session = new MarkingSession(world, mode);
        byPlayer.put(player, session);
        return session;
    }

    public Optional<MarkingSession> sessionOf(UUID player) {
        return Optional.ofNullable(byPlayer.get(player));
    }

    public boolean isMarking(UUID player) {
        return byPlayer.containsKey(player);
    }

    /** @return whether there was one to end */
    public boolean clear(UUID player) {
        return byPlayer.remove(player) != null;
    }

    public int count() {
        return byPlayer.size();
    }
}
