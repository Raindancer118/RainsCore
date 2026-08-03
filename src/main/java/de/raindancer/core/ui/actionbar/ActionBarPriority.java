package de.raindancer.core.ui.actionbar;

/**
 * How badly one plugin wants the action bar, when another wants it too.
 *
 * <h2>Why four and not an int</h2>
 * An integer priority is an invitation to an arms race: every plugin picks 100, then the next picks
 * 1000, and the ordering ends up meaning nothing. Four named levels force the question that actually
 * decides it — <em>is this a running commentary, an answer, or a warning?</em> — and two plugins
 * reading this list will agree about where their message belongs.
 */
public enum ActionBarPriority {

    /**
     * A running commentary the player can miss without losing anything: a flight's progress, the
     * name of the claim they are standing in.
     */
    LOW,

    /**
     * An answer to something the player just did: "Home set", "Request sent".
     *
     * <p>The common case, and the one {@code Chat.tell} uses.
     */
    NORMAL,

    /** A refusal, or something that went wrong: "You may not build here." */
    HIGH,

    /**
     * Something the player has to see now — being kicked in ten seconds, a world about to close.
     *
     * <p>Deliberately awkward to reach for. Anything that outranks a refusal had better be rare.
     */
    CRITICAL
}
