package de.raindancer.core.world.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Pauses a run's clock while every participant is offline, and resumes it the moment one comes back.
 *
 * <h2>Why the host registers this rather than {@link SpeedrunSession} doing it</h2>
 * Same reasoning as {@code TravelListener} (see its javadoc): a library that registers its own
 * listeners is a library whose behaviour cannot be switched off. A caller who wants a run that keeps
 * timing while everybody is offline — unusual, but not this class's decision to make — simply does not
 * register it.
 *
 * <pre>{@code
 * SpeedrunSession session = new SpeedrunSession(participants);
 * getServer().getPluginManager().registerEvents(new SpeedrunOccupancyListener(session), this);
 * }</pre>
 */
public final class SpeedrunOccupancyListener implements Listener {

    private final SpeedrunSession session;

    public SpeedrunOccupancyListener(SpeedrunSession session) {
        this.session = session;
    }

    /**
     * The last participant logged out.
     *
     * <p>The quitting player is still findable through {@code Bukkit.getPlayer} for at least part of
     * this event's handling on some Paper versions, so "is anybody else online" cannot simply ask
     * {@code Bukkit.getOnlinePlayers()} and trust it excludes them. Instead, the quitting player is
     * excluded explicitly by id, and every other participant is asked directly.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (session.state() != SpeedrunState.RUNNING) {
            return;
        }
        UUID quitting = event.getPlayer().getUniqueId();
        for (UUID participant : session.participants()) {
            if (participant.equals(quitting)) {
                continue;   // the one leaving is not "still online" just because the event hasn't finished
            }
            if (Bukkit.getPlayer(participant) != null) {
                return;   // somebody else is still here
            }
        }
        session.pauseForEmptyRoster();
    }

    /** The first participant of a paused run comes back. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (session.state() != SpeedrunState.PAUSED) {
            return;
        }
        Player joined = event.getPlayer();
        if (session.participants().contains(joined.getUniqueId())) {
            session.resume();
        }
    }
}
