package de.raindancer.core.world.protection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a flag actually resolves to, for whom.
 *
 * <p>Replaces the resolution half of {@code FlagAudienceTest}; the half that read flag policies out of a
 * {@code config.yml} stays with whatever owns that config, because Core is handed a
 * {@link LandPolicy} and does not care where it came from.
 *
 * <p>The rule with the sharp edge is {@code DISABLED}, which does <b>not</b> mean "denied". It means this
 * server does not enforce the flag at all, so vanilla behaviour applies — and vanilla behaviour is
 * "allowed". An admin switching a flag off expecting it to deny things would get the opposite of what they
 * wanted, which is exactly why it is spelled out in a test rather than left to be inferred.
 */
class FlagRulesTest {

    private final LandPolicies policies = LandPolicies.builtIn();
    private final FlagRules flags = new FlagRules(policies);

    private final UUID owner = UUID.randomUUID();

    private FakeArea area() {
        return FakeArea.named("somebody's ground").ownedBy(owner);
    }

    @Nested
    @DisplayName("with the server leaving it to the owner")
    class Available {

        @Test
        void anUntouchedFlagIsWhateverTheFlagItselfSays() {
            FakeArea area = area();
            for (LandFlag flag : LandFlag.values()) {
                assertThat(flags.isAllowed(area, flag, LandAudience.OWNER))
                        .as("%s with nothing set", flag)
                        .isEqualTo(flag.builtInDefault());
            }
        }

        @Test
        void theServerDefaultCanBeChangedWithoutTouchingAnyGround() {
            policies.flagDefault(LandFlag.PVP, true);
            assertThat(flags.isAllowed(area(), LandFlag.PVP, LandAudience.VISITOR)).isTrue();
        }

        @Test
        void anOwnerOverrideBeatsTheServerDefault() {
            FakeArea area = area();
            policies.flagDefault(LandFlag.PVP, true);
            area.with(LandFlag.PVP, false);

            assertThat(flags.isAllowed(area, LandFlag.PVP, LandAudience.VISITOR)).isFalse();
        }

        @Test
        void clearingAnOverrideFallsBackToTheServerDefaultAgain() {
            FakeArea area = area();
            area.with(LandFlag.PVP, true);
            area.clear(LandFlag.PVP);

            assertThat(flags.isAllowed(area, LandFlag.PVP, LandAudience.OWNER))
                    .isEqualTo(LandFlag.PVP.builtInDefault());
        }
    }

    @Nested
    @DisplayName("per audience")
    class Audiences {

        @Test
        void anAudienceAwareFlagMayDifferBetweenGroups() {
            FakeArea area = area();
            area.with(LandFlag.FALL_DAMAGE, LandAudience.OWNER, false);
            area.with(LandFlag.FALL_DAMAGE, LandAudience.VISITOR, true);

            assertThat(flags.isAllowed(area, LandFlag.FALL_DAMAGE, LandAudience.OWNER)).isFalse();
            assertThat(flags.isAllowed(area, LandFlag.FALL_DAMAGE, LandAudience.VISITOR)).isTrue();
        }

        @Test
        void anAreaWideFlagReadsTheSameForEveryTier() {
            // Fire does not spread for some onlookers and not others. The area records one value — at the
            // owner tier — and Core reads every tier there, so a provider cannot break the promise by
            // recording per-tier values for a flag that has no business having them.
            FakeArea area = area();
            area.with(LandFlag.FIRE_SPREAD, LandAudience.OWNER, true);

            for (LandAudience audience : LandAudience.values()) {
                assertThat(flags.isAllowed(area, LandFlag.FIRE_SPREAD, audience))
                        .as("fire spread for %s", audience)
                        .isTrue();
            }
        }

        @Test
        void aMissingAudienceReadsAsTheOwners() {
            FakeArea area = area();
            area.with(LandFlag.FALL_DAMAGE, LandAudience.OWNER, false);

            assertThat(flags.isAllowed(area, LandFlag.FALL_DAMAGE, null)).isFalse();
        }

        @Test
        void aSummaryReportsWhenTheGroupsDisagree() {
            FakeArea area = area();
            area.with(LandFlag.FALL_DAMAGE, LandAudience.OWNER, false);
            area.with(LandFlag.FALL_DAMAGE, LandAudience.VISITOR, true);

            assertThat(flags.summarise(area, LandFlag.FALL_DAMAGE)).isEqualTo(FlagRules.Summary.MIXED);
        }

