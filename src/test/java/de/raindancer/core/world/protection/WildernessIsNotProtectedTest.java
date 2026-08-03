package de.raindancer.core.world.protection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That ground nobody has claimed behaves like ground nobody has claimed.
 *
 * <h2>The bug this fixes</h2>
 * Reported as "TNT explosions seem to be off in the entire world", and it was — along with PvP and fire spread,
 * everywhere on the server, inside claims and out.
 *
 * <p>With no area to ask, the resolver returned the flag's <b>default</b>. That value means "what a new claim
 * starts with": {@code EXPLOSIONS} is false so a new claim does not blow up, {@code PVP} is false so a new claim
 * is safe, {@code FIRE_SPREAD} is false so a new claim does not burn. Read as the answer for open wilderness, the
 * same three values switched all of it off for the whole world.
 *
 * <p>Outside a protected area there is nothing to protect and the plugin has no business interfering. That is the
 * same principle as {@link LandVerdict#UNKNOWN} — with no provider Core refuses to claim anything is unprotected
 * — applied one level down: with no <em>area</em>, Core refuses to claim anything is protected.
 *
 * <p>It also explains why a flag could look inverted. An owner reading "explosions: off by default" would expect
 * their claim to be safe and the wilderness to be normal; they got both places quiet, and nothing in any log.
 */
class WildernessIsNotProtectedTest {

    /** An area that would refuse everything, so anything allowed here came from somewhere else. */
    private static ProtectedArea strict(String id) {
        return new ProtectedArea() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String name() {
                return "Strict";
            }

            @Override
            public java.util.List<UUID> owners() {
                return java.util.List.of();
            }

            @Override
            public Optional<Boolean> flagOverride(LandFlag flag, LandAudience audience) {
                return Optional.of(false);
            }

            @Override
            public LandAudience audienceOf(UUID who) {
                return LandAudience.VISITOR;
            }

            @Override
            public boolean may(UUID who, LandAction action) {
                return false;
            }
        };
    }

    private static FlagRules rules() {
        return new FlagRules(LandPolicies.builtIn());
    }

    @Test
    @DisplayName("open ground allows what the world would allow")
    void nothingIsRefusedWithNoArea() {
        FlagRules rules = rules();

        // The three the report was about, and the three whose default is false. Read as a wilderness answer,
        // each one switched itself off for the entire server.
        for (LandFlag flag : new LandFlag[]{LandFlag.EXPLOSIONS, LandFlag.PVP, LandFlag.FIRE_SPREAD}) {
            assertThat(rules.isAllowed(null, flag, LandAudience.VISITOR, UUID.randomUUID()))
                    .as(flag + " must not be refused where there is nothing to protect")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("a flag that ships allowed is still allowed on open ground")
    void thedefaultDoesNotMatterEitherWay() {
        assertThat(rules().isAllowed(null, LandFlag.REDSTONE, LandAudience.VISITOR, null))
                .as("the built-in default is about what a new claim starts with, and open ground is not a claim")
                .isTrue();
    }

    @Test
    @DisplayName("inside an area the area still decides")
    void anAreaIsStillObeyed() {
        assertThat(rules().isAllowed(strict("strict"), LandFlag.EXPLOSIONS, LandAudience.VISITOR, null))
                .as("the fix must not turn every flag into a suggestion — this is the whole feature")
                .isFalse();
    }

    @Test
    @DisplayName("a server that forces a flag off means it everywhere, claim or not")
    void aforcedFlagStillWins() {
        LandPolicies policies = LandPolicies.builtIn();
        policies.policy(LandFlag.EXPLOSIONS, FlagPolicy.FORCED_OFF);

        assertThat(new FlagRules(policies)
                .isAllowed(null, LandFlag.EXPLOSIONS, LandAudience.VISITOR, null))
                .as("an admin who says 'never, anywhere' is the one case where open ground is refused — that "
                        + "is a decision somebody made rather than a default being misread")
                .isFalse();
    }

    @Test
    @DisplayName("a disabled flag is allowed everywhere, as it always was")
    void adisabledFlagIsUntouched() {
        LandPolicies policies = LandPolicies.builtIn();
        policies.policy(LandFlag.EXPLOSIONS, FlagPolicy.DISABLED);

        assertThat(new FlagRules(policies)
                .isAllowed(null, LandFlag.EXPLOSIONS, LandAudience.VISITOR, null))
                .isTrue();
    }
}
