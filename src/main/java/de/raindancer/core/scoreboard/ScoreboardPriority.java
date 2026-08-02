package de.raindancer.core.scoreboard;

/**
 * How badly one plugin wants a player's sidebar, when another wants it too.
 *
 * <p>Four named levels rather than an integer, for the same reason as
 * {@link de.raindancer.core.actionbar.ActionBarPriority}: an integer priority is an arms race, where
 * every plugin picks 100 and the next picks 1000 until the ordering means nothing. Naming them
 * forces the question that actually decides it.
 */
public enum ScoreboardPriority {

    /** The server's usual sidebar: whose land this is, how much money you have. */
    LOW,

    /** Something the player asked to see. */
    NORMAL,

    /** Something happening now that the sidebar is the natural place for — a flight in progress. */
    HIGH,

    /** An event that owns the screen while it runs. Rare on purpose. */
    CRITICAL
}
