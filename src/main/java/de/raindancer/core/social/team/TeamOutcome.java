package de.raindancer.core.social.team;

/**
 * What happened when something asked to change a team.
 *
 * <h2>Why an outcome and not a boolean, and not an exception</h2>
 * A boolean cannot be turned into a sentence. Every one of these refusals is something a player has to be
 * told, in words, in the moment — "that colour is taken", "that team is full", "teams are locked now" — and a
 * method returning {@code false} forces its caller to work out which of nine reasons applied, by re-asking
 * the same questions the registry just asked. Two sets of reasoning about the same rule is how a menu comes to
 * grey a button for one reason and refuse the click for another.
 *
 * <p>Not an exception either, because none of this is exceptional. Somebody clicking a full team is the
 * ordinary use of a team menu, and an exception per click is a stack trace in the console for a thing that
 * worked correctly.
 *
 * <p>The enum is deliberately the same vocabulary for every plugin using {@link Teams}. A tournament, a
 * bedwars match and a clans plugin each want their own wording for {@link #COLOUR_TAKEN}, and each looks it
 * up under its own message key — but the <em>reason</em> is the same reason, decided in one place.
 */
public enum TeamOutcome {

    /** It worked. */
    SUCCESS,

    /** There is no team with that id. Usually a stale menu: somebody deleted it while it was open. */
    NO_SUCH_TEAM,

    /** Another team is already called that. Compared case-insensitively — see {@link TeamId}. */
    NAME_TAKEN,

    /**
     * Another team already has that colour.
     *
     * <p>Only ever returned where {@link TeamPolicy#exclusiveColours()} is on. The refusal is outright: the
     * registry never picks a different colour on the caller's behalf, because a team that quietly ends up a
     * colour nobody chose is a team whose members find out by looking at their armour.
     */
    COLOUR_TAKEN,

    /** Every colour is in use, so no further team can be told apart from the ones that exist. */
    NO_COLOUR_FREE,

    /** That team is at {@link TeamPolicy#maxMembers()}. */
    TEAM_FULL,

    /** There are already {@link TeamPolicy#maxTeams()} teams. */
    TOO_MANY_TEAMS,

    /**
     * Teams are not editable at the moment.
     *
     * <p>Not a rule but a fact about the moment — a round that has started, a match in progress. See the
     * {@code frozen} argument to {@link Teams}.
     */
    FROZEN,

    /**
     * That player is not eligible to be in a team here.
     *
     * <p>What eligibility means is the host's: a whitelisted tribute, a player in the match, somebody not
     * already banned from clans. {@link Teams} only knows the answer, never the reason.
     */
    NOT_ELIGIBLE,

    /** They are already in that team, and the change would do nothing. */
    ALREADY_IN_THAT_TEAM,

    /**
     * They are in another team and this policy does not allow moving directly.
     *
     * <p>See {@link TeamPolicy#allowSwitching()} — off, leaving is a separate, visible act.
     */
    MUST_LEAVE_FIRST,

    /** They are not in a team, so there is nothing to leave or lead. */
    NOT_IN_A_TEAM,

    /** A captain was asked for and this policy has no captains — see {@link TeamPolicy#captains()}. */
    NO_CAPTAINS_HERE,

    /** The proposed captain is not in the team. A team led from outside is not a state worth having. */
    CAPTAIN_NOT_A_MEMBER;

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    public boolean isRefusal() {
        return this != SUCCESS;
    }

    /**
     * A stable lower-case name, for building a message key.
     *
     * <p>So a plugin writes {@code messages.send(who, "clans." + outcome.key())} and its wording file has a
     * line per reason, rather than a switch with fourteen arms in every screen that changes a team.
     */
    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
