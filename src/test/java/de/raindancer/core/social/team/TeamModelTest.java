package de.raindancer.core.social.team;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.DyeColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * That the team model keeps the promises the rest of the server relies on.
 *
 * <p>Most of what is checked here is a compromise rather than a calculation, and a compromise is exactly what
 * somebody tidies up six months later without knowing why it was made. Brown teams wearing dark red names
 * looks like an oversight until you go looking for the brown that does not exist in Adventure's palette.
 */
class TeamModelTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Nested
    @DisplayName("the colours")
    class Colours {

        @Test
        @DisplayName("there are sixteen, one per dye, and no dye is used twice")
        void oneColourPerDye() {
            // Sixteen distinguishable colours is the whole offer. Two teams sharing a dye would be two teams
            // in identical armour, which defeats the only thing a team colour is for.
            assertThat(TeamColour.values()).hasSize(16);

            Set<DyeColor> dyes = EnumSet.noneOf(DyeColor.class);
            for (TeamColour colour : TeamColour.values()) {
                assertThat(dyes.add(colour.dyeColour()))
                        .as("%s reuses the dye %s", colour, colour.dyeColour())
                        .isTrue();
            }
            assertThat(dyes).hasSize(16);
        }

        @Test
        @DisplayName("every colour answers on all four surfaces")
        void nothingIsMissingAMapping() {
            // A null on any one of these is a crash at the moment a team is drawn, which is the moment forty
            // people are looking at it.
            for (TeamColour colour : TeamColour.values()) {
                assertThat(colour.dyeColour()).as("%s has no dye", colour).isNotNull();
                assertThat(colour.armourColour()).as("%s has no armour colour", colour).isNotNull();
                assertThat(colour.textColour()).as("%s has no text colour", colour).isNotNull();
                assertThat(colour.namedTextColour()).as("%s has no scoreboard colour", colour).isNotNull();
            }
        }

        @Test
        @DisplayName("the two text colours are the same colour, so a scoreboard cannot disagree with chat")
        void theNarrowerTypeIsTheSameValue() {
            for (TeamColour colour : TeamColour.values()) {
                assertThat(colour.textColour())
                        .as("%s reads differently in chat and on the scoreboard", colour)
                        .isEqualTo(colour.namedTextColour());
            }
        }

        @Test
        @DisplayName("the palette compromises are deliberate and stay put")
        void theCompromisesArePinned() {
            // Minecraft has two pinks in dye and one in text, and no brown in text at all. Every one of these
            // is a decision somebody would otherwise 'fix' into a colour that does not exist.
            assertThat(TeamColour.MAGENTA.namedTextColour()).isEqualTo(NamedTextColor.LIGHT_PURPLE);
            assertThat(TeamColour.PINK.namedTextColour()).isEqualTo(NamedTextColor.LIGHT_PURPLE);
            assertThat(TeamColour.BROWN.namedTextColour()).isEqualTo(NamedTextColor.DARK_RED);
            assertThat(TeamColour.ORANGE.namedTextColour()).isEqualTo(NamedTextColor.GOLD);
            assertThat(TeamColour.GRAY.namedTextColour()).isEqualTo(NamedTextColor.DARK_GRAY);
            assertThat(TeamColour.LIGHT_GRAY.namedTextColour()).isEqualTo(NamedTextColor.GRAY);
        }

        @Test
        @DisplayName("a name is read however somebody typed it")
        void namesAreForgiving() {
            assertThat(TeamColour.named("light_blue")).contains(TeamColour.LIGHT_BLUE);
            assertThat(TeamColour.named("LIGHT BLUE")).contains(TeamColour.LIGHT_BLUE);
            assertThat(TeamColour.named("light-blue")).contains(TeamColour.LIGHT_BLUE);
            assertThat(TeamColour.named("  Red  ")).contains(TeamColour.RED);
        }

        @Test
        @DisplayName("an unreadable colour is empty rather than a default")
        void aTypoIsNotAChoice() {
            // Returning white here would make a typo in a config file look like somebody had chosen white,
            // and the team would be the wrong colour with nothing anywhere saying why.
            assertThat(TeamColour.named("burgundy")).isEmpty();
            assertThat(TeamColour.named("")).isEmpty();
            assertThat(TeamColour.named(null)).isEmpty();
        }

        @Test
        @DisplayName("a colour describes itself the way a person would read it")
        void describeReadsAsWords() {
            assertThat(TeamColour.LIGHT_BLUE.describe()).isEqualTo("Light blue");
            assertThat(TeamColour.RED.describe()).isEqualTo("Red");
        }

        @Test
        @DisplayName("every colour can be read back from its own name")
        void everyColourRoundTrips() {
            for (TeamColour colour : TeamColour.values()) {
                assertThat(TeamColour.named(colour.name())).contains(colour);
                assertThat(TeamColour.named(colour.describe())).contains(colour);
            }
        }
    }

    @Nested
    @DisplayName("the id")
    class Ids {

        @Test
        @DisplayName("an id is lower-cased, so one team cannot become two on disk")
        void idsAreNormalised() {
            assertThat(new TeamId("Rote-Raben").value()).isEqualTo("rote-raben");
            assertThat(new TeamId("ROTE-RABEN")).isEqualTo(new TeamId("rote-raben"));
        }

        @Test
        @DisplayName("runs of whitespace in a name collapse to one dash")
        void whitespaceCollapses() {
            // Otherwise "Rote  Raben" and "Rote Raben" are two teams that read identically everywhere.
            assertThat(TeamId.fromName("Rote  Raben")).isEqualTo(TeamId.fromName("Rote Raben"));
            assertThat(TeamId.fromName("  Rote Raben  ").value()).isEqualTo("rote-raben");
        }

        @Test
        @DisplayName("a blank id is refused")
        void blankIsRefused() {
            assertThatThrownBy(() -> new TeamId("   "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new TeamId(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("the team")
    class Teams {

        private Team redPair() {
            return new Team(TeamId.fromName("Red"), "Red", TeamColour.RED, TeamEmblem.NONE, null, Set.of(alice, bob), Optional.of(alice));
        }

        @Test
        @DisplayName("a new team is empty and unled")
        void ofMakesAnEmptyTeam() {
            Team team = Team.of(TeamId.fromName("Red"), "Red", TeamColour.RED);

            assertThat(team.isEmpty()).isTrue();
            assertThat(team.size()).isZero();
            assertThat(team.captain()).isEmpty();
        }

        @Test
        @DisplayName("the member set is copied, so nobody joins a team behind its back")
        void membersAreCopied() {
            Set<UUID> mutable = new LinkedHashSet<>();
            mutable.add(alice);
            Team team = new Team(TeamId.fromName("Red"), "Red", TeamColour.RED, TeamEmblem.NONE, null,
                    mutable, Optional.empty());

            mutable.add(bob);

            assertThat(team.members())
                    .as("a change to the caller's set reached inside the team")
                    .containsExactly(alice);
        }

        @Test
        @DisplayName("membership and captaincy answer for the right person")
        void membershipQuestions() {
            Team team = redPair();

            assertThat(team.isMember(alice)).isTrue();
            assertThat(team.isMember(UUID.randomUUID())).isFalse();
            assertThat(team.isCaptain(alice)).isTrue();
            assertThat(team.isCaptain(bob)).isFalse();
            assertThat(team.isCaptain(null)).isFalse();
        }

        @Test
        @DisplayName("a team cannot be led by somebody who is not in it")
        void aCaptainMustBeAMember() {
            // Allowed, this is a state every caller would have to check for and one would forget — and the
            // screens that draw a captain would show somebody who is on another team.
            Team team = redPair();
            UUID stranger = UUID.randomUUID();

            assertThatThrownBy(() -> team.withCaptain(Optional.of(stranger)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(stranger.toString());
        }

        @Test
        @DisplayName("a team can be left unled")
        void theCaptainCanBeCleared() {
            assertThat(redPair().withCaptain(Optional.empty()).captain()).isEmpty();
        }

        @Test
        @DisplayName("the identity survives every change to the team")
        void withersKeepTheId() {
            // The whole reason an id exists: renaming and recolouring are things that happen to a live team
            // with members already in it, and neither may make it a different team.
            Team team = redPair();

            assertThat(team.withName("Crimson").id()).isEqualTo(team.id());
            assertThat(team.withColour(TeamColour.BLUE).id()).isEqualTo(team.id());
            assertThat(team.withMembers(Set.of(alice)).id()).isEqualTo(team.id());

            assertThat(team.withName("Crimson").name()).isEqualTo("Crimson");
            assertThat(team.withColour(TeamColour.BLUE).colour()).isEqualTo(TeamColour.BLUE);
        }

        @Test
        @DisplayName("changing the members does not silently drop the captain")
        void replacingMembersKeepsTheCaptainField() {
            // Documenting a sharp edge rather than hiding it: withMembers does not re-check the captain, so a
            // caller removing the captain has to say what happens to the captaincy. The registry that owns the
            // roster is the only thing that knows whether that means promoting somebody or leaving it unled.
            Team team = redPair().withMembers(Set.of(bob));

            assertThat(team.captain())
                    .as("withMembers is not the place to invent a new captain")
                    .contains(alice);
            assertThat(team.isMember(alice)).isFalse();
        }

        @Test
        @DisplayName("two teams with the same values are the same team")
        void valueEquality() {
            assertThat(redPair()).isEqualTo(redPair());
            assertThat(redPair()).hasSameHashCodeAs(redPair());
            // Through a List, not Set.of(a, b) — that factory throws IllegalArgumentException on a duplicate,
            // so the first version of this assertion "passed" its point by crashing on it. Deduplicating in a
            // HashSet is what actually exercises equals and hashCode together, which is what matters: a Team
            // is a map key in every roster.
            assertThat(new HashSet<>(List.of(redPair(), redPair()))).hasSize(1);
        }

        @Test
        @DisplayName("nothing essential may be null")
        void nullsAreRefused() {
            TeamId id = TeamId.fromName("Red");
            assertThatThrownBy(() -> new Team(null, "Red", TeamColour.RED, TeamEmblem.NONE, null, Set.of(), Optional.empty()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new Team(id, null, TeamColour.RED, TeamEmblem.NONE, null, Set.of(), Optional.empty()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new Team(id, "Red", null, TeamEmblem.NONE, null, Set.of(), Optional.empty()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new Team(id, "Red", TeamColour.RED, TeamEmblem.NONE, null,
                    Set.of(), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
