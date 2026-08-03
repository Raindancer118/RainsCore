package de.raindancer.core.items;

/**
 * What happened when somebody tried to use an item.
 *
 * <h2>Why not a boolean</h2>
 * Because the player is told which one it was, and "that is still cooling down", "there are no
 * charges left" and "there was nothing to aim at" are three different sentences. A boolean collapses
 * them into a click that appears to do nothing, which gets clicked again.
 */
public enum UseOutcome {

    /** It ran. */
    RAN,

    /** No such ability. */
    UNKNOWN,

    /** This ability does not answer to that trigger. Not worth telling the player about. */
    WRONG_TRIGGER,

    /** Still cooling down; {@code UseResult#remaining} says how long. */
    ON_COOLDOWN,

    /** Used up. */
    NO_CHARGES,

    /**
     * The ability itself said no — there was nothing to aim at, nowhere to teleport to.
     *
     * <p>Costs neither a charge nor a cooldown, which is what makes a grappling hook that hit the
     * sky feel like a miss rather than a punishment.
     */
    DECLINED,

    /** It threw. The player is told something went wrong; the log has the reason. */
    FAILED
}
