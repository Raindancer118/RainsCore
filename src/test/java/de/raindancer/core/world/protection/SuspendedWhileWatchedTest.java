package de.raindancer.core.world.protection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An area whose rules are suspended because somebody with the bypass is standing in it.
 *
 * <h2>Why the obvious fix was not enough</h2>
 * A player's bypass is checked wherever a flag question carries a player. Plenty of them do not carry one, and
 * cannot: {@code BlockRedstoneEvent} has no player on it at all, and it is the event that decides whether a
 * circuit runs.
 *
 * <p>Judging the <em>click</em> instead covered levers and buttons and nothing else. Reported immediately, and
 * correctly: a redstone torch placed by an admin still powered nothing, because placing a block is a build
 * action rather than a click on a redstone component, and the circuit that follows carries no actor. The same is
 * true of a pressure plate somebody steps on, a block that completes a line, and a block broken to break one.
 *
 * <h2>What this does instead</h2>
 * While somebody with the bypass on is inside an area, that area's flags do not apply there. Not a time window
 * — those were the wrong shape twice over, since redstone propagates for many ticks and a window generous
 * enough to cover it is a window in which the flag is off for everybody in the world.
 *
 * <p>Presence is bounded, visible and easy to explain: the rules of a claim are suspended while an admin is
 * standing in it, and they resume when the admin walks out. An admin in a claim can already build and break
 * anything in it; redstone running while they are present is smaller than that, and they can see that they are
 * the reason.
 *
 * <h2>The cost, stated plainly</h2>
 * Somebody else in the same claim gets working redstone for as long as the admin is there. That is the price of
 * a flag whose event has no actor, and it is why the loop is over the bypassing players rather than the online
 * ones — that set is empty on almost every server, so the usual answer costs nothing to find.
 */
class SuspendedWhileWatchedTest {

    private static final Path PROTECTION = Path.of("src/main/java/de/raindancer/core/world/protection");

    private static String read(String file) {
        try {
            return Files.readString(PROTECTION.resolve(file));
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + file, unreadable);
        }
    }

    @Test
    @DisplayName("the scan found the pieces, so a rename cannot quietly empty this")
    void theScanIsNotVacuous() {
        assertThat(read("Land.java")).contains("bypassing");
        assertThat(read("LandFlags.java")).contains("isAllowedAt");
    }

    @Test
    @DisplayName("Land can say whether an area is being watched by somebody who bypasses it")
    void landAnswersTheQuestion() {
        String body = read("Land.java");
        assertThat(body)
                .as("Land owns the bypassing set, so this is the only class that can answer without exposing it")
                .contains("isSuspendedIn(");
    }

    @Test
    @DisplayName("the actor-less flag questions ask it")
    void theActorlessQuestionsUseIt() {
        String body = read("LandFlags.java");
        int at = body.indexOf("public boolean isAllowedAt(Location location, LandFlag flag, UUID who)");
        assertThat(at).isNotNegative();

        assertThat(body.substring(at, Math.min(body.length(), at + 900)))
                .as("this is the overload every world event goes through — a redstone torch, a pressure plate, "
                        + "a block completing a circuit — and none of them carry a player")
                .contains("isSuspendedIn(");
    }

    @Test
    @DisplayName("the loop is over the bypassing players, not the online ones")
    void itCostsNothingWhenNobodyIsBypassing() {
        String body = read("Land.java");
        int at = body.indexOf("public boolean isSuspendedIn(");
        assertThat(at).isNotNegative();
        String method = body.substring(at, body.indexOf("\n    }", at));

        assertThat(method)
                .as("asked on every redstone tick on the server; iterating everybody online would make an "
                        + "empty answer the expensive one")
                .contains("bypassing");
        assertThat(method)
                .as("and it has to be the same area, not merely the same world")
                .contains("id()");
    }

    @Test
    @DisplayName("no area means nothing to suspend")
    void wildernessIsNotSuspended() {
        String method = read("Land.java");
        int at = method.indexOf("public boolean isSuspendedIn(");
        assertThat(method.substring(at, method.indexOf("\n    }", at)))
                .as("a null area is open ground, where the flag was never going to refuse anything")
                .contains("area == null");
    }
}
