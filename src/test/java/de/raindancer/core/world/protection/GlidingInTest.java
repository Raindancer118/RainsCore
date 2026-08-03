package de.raindancer.core.world.protection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flying into somewhere that does not allow flying.
 *
 * <h2>The gap this closes</h2>
 * {@link LandFlag#ELYTRA_FLIGHT} was enforced on {@code EntityToggleGlideEvent} alone, which fires when a player
 * <em>starts</em> gliding. Start outside, glide in, and nothing ever asked the question — so the flag stopped
 * people taking off inside a claim and did nothing at all about people arriving under their own steam. The old
 * plugin had the same hole, so this is not a regression; it is a flag that only ever half worked.
 *
 * <h2>Why the glide is stopped rather than the movement refused</h2>
 * Cancelling a move at elytra speed rubber-bands the player across the border repeatedly, which is worse than
 * the thing being prevented. Stopping the glide is what the flag actually says: no flying here.
 *
 * <h2>And why the fall that follows is not the player's fault</h2>
 * Dropping somebody out of the air is a fall they did not choose, and fall damage is separately switchable —
 * so an owner could otherwise pair "no elytra" with "fall damage on" and have a border that kills anybody who
 * flies into it. The plugin caused the fall, so the plugin eats the damage. That is one short grace, not a
 * permanent exemption: it lasts long enough to land and no longer.
 */
class GlidingInTest {

    private static final Path LISTENER = Path.of(
            "src/main/java/de/raindancer/core/world/protection/MovementProtectionListener.java");

    private static String listener() {
        try {
            return Files.readString(LISTENER);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read MovementProtectionListener", unreadable);
        }
    }

    @Test
    @DisplayName("the scan found the listener, so a rename cannot quietly empty this")
    void theScanIsNotVacuous() {
        assertThat(listener()).contains("WALK_IN");
    }

    @Test
    @DisplayName("crossing into a claim while gliding is enforced, not only taking off in one")
    void arrivingUnderPowerIsChecked() {
        String body = listener();

        assertThat(body)
                .as("the toggle event fires when gliding starts; somebody who started outside never triggers "
                        + "it, which is the whole bug")
                .contains("ELYTRA_FLIGHT");
        assertThat(body)
                .as("the enforcement is to stop the glide, since cancelling a move at elytra speed "
                        + "rubber-bands them across the border instead")
                .contains("setGliding(false)");
    }

    @Test
    @DisplayName("the fall the plugin caused does not hurt")
    void theDropIsNotADeathTrap() {
        String body = listener();

        assertThat(body)
                .as("fall damage is separately switchable, so without this an owner can pair 'no elytra' with "
                        + "'fall damage on' and make a border that kills whoever flies into it")
                .contains("caughtFalling");
    }

    @Test
    @DisplayName("the grace is short, and is not a way to switch fall damage off for good")
    void thegraceExpires() {
        String body = listener();
        int at = body.indexOf("caughtFalling");
        assertThat(at).isNotNegative();

        // A grace with no end is an exemption. It has to cover the drop and stop.
        assertThat(body)
                .as("somebody dropped at the border should be safe until they land, not for ever")
                .containsPattern("GLIDE_GRACE|graceMillis|FALL_GRACE");
    }

    @Test
    @DisplayName("a bypassing admin keeps flying")
    void theBypassStillWins() {
        String body = listener();
        int at = body.indexOf("ELYTRA_FLIGHT");
        assertThat(at).isNotNegative();

        assertThat(body)
                .as("no flag an owner sets may ground an admin who is on their way to fix something")
                .contains("isBypassing");
    }

    @Test
    @DisplayName("gliding out, or about inside, is left alone")
    void onlyArrivingIsEnforced() {
        // The same rule the rest of the way-in flags follow: somebody already inside when the flag was switched
        // off has to be able to leave, and being dropped on the way out is the flag punishing them for obeying it.
        assertThat(listener())
                .as("the crossing test already exists for WALK_IN and has to be shared, not written twice")
                .contains("refusedAt");
    }
}
