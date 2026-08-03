package de.raindancer.core.player;

/**
 * What happened when a management action was tried.
 *
 * <p>Answers rather than a boolean, because a moderator clicking a button needs to know which of
 * "done", "they logged out", "that would have killed them" and "there was nothing to do" happened.
 * A silent false gets clicked again.
 */
public enum Outcome {

    /** It happened. */
    DONE("Done."),

    /** It was already the case. Nothing was wrong and nothing changed. */
    NOTHING_TO_DO("There was nothing to do."),

    /** They are not on the server. */
    NOT_ONLINE("They are not online."),

    /** The action would have killed them, and it was not a kill. */
    WOULD_KILL("That would have killed them; use kill if you meant to."),

    /** A number outside what the game accepts. */
    OUT_OF_RANGE("That is outside what the game allows."),

    /** A name that is not a thing — a gamemode, an effect. */
    NOT_UNDERSTOOD("That is not something this server knows about.");

    private final String saying;

    Outcome(String saying) {
        this.saying = saying;
    }

    /** What to tell whoever pressed the button. */
    public String saying() {
        return saying;
    }

    public boolean isDone() {
        return this == DONE;
    }
}
