package de.raindancer.core.world.teleport;

import de.raindancer.core.world.safety.Spot;

/**
 * Somebody part-way through being sent somewhere.
 *
 * <p>Only what the decision needs: where they were standing when they asked, how many seconds are
 * left, and what they are waiting for. Not where they are going — that is a {@code Location}, which
 * holds a world, and this has to be a value a test can build without a server.
 *
 * @param traveller   who is waiting
 * @param from        the block they were standing on when they asked; moving off it cancels
 * @param secondsLeft what the action bar counts down
 * @param what        what they are going to, for the line that says so — "spawn", "your bed"
 */
public record Departure(java.util.UUID traveller, Spot from, int secondsLeft, String what) {

    /** The same departure, a second closer. */
    Departure aSecondOn() {
        return new Departure(traveller, from, secondsLeft - 1, what);
    }

    /** Whether the countdown is over. */
    public boolean isDue() {
        return secondsLeft <= 0;
    }

    /**
     * Whether this block is a different one from the one they set off waiting on.
     *
     * <p>The block, never the exact position. A player standing perfectly still is not still —
     * breathing, a mob pushing past, a boat rocking and the client's own idle animation all move
     * them by fractions of a block — so a warm-up measured on the position is one nobody can ever
     * complete.
     */
    public boolean isAwayFrom(Spot now) {
        return now != null && !now.equals(from);
    }
}