        @Test
        void aSummaryIsPlainWhenTheyAgree() {
            FakeArea area = area();
            area.with(LandFlag.FALL_DAMAGE, true);
            assertThat(flags.summarise(area, LandFlag.FALL_DAMAGE)).isEqualTo(FlagRules.Summary.ALLOWED);

            area.with(LandFlag.FALL_DAMAGE, false);
            assertThat(flags.summarise(area, LandFlag.FALL_DAMAGE)).isEqualTo(FlagRules.Summary.DENIED);
        }
    }

    @Nested
    @DisplayName("with the server forcing it")
    class Forced {

        @Test
        void onBeatsAnOwnerWhoTurnedItOff() {
            FakeArea area = area();
            area.with(LandFlag.PVP, false);
            policies.policy(LandFlag.PVP, FlagPolicy.FORCED_ON);

            assertThat(flags.isAllowed(area, LandFlag.PVP, LandAudience.OWNER)).isTrue();
        }

        @Test
        void offBeatsAnOwnerWhoTurnedItOn() {
            FakeArea area = area();
            area.with(LandFlag.PVP, true);
            policies.policy(LandFlag.PVP, FlagPolicy.FORCED_OFF);

            assertThat(flags.isAllowed(area, LandFlag.PVP, LandAudience.OWNER)).isFalse();
        }

        @Test
        void takesTheFlagOutOfTheOwnersReach() {
            assertThat(flags.isEditableByOwner(LandFlag.PVP)).isTrue();
            policies.policy(LandFlag.PVP, FlagPolicy.FORCED_ON);
            assertThat(flags.isEditableByOwner(LandFlag.PVP)).isFalse();
        }

        @Test
        void aForcedFlagSummarisesFlatlyRatherThanAsMixed() {
            FakeArea area = area();
            area.with(LandFlag.FALL_DAMAGE, LandAudience.OWNER, false);
            area.with(LandFlag.FALL_DAMAGE, LandAudience.VISITOR, true);
            policies.policy(LandFlag.FALL_DAMAGE, FlagPolicy.FORCED_OFF);

            assertThat(flags.summarise(area, LandFlag.FALL_DAMAGE)).isEqualTo(FlagRules.Summary.DENIED);
        }
    }

    @Nested
    @DisplayName("with the server not enforcing it at all")
    class Disabled {

        @Test
        void meansAllowedRatherThanDenied() {
            // The sharp edge. DISABLED is "this server does not interfere", and not interfering with
            // fire spread means fire spreads.
            FakeArea area = area();
            area.with(LandFlag.FIRE_SPREAD, false);
            policies.policy(LandFlag.FIRE_SPREAD, FlagPolicy.DISABLED);

            assertThat(flags.isAllowed(area, LandFlag.FIRE_SPREAD, LandAudience.OWNER)).isTrue();
        }

        @Test
        void takesTheFlagOutOfTheListsSoNobodyClicksADeadSwitch() {
            policies.policy(LandFlag.FIRE_SPREAD, FlagPolicy.DISABLED);

            assertThat(flags.isEnforced(LandFlag.FIRE_SPREAD)).isFalse();
            assertThat(flags.editableFlags()).doesNotContain(LandFlag.FIRE_SPREAD);
        }

        @Test
        void everyOtherFlagIsStillListed() {
            policies.policy(LandFlag.FIRE_SPREAD, FlagPolicy.DISABLED);
            assertThat(flags.editableFlags()).hasSize(LandFlag.values().length - 1);
        }
    }

    @Nested
    @DisplayName("on unprotected ground")
    class Unprotected {

        @Test
        void readsTheServerDefault() {
            policies.flagDefault(LandFlag.PVP, true);
            assertThat(flags.isAllowed(null, LandFlag.PVP, LandAudience.VISITOR)).isTrue();
        }

        @Test
        void aForcedOffFlagIsStillOff() {
            policies.policy(LandFlag.PVP, FlagPolicy.FORCED_OFF);
            assertThat(flags.isAllowed(null, LandFlag.PVP, LandAudience.VISITOR)).isFalse();
        }
    }
}
