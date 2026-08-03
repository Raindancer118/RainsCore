package de.raindancer.core.world.protection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server's decisions about claims, and what a config file has to write down.
 *
 * <p>The property worth holding is that <b>setting something back to its default forgets it</b>. Without
 * that, an admin who toggles a flag twice leaves a line in the file for every flag they ever looked at, and
 * a file spelling out all twenty-six flags at their default values is one nobody can read for the two lines
 * that matter. It also means a later version changing a default actually reaches servers that never
 * disagreed with the old one.
 */
class LandPoliciesTest {

    private final LandPolicies policies = LandPolicies.builtIn();

    @Test
    void startsFromWhatEachFlagSaysAboutItself() {
        for (LandFlag flag : LandFlag.values()) {
            assertThat(policies.flagDefault(flag))
                    .as("%s should start at its own default", flag)
                    .isEqualTo(flag.builtInDefault());
            assertThat(policies.policy(flag)).isEqualTo(FlagPolicy.AVAILABLE);
        }
        assertThat(policies.isUntouched()).isTrue();
        assertThat(policies.changed().isEmpty()).isTrue();
    }

    @Test
    void remembersWhatWasChanged() {
        policies.policy(LandFlag.PVP, FlagPolicy.FORCED_OFF);
        policies.flagDefault(LandFlag.HUNGER, !LandFlag.HUNGER.builtInDefault());

        LandPolicies.Changed changed = policies.changed();
        assertThat(changed.flagPolicies()).containsEntry(LandFlag.PVP, FlagPolicy.FORCED_OFF);
        assertThat(changed.flagDefaults()).containsKey(LandFlag.HUNGER);
        assertThat(policies.isUntouched()).isFalse();
    }

    @Test
    void forgetsAPolicyPutBackToTheDefault() {
        policies.policy(LandFlag.PVP, FlagPolicy.FORCED_OFF);
        policies.policy(LandFlag.PVP, FlagPolicy.AVAILABLE);

        assertThat(policies.changed().flagPolicies()).isEmpty();
        assertThat(policies.isUntouched()).isTrue();
    }

    @Test
    void forgetsADefaultPutBackToTheFlagsOwn() {
        boolean own = LandFlag.HUNGER.builtInDefault();
        policies.flagDefault(LandFlag.HUNGER, !own);
        policies.flagDefault(LandFlag.HUNGER, own);

        assertThat(policies.changed().flagDefaults()).isEmpty();
    }


    @Test
    void treatsNullAsPutItBack() {
        policies.policy(LandFlag.PVP, FlagPolicy.FORCED_OFF);
        policies.policy(LandFlag.PVP, null);
        policies.flagDefault(LandFlag.HUNGER, !LandFlag.HUNGER.builtInDefault());
        policies.flagDefault(LandFlag.HUNGER, null);

        assertThat(policies.isUntouched()).isTrue();
    }

    @Test
    void survivesARoundTripThroughWhateverStoresIt() {
        policies.policy(LandFlag.PVP, FlagPolicy.FORCED_ON);
        policies.flagDefault(LandFlag.EXPLOSIONS, !LandFlag.EXPLOSIONS.builtInDefault());
        LandPolicies.Changed saved = policies.changed();

        LandPolicies loaded = LandPolicies.builtIn();
        loaded.restore(saved);

        assertThat(loaded.policy(LandFlag.PVP)).isEqualTo(FlagPolicy.FORCED_ON);
        assertThat(loaded.flagDefault(LandFlag.EXPLOSIONS))
                .isEqualTo(!LandFlag.EXPLOSIONS.builtInDefault());
        assertThat(loaded.changed()).isEqualTo(saved);
    }

    @Test
    void restoringReplacesRatherThanMergesWithWhatWasThere() {
        policies.policy(LandFlag.PVP, FlagPolicy.FORCED_ON);
        policies.restore(LandPolicies.builtIn().changed());

        assertThat(policies.isUntouched()).isTrue();
    }

    @Test
    void aStoredLineForAFlagThatNoLongerExistsIsIgnoredRatherThanFatal() {
        // A flag removed in a later version leaves its line behind in somebody's file. Refusing to load
        // over it would take every other flag on the server down with it.
        policies.set("a-flag-that-was-retired", "forced-off", true);

        assertThat(policies.isUntouched()).isTrue();
    }

    @Test
    void aStoredLineWithAWordNobodyRecognisesLeavesThePolicyAloneButKeepsTheDefault() {
        policies.set(LandFlag.PVP.key(), "sometimes", true);

        assertThat(policies.policy(LandFlag.PVP)).isEqualTo(FlagPolicy.AVAILABLE);
        assertThat(policies.flagDefault(LandFlag.PVP)).isTrue();
    }

    @Test
    void aStoredLineWithNoDefaultLeavesTheDefaultAlone() {
        policies.set(LandFlag.PVP.key(), "forced-off", null);

        assertThat(policies.policy(LandFlag.PVP)).isEqualTo(FlagPolicy.FORCED_OFF);
        assertThat(policies.changed().flagDefaults()).isEmpty();
    }

    @Test
    void whatItHandsOutCannotBeChangedBehindItsBack() {
        policies.policy(LandFlag.PVP, FlagPolicy.FORCED_OFF);
        LandPolicies.Changed changed = policies.changed();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> changed.flagPolicies().put(LandFlag.HUNGER, FlagPolicy.DISABLED)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
