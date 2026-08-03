package de.raindancer.core.ui.chat;

/**
 * What happened when somebody clicked a chat button.
 *
 * <h2>Why five answers and not a boolean</h2>
 * Because the player is told which one it was, and "that button has expired", "you already answered
 * that" and "that is not your button" are three different things a person needs to hear. A boolean
 * collapses them into one unhelpful refusal, which is how a player ends up clicking [Accept] four
 * more times to see whether it takes.
 */
public enum ClickResult {

    /** The action ran. */
    RAN,

    /** No such button — never registered, revoked, or swept long ago. */
    UNKNOWN,

    /** It was somebody else's button. */
    NOT_YOURS,

    /** It was good once and its time is up. */
    EXPIRED,

    /** A one-shot button that has already been clicked. */
    SPENT,

    /**
     * The action ran and threw.
     *
     * <p>Distinct from {@link #RAN} so the player is told something went wrong rather than being
     * left to assume it worked. The button is spent either way — see {@link ClickActions}.
     */
    FAILED
}
