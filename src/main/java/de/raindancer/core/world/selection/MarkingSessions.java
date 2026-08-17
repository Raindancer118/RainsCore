package de.raindancer.core.world.selection;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Which player is marking what out, right now — one owning plugin's worth. */
public final class MarkingSessions {

    private final Map<UUID, MarkingSession> sessions = new ConcurrentHashMap<>();

    public MarkingSession begin(UUID player, String worldName, MarkingSession.Mode mode) {
        MarkingSession session = new MarkingSession(worldName, mode);
        sessions.put(player, session);
        return session;
    }

    public Optional<MarkingSession> sessionOf(UUID player) {
        return Optional.ofNullable(sessions.get(player));
    }

    public boolean hasSession(UUID player) {
        return sessions.containsKey(player);
    }

    public void clear(UUID player) {
        sessions.remove(player);
    }
}
