package de.raindancer.core.ui.bossbar;

/**
 * How badly one plugin wants a place among the boss bars, when more want one than fit.
 *
 * <p>Unlike the action bar and the sidebar this is not winner-takes-all — several bars are shown at
 * once — so this decides which ones fill {@link BossBars#MAX_VISIBLE} rather than which single one
 * survives.
 */
public enum BarPriority {

    /** Background information: how full a claim is, how long until the next market. */
    LOW,

    /** Something the player is doing right now — a flight in progress. */
    NORMAL,

    /** Something with a deadline attached that they should not miss. */
    HIGH,

    /** The server is about to do something to them. Rare on purpose. */
    CRITICAL
}
