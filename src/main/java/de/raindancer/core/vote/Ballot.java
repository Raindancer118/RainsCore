package de.raindancer.core.vote;

/**
 * What happened when somebody tried to vote.
 *
 * <p>Six answers rather than a boolean, because "your vote was not counted" gets asked again and
 * "the vote closed a minute ago" does not. Telling a player who changed their mind that they
 * "already voted" is the sort of small wrongness that makes people distrust the result.
 */
public enum Ballot {

    /** Counted. */
    COUNTED("Your vote has been counted."),

    /** They had voted for something else, and now they have not. */
    CHANGED("Your vote has been changed."),

    /** They voted for the same thing again. Nothing happened, and nothing was wrong. */
    ALREADY("You already voted for that."),

    /** There is no such vote, or there never was. */
    NO_SUCH_VOTE("There is no vote with that name."),

    /** The vote has finished. */
    CLOSED("That vote has already ended."),

    /** They are not one of the people being asked. */
    NOT_YOURS("You are not being asked in this vote."),

    /** That is not one of the answers. */
    NOT_AN_OPTION("That is not one of the answers.");

    private final String saying;

    Ballot(String saying) {
        this.saying = saying;
    }

    /** What to tell the player, in their words. */
    public String saying() {
        return saying;
    }

    /** Whether their answer is now on the record. */
    public boolean isCounted() {
        return this == COUNTED || this == CHANGED;
    }
}
