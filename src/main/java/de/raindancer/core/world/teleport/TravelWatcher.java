package de.raindancer.core.world.teleport;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * What the plugin asking for a teleport wants told, and when.
 *
 * <p>Every method is defaulted to doing nothing, so a caller that only cares about one of them says
 * only that one. None of them is where a decision is made — {@link Travel} has already decided by
 * the time any of these is called — they are the wording, which is the part that belongs to the
 * plugin rather than to the library.
 *
 * <p>All four arrive on a thread that owns the player. A watcher may send messages and play sounds;
 * it must not block.
 */
public interface TravelWatcher {

    /**
     * A second has passed and there are this many left.
     *
     * <p>Where the action-bar countdown is drawn. Not drawn by {@link Travel} itself, because "Going
     * home in 3…" and "Warping to spawn in 3…" are the plugin's words and a library that wrote them
     * would be a library deciding what the server sounds like.
     */
    default void counting(Player traveller, int secondsLeft, Trip trip) {
    }

    /** They are there. Called after the teleport has actually completed. */
    default void arrived(Player traveller, Location where, Trip trip) {
    }

    /**
     * The warm-up was given up on.
     *
     * @param why which of the named reasons — the plugin words each one for itself
     */
    default void cancelled(Player traveller, TravelReason why, Trip trip) {
    }

    /**
     * It never started, or it could not finish.
     *
     * <p>Both are refusals to the player and both need saying. The reasons are things like already
     * being on the way somewhere, a destination whose world has gone, or nowhere safe within the
     * search radius — that last one especially, since falling back to the original spot would put
     * somebody in the place already known to be dangerous.
     */
    default void refused(Player traveller, TravelReason why, Trip trip) {
    }
}
