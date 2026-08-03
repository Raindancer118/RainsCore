package de.raindancer.core.world.protection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redstone somebody switched on by hand, as opposed to redstone the world did.
 *
 * <h2>The bug this is about</h2>
 * An admin with the bypass on could flip a lever in a claim that has redstone switched off — and nothing
 * happened. The lever moved and the circuit stayed dead.
 *
 * <p>Because the two halves were judged in different places. The <em>click</em> is a permission question, asked
 * with the player, and {@code Land.allow} honours the bypass, so the lever flipped. The <em>flag</em> was
 * enforced on {@code BlockRedstoneEvent}, which has no player on it at all — there was nobody to bypass on
 * behalf of, so the current was held at its old value regardless of who caused it.
 *
 * <h2>The fix, and why it is not a grace period</h2>
 * The obvious repair is to remember that a bypassing admin just touched something and let redstone through for
 * a few ticks. That is wrong twice over: redstone propagates for fifty blocks and many ticks, so the window has
 * to be generous, and while it is open the flag is not enforced for <em>anybody</em> in that world.
 *
 * <p>So the question is asked where the player is: a click on a redstone component consults the flag for that
 * player, and a refusal cancels the click. Nothing flips, so no world event follows and there is nothing left to
 * judge without an actor. {@code BlockRedstoneEvent} then only ever sees world-driven redstone — observers,
 * circuits, pistons — which genuinely has no actor and is correctly judged without a bypass.
 *
 * <p>It also reads better for everybody else: a lever that will not move is clearer than one that moves and
 * does nothing.
 */
class RedstoneByHandTest {

    private static final Path PROTECTION = Path.of("src/main/java/de/raindancer/core/world/protection");

    private static String read(String file) {
        try {
            return Files.readString(PROTECTION.resolve(file));
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + file, unreadable);
        }
    }

    @Test
    @DisplayName("the scan found both halves, so a rename cannot quietly empty this")
    void theScanIsNotVacuous() {
        assertThat(read("InteractionProtectionListener.java")).contains("onInteract");
        assertThat(read("EnvironmentProtectionListener.java")).contains("BlockRedstoneEvent");
    }

    @Test
    @DisplayName("a click on a redstone component is judged with the player who made it")
    void theClickCarriesTheClicker() {
        String body = read("InteractionProtectionListener.java");
        int at = body.indexOf("public void onInteract(");
        assertThat(at).isNotNegative();
        String handler = body.substring(at, body.indexOf("\n    @EventHandler", at));

        assertThat(handler)
                .as("the flag has to be asked here, where there is a player to bypass on behalf of — asked "
                        + "only on the world event, an admin's bypass can never apply")
                .contains("LandFlag.REDSTONE");
    }

    @Test
    @DisplayName("the world event is left to judge world-driven redstone only")
    void theWorldEventIsUnchanged() {
        String body = read("EnvironmentProtectionListener.java");
        int at = body.indexOf("public void onRedstone(");
        assertThat(at).isNotNegative();
        String handler = body.substring(at, at + 900);

        // Deliberately still actor-less. An observer firing has nobody to bypass for, and inventing a grace
        // period would switch the flag off for everybody in the world for as long as it lasted.
        assertThat(handler)
                .as("no grace period, no remembered player — the actor-driven case is handled at the click")
                .doesNotContain("isBypassing")
                .doesNotContain("grace");
    }

    @Test
    @DisplayName("a refusal is explained rather than silently ignored")
    void therefusalIsTold() {
        String body = read("InteractionProtectionListener.java");
        int at = body.indexOf("LandFlag.REDSTONE");
        assertThat(at).isNotNegative();

        assertThat(body.substring(Math.max(0, at - 400), Math.min(body.length(), at + 400)))
                .as("a click that does nothing with no explanation reads as the server lagging")
                .containsPattern("refuse\\(|land\\.redstone-refused|messages");
    }
}
