package de.raindancer.core.social.team;

/**
 * The rules that differ between one plugin's teams and another's.
 *
 * <h2>Why a policy rather than a team system per plugin</h2>
 * Four kinds of thing on a server are all a named group of players, and every one of them had been written
 * separately: a tournament's teams, a bedwars match's teams, a clan, a party. They share the hard parts —
 * membership, exclusive colours, captains, random assignment, the outcome vocabulary for a refusal — and
 * differ in a handful of numbers and booleans. Written separately, each copy gets one of the hard parts
 * subtly wrong: the copy where a colour clash silently reassigns, the copy where two clicks in one tick both
 * pass the size check, the copy where a rename loses the members.
 *
 * <p>So {@link Teams} owns the hard parts and this record carries the differences. Everything here is a value,
 * so a plugin can hold several — a tournament with a lobby policy and a locked-down running policy — and swap
 * which one is in force without the roster noticing.
 *
 * <h2>What is deliberately not here</h2>
 * <b>Whether teams are editable right now.</b> That is not a rule, it is a fact about the moment, and it
 * changes several times during one round. {@link Teams} takes it as a {@code BooleanSupplier} instead, so the
 * plugin that knows why teams are frozen is the one that answers it. Putting it here would mean rebuilding
 * the policy on every phase change, which is a policy two callers can hold different versions of.
 *
 * <p><b>Who may press the button.</b> A permission node is the host's business and means nothing to a roster.
 * {@code playersMayCreate} is as far as this goes, and it answers "is creating a team a thing players do at
 * all on this server", not "may this player".
 *
 * @param maxMembers        most members one team may have; {@code 0} for no limit. Bedwars sets 2 or 4, a
 *                          tournament 2, a clan 0
 * @param maxTeams          most teams there may be; {@code 0} for no limit. A bedwars map has exactly as many
 *                          as it has beds; a server has as many clans as people found
 * @param exclusiveColours  whether two teams may share a colour. <b>The setting that most needs to vary:</b>
 *                          there are sixteen colours, so a tournament and a bedwars match must have them
 *                          exclusive — telling teams apart is the whole point — and a server with two hundred
 *                          clans cannot, because the seventeenth clan could never be founded
 * @param allowSwitching    whether somebody already in a team may move to another one directly. Off means
 *                          leaving first, which is what a clan wants: joining is an act with consequences,
 *                          and switching sideways hides the leaving from everybody watching
 * @param captains          whether a team has a leader at all. A clan does; a bedwars team does not
 * @param playersMayCreate  whether making a team is something players do, or something staff do for them
 * @param playersMayRecolour whether a member may change their own team's colour. Usually the captain's, and
 *                          usually off where colours are exclusive and a match is about to start
 */
public record TeamPolicy(
        int maxMembers,
        int maxTeams,
        boolean exclusiveColours,
        boolean allowSwitching,
        boolean captains,
        boolean playersMayCreate,
        boolean playersMayRecolour) {

    public TeamPolicy {
        // Clamped rather than refused. These arrive from a config file by way of a settings record, and a
        // negative limit is a typo — refused, it takes the plugin down at startup for something that has an
        // obvious reading, and "no limit" is the reading everybody intends by a number below zero.
        maxMembers = Math.max(0, maxMembers);
        maxTeams = Math.max(0, maxTeams);
    }

    /**
     * A tournament: small fixed teams, colours exclusive, no captains, players organise themselves.
     *
     * <p>Two per team because that is what a Hunger Games round has been played with; sixteen teams because
     * that is how many colours there are, and a seventeenth team could not be told apart from another.
     */
    public static TeamPolicy tournament() {
        return new TeamPolicy(2, 16, true, true, false, true, true);
    }

    /**
     * A match on a fixed map: teams as large as the map allows, colours exclusive, nothing player-driven.
     *
     * <p>The teams in a bedwars map exist before anybody joins — one per bed — so players do not create them
     * and do not recolour them. Switching stays on, because a lobby is exactly where somebody changes their
     * mind about which side they are on.
     */
    public static TeamPolicy match(int perTeam, int teams) {
        return new TeamPolicy(perTeam, teams, true, true, false, false, false);
    }

    /**
     * Clans: any size, any number, colours shared, captains, and leaving is a deliberate act.
     *
     * <p>Colours are not exclusive and cannot be — sixteen colours would cap a server at sixteen clans.
     * Switching is off so that leaving a clan is something that happens visibly rather than something that
     * happens as a side effect of joining another.
     */
    public static TeamPolicy clans() {
        return new TeamPolicy(0, 0, false, false, true, true, true);
    }

    /** A party: small, ad hoc, led by whoever made it, colours shared because there may be many. */
    public static TeamPolicy party(int mostMembers) {
        return new TeamPolicy(mostMembers, 0, false, true, true, true, false);
    }

    // ------------------------------------------------------------------ asking

    /** Whether a team of this size may take one more. */
    public boolean hasRoomFor(int currentSize) {
        return maxMembers == 0 || currentSize < maxMembers;
    }

    /** Whether another team may be made when there are this many. */
    public boolean allowsAnotherTeam(int currentTeams) {
        return maxTeams == 0 || currentTeams < maxTeams;
    }

    public boolean isMemberLimited() {
        return maxMembers > 0;
    }

    public boolean isTeamLimited() {
        return maxTeams > 0;
    }

    // ------------------------------------------------------------------ one change at a time

    public TeamPolicy withMaxMembers(int most) {
        return new TeamPolicy(most, maxTeams, exclusiveColours, allowSwitching, captains,
                playersMayCreate, playersMayRecolour);
    }

    public TeamPolicy withMaxTeams(int most) {
        return new TeamPolicy(maxMembers, most, exclusiveColours, allowSwitching, captains,
                playersMayCreate, playersMayRecolour);
    }

    public TeamPolicy withExclusiveColours(boolean exclusive) {
        return new TeamPolicy(maxMembers, maxTeams, exclusive, allowSwitching, captains,
                playersMayCreate, playersMayRecolour);
    }

    public TeamPolicy withAllowSwitching(boolean allow) {
        return new TeamPolicy(maxMembers, maxTeams, exclusiveColours, allow, captains,
                playersMayCreate, playersMayRecolour);
    }

    public TeamPolicy withCaptains(boolean enabled) {
        return new TeamPolicy(maxMembers, maxTeams, exclusiveColours, allowSwitching, enabled,
                playersMayCreate, playersMayRecolour);
    }

    public TeamPolicy withPlayersMayCreate(boolean may) {
        return new TeamPolicy(maxMembers, maxTeams, exclusiveColours, allowSwitching, captains,
                may, playersMayRecolour);
    }

    public TeamPolicy withPlayersMayRecolour(boolean may) {
        return new TeamPolicy(maxMembers, maxTeams, exclusiveColours, allowSwitching, captains,
                playersMayCreate, may);
    }
}
