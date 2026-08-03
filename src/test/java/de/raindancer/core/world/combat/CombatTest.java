package de.raindancer.core.world.combat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether one thing may hurt another.
 *
 * <h2>Why the rules are worth this much testing</h2>
 * Because every way of getting them wrong is a way of ruining a server, and the two failures are
 * opposites. Too permissive and PvP is on where somebody promised it was off — which is not a bug
 * report, it is an argument between players. Too strict and people cannot mine with TNT, cannot kill
 * the cow they are farming, or become immortal to their own arrows.
 *
 * <p>Almost all of it is about <em>who</em> the attacker is, and the server never says: it names the
 * arrow, the wolf, the potion. That untangling is {@code CombatListener}'s job; this is about what to
 * do once it is done.
 */
@DisplayName("who may hurt whom")
class CombatTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());
    private static final UUID ZOMBIE = UUID.nameUUIDFromBytes("zombie".getBytes());
    private static final UUID COW = UUID.nameUUIDFromBytes("cow".getBytes());

    private static final String OVERWORLD = "world";
    private static final String ARENA = "arena";

    private Combat combat;

    @BeforeEach
    void setUp() {
        combat = new Combat();
    }

    @Nested
    @DisplayName("with nothing switched off")
    class ByDefault {

        @Test
        @DisplayName("everything is allowed, because a library that changes the game on arrival is a bug")
        void allowsEverything() {
            assertThat(combat.judge(Attack.between(ALICE, BOB, OVERWORLD)).allowed()).isTrue();
            assertThat(combat.judge(Attack.onMob(ALICE, ZOMBIE, OVERWORLD)).allowed()).isTrue();
            assertThat(combat.judge(Attack.byMob(ZOMBIE, ALICE, OVERWORLD)).allowed()).isTrue();
            assertThat(combat.isPvpAllowed(OVERWORLD)).isTrue();
            assertThat(combat.isPveAllowed(OVERWORLD)).isTrue();
        }
    }

    @Nested
    @DisplayName("turning PvP off")
    class NoPvp {

        @BeforeEach
        void off() {
            combat.pvp(false);
        }

        @Test
        @DisplayName("one player may not hurt another")
        void refusesPlayerVersusPlayer() {
            Verdict verdict = combat.judge(Attack.between(ALICE, BOB, OVERWORLD));

            assertThat(verdict.allowed()).isFalse();
            assertThat(verdict).isEqualTo(Verdict.NO_PVP);
            assertThat(verdict.reasonKey())
                    .as("the refusal has to be able to say why, in the server's own words")
                    .isEqualTo("combat.no-pvp");
        }

        @Test
        @DisplayName("but may still hurt themselves")
        void allowsSelfHarm() {
            assertThat(combat.judge(Attack.between(ALICE, ALICE, OVERWORLD)).allowed())
                    .as("their own arrow coming down on their head, or their own TNT. Refusing it "
                            + "makes somebody immortal to their own explosives, which is how people "
                            + "mine")
                    .isTrue();
        }

        @Test
        @DisplayName("and mobs are not affected in either direction")
        void leavesMobsAlone() {
            assertThat(combat.judge(Attack.onMob(ALICE, ZOMBIE, OVERWORLD)).allowed()).isTrue();
            assertThat(combat.judge(Attack.byMob(ZOMBIE, ALICE, OVERWORLD)).allowed()).isTrue();
        }

        @Test
        @DisplayName("and the environment still works")
        void leavesTheWorldAlone() {
            assertThat(combat.judge(
                    Attack.fromNothing(Attack.Fighter.PLAYER, ALICE, OVERWORLD)).allowed())
                    .as("a server that stops falling damage has turned off the game, not a rule")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("turning PvE off")
    class NoPve {

        @Test
        @DisplayName("a player may not hurt a mob, and a mob may not hurt a player")
        void refusesBothDirections() {
            combat.pve(false);

            assertThat(combat.judge(Attack.onMob(ALICE, COW, OVERWORLD))).isEqualTo(Verdict.NO_PVE);
            assertThat(combat.judge(Attack.byMob(ZOMBIE, ALICE, OVERWORLD)))
                    .as("stopping players killing mobs while mobs still kill players is a worse "
                            + "game, not a gentler one")
                    .isEqualTo(Verdict.NO_PVE);
        }

        @Test
        @DisplayName("the two directions can be switched separately")
        void directionsAreSeparate() {
            combat.playersMayHurtMobs(false);

            assertThat(combat.judge(Attack.onMob(ALICE, COW, OVERWORLD)))
                    .isEqualTo(Verdict.NO_PVE);
            assertThat(combat.judge(Attack.byMob(ZOMBIE, ALICE, OVERWORLD)).allowed())
                    .as("a peaceful-building server still wants zombies to be dangerous")
                    .isTrue();

            combat.playersMayHurtMobs(true);
            combat.mobsMayHurtPlayers(false);

            assertThat(combat.judge(Attack.onMob(ALICE, COW, OVERWORLD)).allowed()).isTrue();
            assertThat(combat.judge(Attack.byMob(ZOMBIE, ALICE, OVERWORLD)))
                    .isEqualTo(Verdict.NO_PVE);
        }

        @Test
        @DisplayName("two mobs fighting is not anybody's rule to make")
        void leavesMobsToEachOther() {
            combat.pve(false);

            assertThat(combat.judge(Attack.of(Attack.Fighter.MOB, ZOMBIE,
                    Attack.Fighter.MOB, COW, OVERWORLD)).allowed())
                    .as("a zombie killing a cow is the game playing itself; refusing it breaks "
                            + "farms and iron golems and nothing anybody asked about")
                    .isTrue();
        }

        @Test
        @DisplayName("and PvP is untouched")
        void leavesPlayersAlone() {
            combat.pve(false);

            assertThat(combat.judge(Attack.between(ALICE, BOB, OVERWORLD)).allowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("one world at a time")
    class PerWorld {

        @Test
        @DisplayName("a world can differ from the rest of the server")
        void oneWorldDiffers() {
            combat.pvp(false);
            combat.pvp(ARENA, true);

            assertThat(combat.judge(Attack.between(ALICE, BOB, OVERWORLD)).allowed()).isFalse();
            assertThat(combat.judge(Attack.between(ALICE, BOB, ARENA)).allowed())
                    .as("an arena is the reason anybody asks for this")
                    .isTrue();
        }

        @Test
        @DisplayName("and the other way round")
        void oneWorldIsStricter() {
            combat.pvp(true);
            combat.pvp("spawn", false);

            assertThat(combat.judge(Attack.between(ALICE, BOB, OVERWORLD)).allowed()).isTrue();
            assertThat(combat.judge(Attack.between(ALICE, BOB, "spawn")).allowed()).isFalse();
        }

        @Test
        @DisplayName("a world's rule can be taken away again, and it goes back to the server's")
        void oneWorldCanGoBack() {
            combat.pvp(false);
            combat.pvp(ARENA, true);

            combat.clearWorld(ARENA);

            assertThat(combat.judge(Attack.between(ALICE, BOB, ARENA)).allowed()).isFalse();
        }

        @Test
        @DisplayName("world names are matched however they are typed")
        void worldNamesAreCaseInsensitive() {
            combat.pvp(false);
            combat.pvp("Arena", true);

            assertThat(combat.judge(Attack.between(ALICE, BOB, "arena")).allowed())
                    .as("a name typed into a config file will not match the server's capitalisation, "
                            + "and a rule that silently does not apply is worse than one that is "
                            + "refused")
                    .isTrue();
        }

        @Test
        @DisplayName("PvE can be set per world too")
        void pvePerWorld() {
            combat.pve(false);
            combat.pve("nether", true);

            assertThat(combat.judge(Attack.onMob(ALICE, ZOMBIE, OVERWORLD)).allowed()).isFalse();
            assertThat(combat.judge(Attack.onMob(ALICE, ZOMBIE, "nether")).allowed()).isTrue();
        }
    }

    /**
     * The escape hatch. Without one, a claims plugin cannot have a duelling arena inside a world where
     * PvP is off, and will end up cancelling the cancellation from a listener at a different priority
     * — which is how two plugins fight over one event for ever.
     */
    @Nested
    @DisplayName("something else may have the final say")
    class Exemptions {

        @Test
        @DisplayName("an exemption allows an attack the world's rule refuses")
        void anExemptionAllows() {
            combat.pvp(false);
            combat.alsoAsk(attack -> attack.world().equals("duel") ? Verdict.ALLOWED : null);

            assertThat(combat.judge(Attack.between(ALICE, BOB, "duel")).allowed()).isTrue();
            assertThat(combat.judge(Attack.between(ALICE, BOB, OVERWORLD)).allowed()).isFalse();
        }

        @Test
        @DisplayName("and can refuse one the rules would allow")
        void anExemptionRefuses() {
            combat.alsoAsk(attack -> ALICE.equals(attack.victimId()) ? Verdict.PROTECTED : null);

            assertThat(combat.judge(Attack.between(BOB, ALICE, OVERWORLD)))
                    .isEqualTo(Verdict.PROTECTED);
            assertThat(combat.judge(Attack.between(ALICE, BOB, OVERWORLD)).allowed()).isTrue();
        }

        @Test
        @DisplayName("answering nothing means it has no opinion")
        void noOpinionChangesNothing() {
            combat.pvp(false);
            combat.alsoAsk(attack -> null);

            assertThat(combat.judge(Attack.between(ALICE, BOB, OVERWORLD)))
                    .isEqualTo(Verdict.NO_PVP);
        }

        @Test
        @DisplayName("the first one to have an opinion wins, and the rest are not asked")
        void theFirstOpinionWins() {
            java.util.List<String> asked = new java.util.ArrayList<>();
            combat.alsoAsk(attack -> {
                asked.add("first");
                return Verdict.ALLOWED;
            });
            combat.alsoAsk(attack -> {
                asked.add("second");
                return Verdict.PROTECTED;
            });
            combat.pvp(false);

            assertThat(combat.judge(Attack.between(ALICE, BOB, OVERWORLD)).allowed()).isTrue();
            assertThat(asked)
                    .as("order has to be defined, or two plugins disagreeing gives a different "
                            + "answer on every server start")
                    .containsExactly("first");
        }

        @Test
        @DisplayName("one that throws is ignored rather than taken as a refusal")
        void oneThatThrowsIsIgnored() {
            combat.alsoAsk(attack -> {
                throw new IllegalStateException("a plugin's bug");
            });

            assertThat(combat.judge(Attack.between(ALICE, BOB, OVERWORLD)).allowed())
                    .as("a broken exemption must not silently switch PvP off for the whole server")
                    .isTrue();
        }

        @Test
        @DisplayName("self-harm is never handed to an exemption at all")
        void selfHarmIsNotAsked() {
            java.util.concurrent.atomic.AtomicBoolean asked =
                    new java.util.concurrent.atomic.AtomicBoolean();
            combat.alsoAsk(attack -> {
                asked.set(true);
                return Verdict.PROTECTED;
            });

            assertThat(combat.judge(Attack.between(ALICE, ALICE, OVERWORLD)).allowed()).isTrue();
            assertThat(asked)
                    .as("nobody's exemption should be able to stop somebody hurting themselves; it "
                            + "is not a rule about other people")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("misuse")
    class Misuse {

        @Test
        @DisplayName("nothing at all is allowed rather than an exception")
        void nulls() {
            combat.pvp(false);

            assertThat(combat.judge(null).allowed())
                    .as("a listener that cannot work out what happened must not turn that into a "
                            + "refusal nobody can explain")
                    .isTrue();
            assertThat(combat.isPvpAllowed(null)).isFalse();
            combat.pvp(null, true);
            combat.clearWorld(null);
            combat.alsoAsk(null);
        }

        @Test
        @DisplayName("a world nobody named follows the server's rule")
        void noWorldNamed() {
            combat.pvp(false);

            assertThat(combat.judge(Attack.between(ALICE, BOB, null)).allowed()).isFalse();
        }
    }
    /**
     * The half a claims plugin needs, and the half that is easy to get half-right.
     *
     * <p>A claim protects what is <em>inside</em> it. A rule that only looks at where the damage landed
     * lets somebody stand outside a claim and shoot in; a rule that only looks at where the shot came
     * from lets somebody inside a claim shoot out. Both positions are carried, so a claims plugin can
     * ask its own question rather than reconstructing one from an event it never saw.
     */
    @Nested
    @DisplayName("where it happened")
    class Positions {

        private static final Attack.At INSIDE = new Attack.At(10.5, 64.0, 10.5);
        private static final Attack.At OUTSIDE = new Attack.At(200.5, 64.0, 200.5);

        @Test
        @DisplayName("both spots are carried, so a claim can ask about either")
        void carriesBothSpots() {
            Attack attack = Attack.between(ALICE, BOB, OVERWORLD).at(INSIDE, OUTSIDE);

            assertThat(attack.victimSpot()).contains(INSIDE);
            assertThat(attack.attackerSpot()).contains(OUTSIDE);
        }

        @Test
        @DisplayName("an attack with no positions is still a valid attack")
        void spotsAreOptional() {
            Attack attack = Attack.between(ALICE, BOB, OVERWORLD);

            assertThat(attack.victimSpot()).isEmpty();
            assertThat(attack.attackerSpot()).isEmpty();
            assertThat(combat.judge(attack).allowed())
                    .as("a listener that could not work out where must not turn that into a refusal")
                    .isTrue();
        }

        @Test
        @DisplayName("a point knows which block it is in, which is what a claim is measured in")
        void blockCoordinates() {
            assertThat(new Attack.At(10.9, 64.2, -0.5).blockX()).isEqualTo(10);
            assertThat(new Attack.At(10.9, 64.2, -0.5).blockY()).isEqualTo(64);
            assertThat(new Attack.At(10.9, 64.2, -0.5).blockZ())
                    .as("floor, not truncation: -0.5 is in block -1, and getting this wrong puts "
                            + "the boundary of every claim one block out on two of its four sides")
                    .isEqualTo(-1);
        }

        @Test
        @DisplayName("a shot from outside a claim is told apart from a punch inside it")
        void closeQuarters() {
            assertThat(Attack.between(ALICE, BOB, OVERWORLD).at(INSIDE, OUTSIDE).isCloseQuarters())
                    .isFalse();
            assertThat(Attack.between(ALICE, BOB, OVERWORLD)
                    .at(INSIDE, new Attack.At(11.2, 64.0, 10.5)).isCloseQuarters())
                    .isTrue();
            assertThat(Attack.between(ALICE, BOB, OVERWORLD).at(INSIDE, null).isCloseQuarters())
                    .as("not knowing is not the same as being close")
                    .isFalse();
        }

        @Test
        @DisplayName("height is ignored, so a claim is not escaped by standing on a tower")
        void distanceIgnoresHeight() {
            Attack.At high = new Attack.At(10.5, 200.0, 10.5);

            assertThat(INSIDE.flatDistanceTo(high))
                    .as("a claim is a column; somebody two hundred blocks up is still above it")
                    .isZero();
        }

        @Test
        @DisplayName("an exemption gets the positions, which is the whole point of carrying them")
        void anExemptionCanUseThem() {
            combat.pvp(false);
            // What a claims plugin actually does: allow it when neither side is inside the claim.
            combat.alsoAsk(attack -> attack.victimSpot()
                    .filter(spot -> spot.blockX() < 100 && spot.blockZ() < 100)
                    .isPresent() ? null : Verdict.ALLOWED);

            assertThat(combat.judge(Attack.between(ALICE, BOB, OVERWORLD).at(INSIDE, OUTSIDE)))
                    .as("the victim is inside the claim, so the claim has no opinion and PvP being "
                            + "off still applies")
                    .isEqualTo(Verdict.NO_PVP);
            assertThat(combat.judge(
                    Attack.between(ALICE, BOB, OVERWORLD).at(OUTSIDE, OUTSIDE)).allowed())
                    .as("both out in the wild, where this claim does not reach")
                    .isTrue();
        }
    }

    /**
     * The cases a second review found, each of which was allowed or refused the wrong way round.
     */
    @Nested
    @DisplayName("attacks that are not what they look like")
    class NotWhatTheyLook {

        @Test
        @DisplayName("a mob's arrow is a mob attacking, not the weather")
        void aMobsProjectileIsStillAMob() {
            combat.pve(false);

            assertThat(combat.judge(Attack.byMob(ZOMBIE, ALICE, OVERWORLD)))
                    .as("this is the one that mattered: an arrow is not alive, so working the kind "
                            + "out from the projectile made a skeleton's shot NOBODY — and nobody's "
                            + "doing is always allowed. Every archer on the server walked past PvE "
                            + "being off")
                    .isEqualTo(Verdict.NO_PVE);
        }

        @Test
        @DisplayName("somebody's pet is that person, so killing it is PvP")
        void aPetIsItsOwner() {
            combat.pvp(false);

            assertThat(combat.judge(Attack.between(ALICE, BOB, OVERWORLD)))
                    .isEqualTo(Verdict.NO_PVP);
        }

        @Test
        @DisplayName("but a pet fighting a mob is the animal doing it, not its owner hunting")
        void aPetDefendingIsNotHunting() {
            combat.playersMayHurtMobs(false);

            assertThat(combat.judge(Attack.onMob(ALICE, ZOMBIE, OVERWORLD)))
                    .as("Alice swinging at a zombie herself")
                    .isEqualTo(Verdict.NO_PVE);
            assertThat(combat.judge(Attack.onMob(ALICE, ZOMBIE, OVERWORLD)
                    .throughAPet()).allowed())
                    .as("Alice's wolf defending her against the same zombie. Traced to her, which is "
                            + "right for PvP, and wrong here: on a server where people are not meant "
                            + "to hunt, a pet still gets to defend its owner")
                    .isTrue();
        }

        @Test
        @DisplayName("a pet is still its owner when it attacks a player, pet or not")
        void aPetAttackingAPlayerIsStillPvp() {
            combat.pvp(false);

            assertThat(combat.judge(Attack.between(ALICE, BOB, OVERWORLD).throughAPet()))
                    .as("bring a dog is the oldest way round a PvP rule there is; the exception is "
                            + "for fighting creatures, not for fighting people")
                    .isEqualTo(Verdict.NO_PVP);
        }

        @Test
        @DisplayName("an extra rule is asked about two mobs, so a claim can protect livestock")
        void mobVersusMobReachesAnExtraRule() {
            combat.alsoAsk(attack -> COW.equals(attack.victimId()) ? Verdict.PROTECTED : null);

            assertThat(combat.judge(Attack.of(Attack.Fighter.MOB, ZOMBIE,
                    Attack.Fighter.MOB, COW, OVERWORLD)))
                    .as("answering ALLOWED for two mobs before asking meant a claim never heard "
                            + "about the zombie killing the cows inside it")
                    .isEqualTo(Verdict.PROTECTED);
        }

        @Test
        @DisplayName("and two mobs are still allowed when nobody objects")
        void mobVersusMobIsOtherwiseFine() {
            combat.pve(false);

            assertThat(combat.judge(Attack.of(Attack.Fighter.MOB, ZOMBIE,
                    Attack.Fighter.MOB, COW, OVERWORLD)).allowed()).isTrue();
        }
    }

}
