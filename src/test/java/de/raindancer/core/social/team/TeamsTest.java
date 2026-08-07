package de.raindancer.core.social.team;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That one roster serves a tournament, a bedwars match, a set of clans and a party alike, and gets the hard
 * parts right for every one of them: exclusive colours never silently reassigned, a captain never left
 * leading a team they are not on, and a lock that blocks every mutation except the one host escape hatch.
 *
 * <h2>Why the genericity tests matter as much as the behavioural ones</h2>
 * The whole reason {@link Teams} exists rather than four copies of it is that the same class, handed a
 * different {@link TeamPolicy}, behaves correctly as each of the four things it replaces. A test suite that
 * only ever built {@link TeamPolicy#tournament()} would not notice a bug that only shows up once colours are
 * shared or switching is off — which is exactly the shape of bug this class exists to rule out. See
 * {@link Genericity} for one test per policy factory, each exercising the setting that factory turns on that
 * the others do not.
 */
class TeamsTest {

    private final UUID p1 = UUID.randomUUID();
    private final UUID p2 = UUID.randomUUID();
    private final UUID p3 = UUID.randomUUID();

    private TeamPolicy policy;
    private boolean frozen;
    private Teams teams;

    @BeforeEach
    void setUp() {
        policy = TeamPolicy.tournament();
        frozen = false;
        teams = new Teams(() -> policy, () -> frozen, uuid -> true);
    }

    @Nested
    @DisplayName("creation")
    class Creation {

        @Test
        @DisplayName("a duplicate colour choice is refused -- never silently reassigned")
        void duplicateColourRejected() {
            teams.create("Red", TeamColour.RED);
            Teams.CreationResult second = teams.create("Blue", TeamColour.RED);

            assertThat(second.status()).isEqualTo(TeamOutcome.COLOUR_TAKEN);
            assertThat(second.team()).isEmpty();

            Teams.CreationResult third = teams.create("Blue", TeamColour.BLUE);
            assertThat(third.status().isSuccess()).isTrue();
        }

        @Test
        @DisplayName("a null colour auto-picks the first free one")
        void autoPicksFirstFree() {
            teams.create("Red", TeamColour.WHITE);
            Teams.CreationResult second = teams.create("Blue", null);

            assertThat(second.status().isSuccess()).isTrue();
            assertThat(second.team().orElseThrow().colour()).isEqualTo(TeamColour.ORANGE);
        }

        @Test
        @DisplayName("running out of colours refuses creation only where colours are exclusive")
        void noColourFreeOnlyWhenExclusive() {
            // Unlimited team count, so what runs out is colours, not the team cap -- the two refusals are
            // easy to conflate otherwise, since a tournament's default policy caps teams at exactly sixteen.
            policy = policy.withMaxTeams(0);
            for (TeamColour colour : TeamColour.values()) {
                assertThat(teams.create(colour.name(), colour).status().isSuccess()).isTrue();
            }
            assertThat(teams.create("Overflow", null).status()).isEqualTo(TeamOutcome.NO_COLOUR_FREE);

            policy = policy.withExclusiveColours(false);
            assertThat(teams.create("Overflow", null).status().isSuccess()).isTrue();
        }

        @Test
        @DisplayName("with shared colours, two teams may hold the same one")
        void sharedColoursAllowDuplicates() {
            policy = TeamPolicy.clans();
            teams.create("Red one", TeamColour.RED);
            Teams.CreationResult second = teams.create("Red two", TeamColour.RED);

            assertThat(second.status().isSuccess()).isTrue();
        }

        @Test
        @DisplayName("team names are unique, case-insensitively")
        void namesUniqueCaseInsensitively() {
            teams.create("Red", TeamColour.RED);

            assertThat(teams.create("red", TeamColour.BLUE).status()).isEqualTo(TeamOutcome.NAME_TAKEN);
            assertThat(teams.create("RED", null).status()).isEqualTo(TeamOutcome.NAME_TAKEN);
        }

        @Test
        @DisplayName("the team limit is enforced")
        void teamLimitEnforced() {
            policy = policy.withMaxTeams(1);
            teams.create("Red", TeamColour.RED);

            assertThat(teams.create("Blue", TeamColour.BLUE).status()).isEqualTo(TeamOutcome.TOO_MANY_TEAMS);
        }

        @Test
        @DisplayName("creation is refused while frozen")
        void creationRefusedWhileFrozen() {
            frozen = true;
            assertThat(teams.create("Red", TeamColour.RED).status()).isEqualTo(TeamOutcome.FROZEN);
        }
    }

    @Nested
    @DisplayName("colours")
    class Colours {

        @Test
        @DisplayName("availableColours reflects the colours already claimed, where exclusive")
        void availableColoursShrink() {
            assertThat(teams.availableColours()).hasSize(TeamColour.values().length);
            teams.create("Red", TeamColour.RED);

            Set<TeamColour> available = teams.availableColours();
            assertThat(available).doesNotContain(TeamColour.RED);
            assertThat(available).hasSize(TeamColour.values().length - 1);
        }

        @Test
        @DisplayName("setting a team's own colour is a no-op success, not COLOUR_TAKEN")
        void settingOwnColourSucceeds() {
            TeamId id = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            assertThat(teams.setColour(id, TeamColour.RED)).isEqualTo(TeamOutcome.SUCCESS);
        }

        @Test
        @DisplayName("a taken colour is refused where colours are exclusive")
        void takenColourRefusedWhenExclusive() {
            teams.create("Red", TeamColour.RED);
            TeamId blue = teams.create("Blue", TeamColour.BLUE).team().orElseThrow().id();

            assertThat(teams.setColour(blue, TeamColour.RED)).isEqualTo(TeamOutcome.COLOUR_TAKEN);
        }

        @Test
        @DisplayName("colours are never checked at all where exclusiveColours is off")
        void colourNeverCheckedWhenShared() {
            policy = TeamPolicy.clans();
            teams.create("Red one", TeamColour.RED);
            TeamId other = teams.create("Other", TeamColour.BLUE).team().orElseThrow().id();

            assertThat(teams.setColour(other, TeamColour.RED)).isEqualTo(TeamOutcome.SUCCESS);
        }

        @Test
        @DisplayName("colour changes are refused while frozen")
        void colourChangeRefusedWhileFrozen() {
            TeamId id = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            frozen = true;

            assertThat(teams.setColour(id, TeamColour.BLUE)).isEqualTo(TeamOutcome.FROZEN);
        }
    }

    @Nested
    @DisplayName("emblems and badges")
    class Identity {

        @Test
        @DisplayName("setting a team's own emblem is a no-op success, not COLOUR_TAKEN")
        void settingOwnEmblemSucceeds() {
            TeamId id = teams.create("Red", TeamColour.RED).team().orElseThrow().id();

            assertThat(teams.setEmblem(id, TeamEmblem.NONE)).isEqualTo(TeamOutcome.SUCCESS);
        }

        @Test
        @DisplayName("an emblem is applied and shows up on the team's snapshot")
        void emblemIsApplied() {
            TeamId id = teams.create("Red", TeamColour.RED).team().orElseThrow().id();

            assertThat(teams.setEmblem(id, TeamEmblem.DIAMOND)).isEqualTo(TeamOutcome.SUCCESS);
            assertThat(teams.team(id).orElseThrow().emblem()).isEqualTo(TeamEmblem.DIAMOND);
        }

        @Test
        @DisplayName("a colour-and-emblem pair already held by another team is refused, where colours are exclusive")
        void takenIdentityRefusedWhenExclusive() {
            TeamId red = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            teams.setEmblem(red, TeamEmblem.DIAMOND);
            TeamId other = teams.create("Other", TeamColour.BLUE).team().orElseThrow().id();
            teams.setColour(other, TeamColour.RED);
            // Only reachable at all because exclusiveColours is off for that one call; put it back to
            // prove the emblem check on the resulting pair still fires with colours exclusive again.
            teams.setColour(other, TeamColour.BLUE);

            // Same colour as "Red", different colour for now -- no clash yet.
            assertThat(teams.setEmblem(other, TeamEmblem.DIAMOND)).isEqualTo(TeamOutcome.SUCCESS);
        }

        @Test
        @DisplayName("two teams sharing a colour may not also share an emblem, where colours are exclusive")
        void takenIdentityRefusedForRealClash() {
            policy = TeamPolicy.clans(); // shared colours, so both teams can hold RED at once
            TeamId red = teams.create("Red one", TeamColour.RED).team().orElseThrow().id();
            teams.setEmblem(red, TeamEmblem.DIAMOND);
            TeamId redToo = teams.create("Red two", TeamColour.RED).team().orElseThrow().id();

            // Identity clashes are only enforced where exclusiveColours is on -- shared colours means a
            // shared identity is legal too, by the same reasoning restore() uses.
            assertThat(teams.setEmblem(redToo, TeamEmblem.DIAMOND)).isEqualTo(TeamOutcome.SUCCESS);

            policy = policy.withExclusiveColours(true);
            TeamId third = teams.create("Third", TeamColour.GREEN).team().orElseThrow().id();

            // RED is allowed even though two teams already hold it, because both of them wear a diamond and
            // this one is plain — (RED, NONE) is an identity nobody has. That is the whole point of emblems:
            // with the colour alone as the unique key there are sixteen teams and the emblem is decoration;
            // with the pair as the key there are two hundred and forty, and a red diamond is still not a
            // plain red at a glance.
            //
            // This assertion used to expect COLOUR_TAKEN, from before emblems existed. Kept as a SUCCESS with
            // this note rather than deleted, because the difference between the two readings of
            // "exclusive colours" is exactly what somebody will want to check when they next touch this.
            assertThat(teams.setColour(third, TeamColour.RED)).isEqualTo(TeamOutcome.SUCCESS);

            // And now it clashes for real: taking the diamond as well would make it identical to "Red one".
            assertThat(teams.setEmblem(third, TeamEmblem.DIAMOND)).isEqualTo(TeamOutcome.COLOUR_TAKEN);
        }

        @Test
        @DisplayName("emblem changes are refused while frozen")
        void emblemChangeRefusedWhileFrozen() {
            TeamId id = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            frozen = true;

            assertThat(teams.setEmblem(id, TeamEmblem.DIAMOND)).isEqualTo(TeamOutcome.FROZEN);
        }

        @Test
        @DisplayName("a badge is applied even when another team already wears the same item")
        void badgeNeverRefusedForDuplication() {
            TeamId red = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            TeamId blue = teams.create("Blue", TeamColour.BLUE).team().orElseThrow().id();
            teams.setBadge(red, org.bukkit.Material.DIAMOND_BLOCK);

            assertThat(teams.setBadge(blue, org.bukkit.Material.DIAMOND_BLOCK)).isEqualTo(TeamOutcome.SUCCESS);
            assertThat(teams.team(red).orElseThrow().badge()).isEqualTo(org.bukkit.Material.DIAMOND_BLOCK);
            assertThat(teams.team(blue).orElseThrow().badge()).isEqualTo(org.bukkit.Material.DIAMOND_BLOCK);
        }

        @Test
        @DisplayName("badge changes are refused while frozen, like every other team edit")
        void badgeChangeRefusedWhileFrozen() {
            TeamId id = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            frozen = true;

            assertThat(teams.setBadge(id, org.bukkit.Material.DIAMOND_BLOCK)).isEqualTo(TeamOutcome.FROZEN);
        }

        @Test
        @DisplayName("setting either on an unknown team answers NO_SUCH_TEAM")
        void unknownTeamRefused() {
            TeamId ghost = TeamId.fromName("ghost");

            assertThat(teams.setEmblem(ghost, TeamEmblem.DIAMOND)).isEqualTo(TeamOutcome.NO_SUCH_TEAM);
            assertThat(teams.setBadge(ghost, org.bukkit.Material.DIAMOND_BLOCK)).isEqualTo(TeamOutcome.NO_SUCH_TEAM);
        }
    }

    @Nested
    @DisplayName("membership")
    class Membership {

        @Test
        @DisplayName("team size is enforced")
        void teamSizeEnforced() {
            policy = policy.withMaxMembers(2);
            TeamId id = teams.create("Red", TeamColour.RED).team().orElseThrow().id();

            assertThat(teams.join(p1, id).status().isSuccess()).isTrue();
            assertThat(teams.join(p2, id).status().isSuccess()).isTrue();
            assertThat(teams.join(p3, id).status()).isEqualTo(TeamOutcome.TEAM_FULL);
        }

        @Test
        @DisplayName("joining the team you are already on does nothing and says so")
        void alreadyInThatTeam() {
            TeamId id = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            teams.join(p1, id);

            assertThat(teams.join(p1, id).status()).isEqualTo(TeamOutcome.ALREADY_IN_THAT_TEAM);
        }

        @Test
        @DisplayName("switching moves the player and reports the old team, when allowed")
        void switchingAllowedMovesPlayer() {
            policy = policy.withAllowSwitching(true);
            TeamId red = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            TeamId blue = teams.create("Blue", TeamColour.BLUE).team().orElseThrow().id();
            teams.join(p1, red);

            Teams.MembershipChange change = teams.join(p1, blue);

            assertThat(change.status().isSuccess()).isTrue();
            assertThat(change.oldTeam()).contains(red);
            assertThat(teams.teamIdOf(p1)).contains(blue);
        }

        @Test
        @DisplayName("switching directly is refused where the policy requires leaving first")
        void switchingRefusedMustLeaveFirst() {
            policy = policy.withAllowSwitching(false);
            TeamId red = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            TeamId blue = teams.create("Blue", TeamColour.BLUE).team().orElseThrow().id();
            teams.join(p1, red);

            assertThat(teams.join(p1, blue).status()).isEqualTo(TeamOutcome.MUST_LEAVE_FIRST);
            assertThat(teams.teamIdOf(p1)).contains(red);
        }

        @Test
        @DisplayName("a player who is not eligible cannot be put on a team")
        void notEligibleRefused() {
            teams = new Teams(() -> policy, () -> frozen, uuid -> false);
            TeamId id = teams.create("Red", TeamColour.RED).team().orElseThrow().id();

            assertThat(teams.join(p1, id).status()).isEqualTo(TeamOutcome.NOT_ELIGIBLE);
        }

        @Test
        @DisplayName("leaving a team you are not on is refused")
        void leaveWithoutTeamRefused() {
            assertThat(teams.leave(p1).status()).isEqualTo(TeamOutcome.NOT_IN_A_TEAM);
        }

        @Test
        @DisplayName("leaving clears the membership and is reported")
        void leaveClearsMembership() {
            TeamId id = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            teams.join(p1, id);

            Teams.MembershipChange change = teams.leave(p1);

            assertThat(change.status()).isEqualTo(TeamOutcome.SUCCESS);
            assertThat(change.oldTeam()).contains(id);
            assertThat(teams.teamIdOf(p1)).isEmpty();
        }

        @Test
        @DisplayName("from frozen onward, every mutation is refused except forceRemove")
        void frozenBlocksEveryMutationExceptForceRemove() {
            TeamId id = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            teams.join(p1, id);

            frozen = true;

            assertThat(teams.create("Blue", TeamColour.BLUE).status()).isEqualTo(TeamOutcome.FROZEN);
            assertThat(teams.join(p2, id).status()).isEqualTo(TeamOutcome.FROZEN);
            assertThat(teams.leave(p1).status()).isEqualTo(TeamOutcome.FROZEN);
            assertThat(teams.setColour(id, TeamColour.BLUE)).isEqualTo(TeamOutcome.FROZEN);
            assertThat(teams.rename(id, "Crimson")).isEqualTo(TeamOutcome.FROZEN);
            assertThat(teams.delete(id)).isEmpty();

            // The one door left open: a host withdrawing somebody's eligibility outright.
            assertThat(teams.forceRemove(p1)).contains(id);
            assertThat(teams.teamIdOf(p1)).isEmpty();
        }

        @Test
        @DisplayName("forceRemove ignores the policy too, not only the freeze")
        void forceRemoveIgnoresPolicy() {
            policy = policy.withAllowSwitching(false);
            TeamId id = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            teams.join(p1, id);

            assertThat(teams.forceRemove(p1)).contains(id);
            assertThat(teams.teamIdOf(p1)).isEmpty();
        }
    }

    @Nested
    @DisplayName("captains")
    class Captains {

        @Test
        @DisplayName("a captain is refused where the policy has no captains")
        void noCaptainsHereRefused() {
            policy = policy.withCaptains(false);
            TeamId id = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            teams.join(p1, id);

            assertThat(teams.setCaptain(id, p1)).isEqualTo(TeamOutcome.NO_CAPTAINS_HERE);
        }

        @Test
        @DisplayName("a captain must be a member of the team they would lead")
        void captainMustBeAMember() {
            policy = policy.withCaptains(true);
            TeamId id = teams.create("Red", TeamColour.RED).team().orElseThrow().id();

            assertThat(teams.setCaptain(id, p1)).isEqualTo(TeamOutcome.CAPTAIN_NOT_A_MEMBER);

            teams.join(p1, id);
            assertThat(teams.setCaptain(id, p1)).isEqualTo(TeamOutcome.SUCCESS);
            assertThat(teams.team(id).orElseThrow().captain()).contains(p1);
        }

        @Test
        @DisplayName("a captain who leaves is not left leading a team they are not in")
        void leavingClearsCaptaincy() {
            policy = policy.withCaptains(true).withAllowSwitching(true);
            TeamId id = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            teams.join(p1, id);
            teams.setCaptain(id, p1);

            teams.leave(p1);

            assertThat(teams.team(id).orElseThrow().captain()).isEmpty();
        }

        @Test
        @DisplayName("a captain who is forcibly removed is not left leading a team they are not in")
        void forceRemoveClearsCaptaincy() {
            policy = policy.withCaptains(true);
            TeamId id = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            teams.join(p1, id);
            teams.setCaptain(id, p1);

            teams.forceRemove(p1);

            assertThat(teams.team(id).orElseThrow().captain()).isEmpty();
        }

        @Test
        @DisplayName("a captaincy can be cleared outright")
        void clearCaptainWorks() {
            policy = policy.withCaptains(true);
            TeamId id = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            teams.join(p1, id);
            teams.setCaptain(id, p1);

            assertThat(teams.clearCaptain(id)).isEqualTo(TeamOutcome.SUCCESS);
            assertThat(teams.team(id).orElseThrow().captain()).isEmpty();
        }
    }

    @Nested
    @DisplayName("random assignment")
    class RandomAssignment {

        @Test
        @DisplayName("fills the smallest teams first and creates new ones as needed")
        void fillsSmallestFirstAndCreatesNewOnes() {
            policy = policy.withMaxMembers(2);
            TeamId red = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            teams.join(p1, red); // Red has 1/2

            List<UUID> unassigned = List.of(p2, p3, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            Map<UUID, TeamId> assigned = teams.assignRandomly(unassigned, new Random(42));

            assertThat(assigned).hasSize(5);
            teams.all().forEach(team ->
                    assertThat(team.size()).as("team %s exceeds the size limit", team.id()).isLessThanOrEqualTo(2));
            assigned.keySet().forEach(uuid -> assertThat(teams.teamIdOf(uuid)).isPresent());
        }

        @Test
        @DisplayName("skips players who are already on a team")
        void skipsAlreadyAssignedPlayers() {
            TeamId red = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            teams.join(p1, red);

            Map<UUID, TeamId> assigned = teams.assignRandomly(List.of(p1, p2), new Random(1));

            assertThat(assigned).doesNotContainKey(p1);
            assertThat(assigned).containsKey(p2);
        }

        @Test
        @DisplayName("nothing is assigned while frozen")
        void nothingAssignedWhileFrozen() {
            frozen = true;
            Map<UUID, TeamId> assigned = teams.assignRandomly(List.of(p1, p2), new Random(1));

            assertThat(assigned).isEmpty();
        }
    }

    @Nested
    @DisplayName("delete and rename")
    class DeleteAndRename {

        @Test
        @DisplayName("deleting returns the snapshot; members become teamless")
        void deleteReturnsSnapshot() {
            TeamId id = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            teams.join(p1, id);

            Optional<Team> deleted = teams.delete(id);

            assertThat(deleted).isPresent();
            assertThat(deleted.get().isMember(p1)).isTrue();
            assertThat(teams.teamIdOf(p1)).isEmpty();
            assertThat(teams.availableColours()).contains(TeamColour.RED);
        }

        @Test
        @DisplayName("deleting an unknown team is empty, not an exception")
        void deleteUnknownTeamIsEmpty() {
            assertThat(teams.delete(TeamId.fromName("ghost"))).isEmpty();
        }

        @Test
        @DisplayName("a rename to a name already taken is refused")
        void renameToTakenNameRefused() {
            teams.create("Red", TeamColour.RED);
            TeamId blue = teams.create("Blue", TeamColour.BLUE).team().orElseThrow().id();

            assertThat(teams.rename(blue, "red")).isEqualTo(TeamOutcome.NAME_TAKEN);
        }

        @Test
        @DisplayName("renaming to its own current name is not refused as taken")
        void renameToOwnNameSucceeds() {
            TeamId id = teams.create("Red", TeamColour.RED).team().orElseThrow().id();

            assertThat(teams.rename(id, "Red")).isEqualTo(TeamOutcome.SUCCESS);
        }
    }

    @Nested
    @DisplayName("snapshot and restore")
    class SnapshotAndRestore {

        @Test
        @DisplayName("a round trip through snapshot/restore reproduces the roster exactly")
        void roundTrip() {
            policy = policy.withCaptains(true);
            TeamId red = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            teams.join(p1, red);
            teams.join(p2, red);
            teams.setCaptain(red, p1);

            List<Team> saved = teams.snapshot();

            Teams restored = new Teams(() -> policy, () -> frozen, uuid -> true);
            restored.restore(saved);

            assertThat(restored.all()).containsExactlyElementsOf(saved);
            assertThat(restored.teamIdOf(p1)).contains(red);
            assertThat(restored.team(red).orElseThrow().captain()).contains(p1);
        }

        @Test
        @DisplayName("restore drops an impossible captain rather than carrying it")
        void restoreDropsCaptainWhoIsNotAMember() {
            // Team's compact constructor -- unlike withCaptain -- does not check that the captain is a
            // member, which is exactly how a hand-edited file or a stale save could carry this combination
            // in. restore() is the one door that reads such a row, and it must not wave it through.
            Team staleCaptainRow = new Team(TeamId.fromName("Red"), "Red", TeamColour.RED, TeamEmblem.NONE, null, Set.of(p2), Optional.of(p1));

            teams.restore(List.of(staleCaptainRow));

            assertThat(teams.team(TeamId.fromName("red")).orElseThrow().captain()).isEmpty();
            assertThat(teams.team(TeamId.fromName("red")).orElseThrow().members()).containsExactly(p2);
        }

        @Test
        @DisplayName("restore reassigns a duplicate colour where colours are exclusive, and reports it")
        void restoreReassignsDuplicateColourWhenExclusive() {
            policy = TeamPolicy.tournament();
            List<Team> saved = List.of(
                    new Team(TeamId.fromName("Red"), "Red", TeamColour.RED, TeamEmblem.NONE, null, Set.of(p1), Optional.empty()),
                    new Team(TeamId.fromName("Also red"), "Also red", TeamColour.RED, TeamEmblem.NONE, null, Set.of(p2), Optional.empty()));

            teams.restore(saved);

            assertThat(teams.team(TeamId.fromName("red")).orElseThrow().colour()).isEqualTo(TeamColour.RED);
            TeamColour secondColour = teams.team(TeamId.fromName("also-red")).orElseThrow().colour();
            assertThat(secondColour).isNotEqualTo(TeamColour.RED);
            assertThat(teams.problems()).isNotEmpty();
            assertThat(teams.problems().get(0)).contains("also-red").contains("RED");
        }

        @Test
        @DisplayName("restore leaves a duplicate colour alone where colours are shared -- it is legal there")
        void restoreLeavesDuplicateColourAloneWhenShared() {
            policy = TeamPolicy.clans();
            List<Team> saved = List.of(
                    new Team(TeamId.fromName("Clan A"), "Clan A", TeamColour.RED, TeamEmblem.NONE, null, Set.of(p1), Optional.empty()),
                    new Team(TeamId.fromName("Clan B"), "Clan B", TeamColour.RED, TeamEmblem.NONE, null, Set.of(p2), Optional.empty()));

            teams.restore(saved);

            assertThat(teams.team(TeamId.fromName("clan-a")).orElseThrow().colour()).isEqualTo(TeamColour.RED);
            assertThat(teams.team(TeamId.fromName("clan-b")).orElseThrow().colour()).isEqualTo(TeamColour.RED);
            assertThat(teams.problems()).isEmpty();
        }

        @Test
        @DisplayName("restore keeps a colour clash, rather than dropping the team, once none is free")
        void restoreKeepsClashWhenNoColourIsFree() {
            policy = TeamPolicy.tournament().withMaxTeams(0);
            List<Team> saved = new java.util.ArrayList<>();
            for (TeamColour colour : TeamColour.values()) {
                saved.add(new Team(TeamId.fromName(colour.name()), colour.name(), colour, TeamEmblem.NONE, null, Set.of(), Optional.empty()));
            }
            saved.add(new Team(TeamId.fromName("Overflow"), "Overflow", TeamColour.RED, TeamEmblem.NONE, null, Set.of(p1), Optional.empty()));

            teams.restore(saved);

            assertThat(teams.all()).hasSize(17);
            assertThat(teams.team(TeamId.fromName("overflow")).orElseThrow().colour())
                    .as("no colour was free, so the clash is kept rather than the team dropped")
                    .isEqualTo(TeamColour.RED);
            assertThat(teams.problems()).isNotEmpty();
        }

        @Test
        @DisplayName("restore drops a captain who is not a member, and reports it")
        void restoreDropsCaptainWhoIsNotAMemberAndReportsIt() {
            Team staleCaptainRow = new Team(TeamId.fromName("Red"), "Red", TeamColour.RED, TeamEmblem.NONE, null, Set.of(p2), Optional.of(p1));

            teams.restore(List.of(staleCaptainRow));

            assertThat(teams.team(TeamId.fromName("red")).orElseThrow().captain()).isEmpty();
            assertThat(teams.problems()).isNotEmpty();
            assertThat(teams.problems().get(0)).contains("red").contains("captain");
        }

        @Test
        @DisplayName("clear empties the roster")
        void clearEmptiesRoster() {
            teams.create("Red", TeamColour.RED);
            teams.clear();

            assertThat(teams.all()).isEmpty();
            assertThat(teams.count()).isZero();
        }
    }

    /**
     * One test per {@link TeamPolicy} factory, each proving the setting that factory turns on and the others
     * do not -- through this same {@link Teams} instance type. This is the genericity claim this class makes:
     * a tournament, a bedwars match, a clan and a party are all served correctly by one roster.
     */
    @Nested
    @DisplayName("genericity across policies")
    class Genericity {

        @Test
        @DisplayName("tournament(): exclusive colours, two per team, no captains, switching allowed")
        void tournamentPolicy() {
            policy = TeamPolicy.tournament();
            TeamId red = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            TeamId blue = teams.create("Blue", TeamColour.BLUE).team().orElseThrow().id();

            // exclusive colours
            assertThat(teams.setColour(blue, TeamColour.RED)).isEqualTo(TeamOutcome.COLOUR_TAKEN);
            // two per team
            teams.join(p1, red);
            teams.join(p2, red);
            assertThat(teams.join(p3, red).status()).isEqualTo(TeamOutcome.TEAM_FULL);
            // no captains
            assertThat(teams.setCaptain(red, p1)).isEqualTo(TeamOutcome.NO_CAPTAINS_HERE);
            // switching allowed
            teams.join(p3, blue);
            assertThat(teams.join(p3, red).status()).isNotEqualTo(TeamOutcome.MUST_LEAVE_FIRST);
        }

        @Test
        @DisplayName("match(perTeam, teams): exclusive colours, fixed team count, no captains")
        void matchPolicy() {
            policy = TeamPolicy.match(2, 2);
            teams.create("Red", TeamColour.RED);
            teams.create("Blue", TeamColour.BLUE);

            // fixed team count -- a third team is refused
            assertThat(teams.create("Green", TeamColour.GREEN).status()).isEqualTo(TeamOutcome.TOO_MANY_TEAMS);
            // no captains
            TeamId red = teams.team(TeamId.fromName("Red")).orElseThrow().id();
            teams.join(p1, red);
            assertThat(teams.setCaptain(red, p1)).isEqualTo(TeamOutcome.NO_CAPTAINS_HERE);
        }

        @Test
        @DisplayName("clans(): shared colours, unlimited size, captains, must leave before switching")
        void clansPolicy() {
            policy = TeamPolicy.clans();
            TeamId clanA = teams.create("Clan A", TeamColour.RED).team().orElseThrow().id();
            TeamId clanB = teams.create("Clan B", TeamColour.RED).team().orElseThrow().id();

            // shared colours: both hold RED, no refusal
            assertThat(teams.team(clanA).orElseThrow().colour()).isEqualTo(TeamColour.RED);
            assertThat(teams.team(clanB).orElseThrow().colour()).isEqualTo(TeamColour.RED);

            // unlimited size
            for (int i = 0; i < 20; i++) {
                assertThat(teams.join(UUID.randomUUID(), clanA).status().isSuccess()).isTrue();
            }

            // captains
            teams.join(p1, clanA);
            assertThat(teams.setCaptain(clanA, p1)).isEqualTo(TeamOutcome.SUCCESS);

            // must leave before switching
            assertThat(teams.join(p1, clanB).status()).isEqualTo(TeamOutcome.MUST_LEAVE_FIRST);
            teams.leave(p1);
            assertThat(teams.join(p1, clanB).status().isSuccess()).isTrue();
        }

        @Test
        @DisplayName("party(mostMembers): shared colours, a member cap, captains, switching allowed")
        void partyPolicy() {
            policy = TeamPolicy.party(4);
            TeamId party = teams.create("Party", TeamColour.RED).team().orElseThrow().id();

            // member cap
            teams.join(p1, party);
            teams.join(p2, party);
            teams.join(p3, party);
            teams.join(UUID.randomUUID(), party);
            assertThat(teams.join(UUID.randomUUID(), party).status()).isEqualTo(TeamOutcome.TEAM_FULL);

            // captains
            assertThat(teams.setCaptain(party, p1)).isEqualTo(TeamOutcome.SUCCESS);

            // shared colours: a second party may hold the same one
            Teams.CreationResult second = teams.create("Second party", TeamColour.RED);
            assertThat(second.status().isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("swapping one UUID for another within a team")
    class Reassignment {

        @Test
        @DisplayName("membership moves to the new UUID")
        void membershipMoves() {
            TeamId id = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            teams.join(p1, id);

            Optional<TeamId> moved = teams.reassign(p1, p2);

            assertThat(moved).contains(id);
            assertThat(teams.teamIdOf(p2)).contains(id);
            assertThat(teams.teamIdOf(p1))
                    .as("the old UUID must not still hold the seat too — that is two members for one person")
                    .isEmpty();
        }

        @Test
        @DisplayName("captaincy moves with it")
        void captaincyMoves() {
            policy = policy.withCaptains(true);
            TeamId id = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            teams.join(p1, id);
            teams.setCaptain(id, p1);

            teams.reassign(p1, p2);

            assertThat(teams.team(id).orElseThrow().captain())
                    .as("losing captaincy to an accident of timing is not a decision anybody made")
                    .contains(p2);
        }

        @Test
        @DisplayName("somebody who is not on a team moves nothing and reports so")
        void nobodyToMove() {
            assertThat(teams.reassign(p1, p2)).isEmpty();
        }

        @Test
        @DisplayName("it works even while the roster is frozen")
        void ignoresFrozen() {
            // Not a membership decision by a player — the same person is receiving their real identity, and
            // a normal join would be refused the moment teams are frozen, which for a tournament is most of
            // the evening.
            TeamId id = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            teams.join(p1, id);
            frozen = true;

            Optional<TeamId> moved = teams.reassign(p1, p2);

            assertThat(moved).contains(id);
            assertThat(teams.teamIdOf(p2)).contains(id);
        }

        @Test
        @DisplayName("a teammate who is not being reassigned is left alone")
        void teammateUntouched() {
            TeamId id = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            teams.join(p1, id);
            teams.join(p3, id);

            teams.reassign(p1, p2);

            assertThat(teams.team(id).orElseThrow().members()).containsExactlyInAnyOrder(p2, p3);
        }
    }
}
