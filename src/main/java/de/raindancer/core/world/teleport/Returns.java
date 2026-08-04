package de.raindancer.core.world.teleport;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where each player was before they were moved — what {@code /back} goes to.
 *
 * <h2>Why this is Core's</h2>
 * Because "the last place I was" is asked by more than one thing. A warp, a home, a teleport request
 * and a death all move somebody, and all four want to be undoable. It lived in the teleport-request
 * plugin because that is where {@code /back} was typed, not because that is where it belongs — and
 * the consequence was exactly what you would expect: a home teleport recorded nothing, so
 * {@code /back} after {@code /home} took you to wherever the last teleport <em>request</em> had.
 *
 * <p>With it here, anything that moves somebody can record it in one line, and there is one answer to
 * where they came from.
 *
 * <h2>Deliberately not saved to disk</h2>
 * A waypoint from before a restart is a lie: the world has been running without it, the reason they
 * were moved is long over, and "go back to where you were three days ago" is not what anybody means.
 * So this is memory only, and a player who logs out is forgotten.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread. On Folia a teleport, a death and a command can each reach a different region
 * thread for the same player.
 */
public final class Returns {

    private final Map<UUID, Waypoint> places = new ConcurrentHashMap<>();

    /**
     * Remembers where somebody was.
     *
     * <p><b>A teleport cannot overwrite a death.</b> Somebody who died and was then moved by a plugin
     * still wants {@code /back} to mean their body — that is the case where it matters most, since
     * their things are on the floor there. Only using it, or dying again, releases that hold.
     *
     * @return whether it was remembered, so a caller can tell "recorded" from "you already have
     *         somewhere better to go back to" rather than guessing
     */
    public boolean remember(UUID player, Waypoint where) {
        if (player == null || where == null || !where.isUsable()) {
            return false;
        }
        // compute, not get-then-put: on Folia a death and a teleport for the same player really can
        // arrive on two threads, and the two-step version lets the teleport win the race it must lose.
        boolean[] kept = new boolean[1];
        places.compute(player, (ignored, held) -> {
            if (held != null && held.cause() == Waypoint.Cause.DEATH
                    && where.cause() != Waypoint.Cause.DEATH) {
                return held;
            }
            kept[0] = true;
            return where;
        });
        return kept[0];
    }

    /**
     * Where they would go back to, without using it up.
     *
     * <p>What a menu asks to grey a button, and what a command asks before it looks at a cooldown.
     */
    public Optional<Waypoint> of(UUID player) {
        return player == null ? Optional.empty() : Optional.ofNullable(places.get(player));
    }

    /**
     * Where they would go back to, and forgets it.
     *
     * <p>Consumed on purpose: kept, {@code /back} twice in a row would be a way to hop between two
     * places for ever, which is a teleport with no cost at all.
     */
    public Optional<Waypoint> take(UUID player) {
        return player == null ? Optional.empty() : Optional.ofNullable(places.remove(player));
    }

    /** Forgets a player. Called when they log out. */
    public void forget(UUID player) {
        if (player != null) {
            places.remove(player);
        }
    }

    /** Forgets everybody. For a plugin being disabled. */
    public void clear() {
        places.clear();
    }

    /** How many are remembered, for a diagnostic and for the tests. */
    public int tracked() {
        return places.size();
    }
}
