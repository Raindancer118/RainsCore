package de.raindancer.core.world.protection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That keep-inventory is a thing claims do, not a thing the server does.
 *
 * <h2>The bug this fixes</h2>
 * Reported as "keep inventory is on in the entire world and cannot be turned off by whatever I do" — and it was,
 * in every world, with no gamerule set and nothing in any log to say why.
 *
 * <h2>Why it is the mirror of {@link WildernessIsNotProtectedTest}, not a repeat of it</h2>
 * That bug was the resolver answering a flag's <em>default</em> for unclaimed ground, which switched eleven
 * flags off across the world. Its fix was {@code area == null → true}: with no area there is no flag, nothing
 * is interfering, and the answer to "may this happen" is yes.
 *
 * <p>That fix is right, and it is right for every flag whose {@code true} means <b>an action is permitted</b> —
 * fire spreads, TNT goes off, a block may be broken. {@link LandFlag#KEEP_INVENTORY} is not one of those. Its
 * {@code true} means <b>an action is taken</b>: the death listener reads it and calls {@code setKeepInventory}.
 * So "nothing is interfering" resolved to true, and the listener dutifully interfered — everywhere, for
 * everybody, because unclaimed ground is most of a world.
 *
 * <p>Which is also why no amount of configuring helped. Switching the flag off in a claim only changes the
 * answer <em>inside</em> that claim; the whole rest of the world still had no area and still answered yes. The
 * one setting that looked like it should work — setting the policy to DISABLED — makes {@code isEnforced} false
 * and does turn it off, but that reads as "this flag is not available to owners at all", which is not what
 * somebody wanting vanilla deaths in the wilderness is trying to say.
 */
class KeepInventoryStaysInsideClaimsTest {

    private static final UUID SOMEBODY = UUID.randomUUID();

    private final FlagRules rules = new FlagRules(new LandPolicies());

    /** An area that says yes to everything, so a false here cannot have come from the area. */
    private static ProtectedArea generous() {
        return FakeArea.named("Generous").with(LandFlag.KEEP_INVENTORY, true);
    }

    @Nested
    @DisplayName("outside every claim")
    class InTheWilderness {

        @Test
        @DisplayName("keep-inventory does not apply, so a death is a vanilla death")
        void itDoesNotApplyWithoutAnArea() {
            assertThat(rules.isAppliedTo(null, LandFlag.KEEP_INVENTORY, null, SOMEBODY))
                    .as("with no claim there is nothing saying to keep anybody's inventory, and the whole "
                            + "world is unclaimed ground")
                    .isFalse();
        }

        @Test
        @DisplayName("nor do the other flags that make something happen rather than permit it")
        void norTheOtherActingFlags() {
            // The same shape, and the reason this is asked as a question about the *kind* of flag rather than
            // about this one: any flag whose true means "do something" is wrong on unclaimed ground for
            // exactly this reason, and the next one added would have the same bug.
            for (LandFlag flag : List.of(LandFlag.KEEP_INVENTORY)) {
                assertThat(rules.isAppliedTo(null, flag, null, SOMEBODY))
                        .as("%s makes something happen, so it needs ground that asked for it", flag)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("but asking whether something is permitted still says yes, as it must")
        void permissionQuestionsAreUnchanged() {
            // The other half of the fix, and the thing that must not regress: WildernessIsNotProtectedTest
            // exists because this used to answer no.
            assertThat(rules.isAllowed(null, LandFlag.EXPLOSIONS, null, SOMEBODY)).isTrue();
            assertThat(rules.isAllowed(null, LandFlag.PVP, null, SOMEBODY)).isTrue();
            assertThat(rules.isAllowed(null, LandFlag.KEEP_INVENTORY, null, SOMEBODY))
                    .as("unchanged — it is the listener that must ask the other question")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("inside a claim that asked for it")
    class InsideAClaim {

        @Test
        @DisplayName("keep-inventory applies")
        void itAppliesWithAnArea() {
            assertThat(rules.isAppliedTo(generous(), LandFlag.KEEP_INVENTORY, LandAudience.VISITOR,
                    SOMEBODY))
                    .as("a claim that switched it on is exactly what the flag is for")
                    .isTrue();
        }

        @Test
        @DisplayName("and does not when the claim did not ask for it")
        void itFollowsTheArea() {
            ProtectedArea refuses = FakeArea.named("Strict").with(LandFlag.KEEP_INVENTORY, false);

            assertThat(rules.isAppliedTo(refuses, LandFlag.KEEP_INVENTORY, LandAudience.VISITOR,
                    SOMEBODY)).isFalse();
        }
    }

    @Test
    @DisplayName("the flag ships off, so a server that configures nothing has vanilla deaths")
    void theDefaultIsOff() {
        assertThat(new LandPolicies().flagDefault(LandFlag.KEEP_INVENTORY))
                .as("a new claim does not keep anybody's inventory unless its owner says so")
                .isFalse();
    }
}
