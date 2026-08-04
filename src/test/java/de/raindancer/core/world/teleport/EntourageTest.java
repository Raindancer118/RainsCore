package de.raindancer.core.world.teleport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What comes with somebody when they are sent somewhere.
 *
 * <h2>Why this is a value-in, value-out rule</h2>
 * Because the alternative is finding out on a live server, and the failures here are the sort nobody
 * reports as a bug: a dog left behind on the far side of the world, or a stranger's horse dragged
 * across it. Both look like the plugin working until somebody notices what is missing.
 *
 * <p>So {@link Entourage} judges {@link Entourage.Candidate} — a handful of plain facts about a
 * nearby entity — and {@link Travel} is what collects those facts from a running server. The whole
 * decision is here, where it can be asked a hundred ways in a second.
 */
class EntourageTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    /** A wild thing on a lead, held by whoever is named — a leashed villager, say. */
    private static Entourage.Candidate led(UUID heldBy) {
        return new Entourage.Candidate(UUID.randomUUID(), false, heldBy, null, 2, false, false);
    }

    /** Somebody's own tame animal, on that person's lead. */
    private static Entourage.Candidate ledPet(UUID heldBy, UUID owner) {
        return new Entourage.Candidate(UUID.randomUUID(), false, heldBy, owner, 2, true, false);
    }

    /** A tame animal belonging to somebody, this far away. */
    private static Entourage.Candidate pet(UUID owner, int blocksAway) {
        return new Entourage.Candidate(UUID.randomUUID(), false, null, owner, blocksAway, true,
                false);
    }

    /** A wild mob standing about. */
    private static Entourage.Candidate wild(int blocksAway) {
        return new Entourage.Candidate(UUID.randomUUID(), false, null, null, blocksAway, false,
                false);
    }

    /** Another player. */
    private static Entourage.Candidate player(int blocksAway) {
        return new Entourage.Candidate(UUID.randomUUID(), true, null, null, blocksAway, false,
                false);
    }

    /** A boat on the traveller's lead with somebody else sitting in it. */
    private static Entourage.Candidate ledWithAPassenger(UUID heldBy) {
        return new Entourage.Candidate(UUID.randomUUID(), false, heldBy, null, 2, false, true);
    }

    @Nested
    @DisplayName("bringing nothing")
    class Nobody {

        private final Entourage entourage = new Entourage(Companions.NOBODY);

        @Test
        @DisplayName("not even what they are leading")
        void nothingComes() {
            assertThat(entourage.comesAlong(led(ALICE), ALICE)).isFalse();
            assertThat(entourage.comesAlong(pet(ALICE, 1), ALICE)).isFalse();
        }

        @Test
        @DisplayName("and it says so, so a caller can skip looking at all")
        void nothingIsWorthLookingFor() {
            // The gather on a live server is a radius search per warp. A server that has this
            // switched off should not be paying for one.
            assertThat(entourage.isWorthLooking()).isFalse();
        }
    }

    @Nested
    @DisplayName("bringing what they lead")
    class WhatTheyLead {

        private final Entourage entourage = new Entourage(Companions.WHAT_YOU_LEAD);

        @Test
        @DisplayName("a dog on their own lead comes")
        void theirOwnLeadComes() {
            assertThat(entourage.comesAlong(led(ALICE), ALICE)).isTrue();
        }

        @Test
        @DisplayName("somebody else's lead does not")
        void anotherLeadStays() {
            // Two players standing together, each with animals on leads. Bringing the other one's is
            // taking their animals away from them.
            assertThat(entourage.comesAlong(led(BOB), ALICE)).isFalse();
        }

        @Test
        @DisplayName("their own tame animal standing beside them does not")
        void petsStayBehind() {
            // Under this policy only what is actually on a lead travels. A dog following you about
            // is not a dog you asked to bring, and the server may have a hundred of them at spawn.
            assertThat(entourage.comesAlong(pet(ALICE, 1), ALICE)).isFalse();
        }

        @Test
        @DisplayName("a wild mob standing next to them certainly does not")
        void wildMobsStayBehind() {
            assertThat(entourage.comesAlong(wild(1), ALICE)).isFalse();
        }

        @Test
        @DisplayName("distance is irrelevant to something on a lead")
        void aLeadHasNoRange() {
            // A lead has its own length and the server enforces it. Adding a second, shorter range
            // here would mean a boat trailing at full lead length is left behind.
            Entourage.Candidate faraway =
                    new Entourage.Candidate(UUID.randomUUID(), false, ALICE, null, 9, false, false);

            assertThat(entourage.comesAlong(faraway, ALICE)).isTrue();
        }
    }

    @Nested
    @DisplayName("bringing what they lead and their animals nearby")
    class AndNearby {

        private final Entourage entourage =
                new Entourage(Companions.WHAT_YOU_LEAD_AND_NEARBY_PETS.within(6));

        @Test
        @DisplayName("their own tame animal within range comes")
        void nearPetsCome() {
            assertThat(entourage.comesAlong(pet(ALICE, 5), ALICE)).isTrue();
        }

        @Test
        @DisplayName("exactly at the range still counts")
        void theEdgeIsIncluded() {
            assertThat(entourage.comesAlong(pet(ALICE, 6), ALICE)).isTrue();
        }

        @Test
        @DisplayName("one further away than that is left")
        void farPetsStay() {
            assertThat(entourage.comesAlong(pet(ALICE, 7), ALICE)).isFalse();
        }

        @Test
        @DisplayName("somebody else's tame animal is left, however close")
        void otherPeoplesPetsStay() {
            assertThat(entourage.comesAlong(pet(BOB, 1), ALICE)).isFalse();
        }

        @Test
        @DisplayName("a wild mob is still left, however close")
        void wildMobsStillStay() {
            // Otherwise a warp taken at a mob farm arrives with the mob farm.
            assertThat(entourage.comesAlong(wild(1), ALICE)).isFalse();
        }

        @Test
        @DisplayName("what they lead still comes, whatever the range says")
        void leadsStillCome() {
            assertThat(entourage.comesAlong(led(ALICE), ALICE)).isTrue();
        }
    }

    @Nested
    @DisplayName("other people")
    class People {

        @Test
        @DisplayName("another player never comes, whatever the policy")
        void playersAreNeverDragged() {
            // Not a preference. A player pulled somewhere they did not ask to go is a teleport
            // nobody consented to, and on a PvP server it is a weapon.
            for (Companions policy : List.of(Companions.NOBODY, Companions.WHAT_YOU_LEAD,
                    Companions.WHAT_YOU_LEAD_AND_NEARBY_PETS)) {
                assertThat(new Entourage(policy).comesAlong(player(1), ALICE))
                        .as("a player came along under %s", policy.kind())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("nor does a boat with somebody else sitting in it")
        void notEvenTheBoatTheyAreIn() {
            // The hole this closes. Paper teleports a vehicle with everything riding in it, so
            // bringing the towed boat brings the person in the boat — a teleport they never agreed
            // to, and on a PvP server a way to drop somebody down a hole.
            assertThat(new Entourage(Companions.WHAT_YOU_LEAD)
                    .comesAlong(ledWithAPassenger(ALICE), ALICE))
                    .isFalse();
        }

        @Test
        @DisplayName("even a player on their lead does not come")
        void notEvenOnALead() {
            // A player can be leashed on some servers by a plugin. It is still not consent.
            Entourage.Candidate leashedPlayer =
                    new Entourage.Candidate(UUID.randomUUID(), true, ALICE, null, 1, false, false);

            assertThat(new Entourage(Companions.WHAT_YOU_LEAD).comesAlong(leashedPlayer, ALICE))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("somebody else's animals")
    class NotYours {

        private final Entourage entourage = new Entourage(Companions.WHAT_YOU_LEAD);

        @Test
        @DisplayName("their own tame animal on their own lead comes")
        void yourOwnPetOnYourOwnLead() {
            assertThat(entourage.comesAlong(ledPet(ALICE, ALICE), ALICE)).isTrue();
        }

        @Test
        @DisplayName("somebody else's tame animal on YOUR lead does not")
        void aLeadIsNotOwnership() {
            // The theft this closes. Anybody may put a lead on anybody's tamed wolf or horse, so
            // "it is on my lead" cannot be the whole test — it would make a warp the fastest way to
            // take somebody's animals off them, from inside their own claim.
            assertThat(entourage.comesAlong(ledPet(ALICE, BOB), ALICE)).isFalse();
        }

        @Test
        @DisplayName("a wild thing on their lead still comes")
        void wildThingsOnALeadStillCome() {
            // Villagers in a boat, a llama just caught, a squid on a string. Nobody owns these, so
            // there is nobody to take them from — and this is most of what the feature is for.
            assertThat(entourage.comesAlong(led(ALICE), ALICE)).isTrue();
        }
    }

    @Nested
    @DisplayName("the policy itself")
    class Policy {

        @Test
        @DisplayName("a radius below one is one, so a policy cannot mean nothing by accident")
        void theRadiusIsClamped() {
            assertThat(Companions.WHAT_YOU_LEAD_AND_NEARBY_PETS.within(0).radius()).isEqualTo(1);
            assertThat(Companions.WHAT_YOU_LEAD_AND_NEARBY_PETS.within(-4).radius()).isEqualTo(1);
        }

        @Test
        @DisplayName("a large radius is capped, because it is a search on every warp")
        void theRadiusIsCapped() {
            assertThat(Companions.WHAT_YOU_LEAD_AND_NEARBY_PETS.within(1_000).radius())
                    .isLessThanOrEqualTo(32);
        }

        @Test
        @DisplayName("only the policy that looks around is worth searching for")
        void whatIsWorthLooking() {
            assertThat(new Entourage(Companions.NOBODY).isWorthLooking()).isFalse();
            assertThat(new Entourage(Companions.WHAT_YOU_LEAD).isWorthLooking()).isTrue();
            assertThat(new Entourage(Companions.WHAT_YOU_LEAD_AND_NEARBY_PETS).isWorthLooking())
                    .isTrue();
        }

        @Test
        @DisplayName("nothing at all is nobody, not an exception")
        void nullIsNobody() {
            Entourage none = new Entourage(null);

            assertThat(none.isWorthLooking()).isFalse();
            assertThat(none.comesAlong(led(ALICE), ALICE)).isFalse();
            assertThat(none.comesAlong(null, ALICE)).isFalse();
        }

        @Test
        @DisplayName("nobody travelling means nothing travels with them")
        void nullTravellerBringsNothing() {
            assertThat(new Entourage(Companions.WHAT_YOU_LEAD).comesAlong(led(ALICE), null))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("filtering a whole crowd at once")
    class Filtering {

        @Test
        @DisplayName("what comes is what should come, and it is bounded")
        void theCrowdIsFiltered() {
            Entourage entourage =
                    new Entourage(Companions.WHAT_YOU_LEAD_AND_NEARBY_PETS.within(6).atMost(3));
            List<Entourage.Candidate> around = List.of(
                    led(ALICE), pet(ALICE, 2), pet(ALICE, 3), pet(ALICE, 4),
                    pet(BOB, 1), wild(1), player(1));

            List<Entourage.Candidate> coming = entourage.from(around, ALICE);

            assertThat(coming)
                    .as("the ceiling exists because a hundred entities teleported at once is a "
                            + "stall on everybody's machine, and somebody will try it")
                    .hasSize(3);
        }

        @Test
        @DisplayName("what they lead is taken before their animals are")
        void leadsComeFirst() {
            // When the ceiling bites, the thing they deliberately put on a lead is the thing they
            // meant to bring. Losing that and keeping a stray cat is the wrong way round.
            Entourage entourage =
                    new Entourage(Companions.WHAT_YOU_LEAD_AND_NEARBY_PETS.within(6).atMost(1));
            Entourage.Candidate theDogOnALead = led(ALICE);
            List<Entourage.Candidate> around = List.of(pet(ALICE, 1), pet(ALICE, 2), theDogOnALead);

            assertThat(entourage.from(around, ALICE)).containsExactly(theDogOnALead);
        }

        @Test
        @DisplayName("an empty crowd is an empty answer, not a failure")
        void nothingAroundIsFine() {
            Entourage entourage = new Entourage(Companions.WHAT_YOU_LEAD);

            assertThat(entourage.from(List.of(), ALICE)).isEmpty();
            assertThat(entourage.from(null, ALICE)).isEmpty();
        }
    }
}
