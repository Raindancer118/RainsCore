package de.raindancer.core.world.teleport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four things about {@link Travel} that only a running server could otherwise catch.
 *
 * <h2>Why these are read out of the source</h2>
 * Because every one of them needs a {@code Player}, a scheduler and a loaded world to exercise, which
 * means the only way to find them is on a live server with somebody standing in it. Each was a real
 * defect in the first version of this class — found by review, not by running it — and each is the
 * kind that shows up as "the plugin teleported me twice" a week after release.
 *
 * <p>{@link Departures} holds the part that can be tested properly, and is. This is the seam that
 * cannot be, so it is pinned by the shape of the code instead of by its behaviour. A weaker test than
 * the others in this package, and better than nothing at all — which is what it replaced.
 */
class TravelGrammarTest {

    private static final Path SOURCE =
            Path.of("src/main/java/de/raindancer/core/world/teleport/Travel.java");

    private static String source() {
        try {
            return Files.readString(SOURCE);
        } catch (IOException unreadable) {
            throw new AssertionError("Travel is gone", unreadable);
        }
    }

    @Test
    @DisplayName("the scan reads it, so a rename cannot quietly empty this")
    void theScanIsNotVacuous() {
        assertThat(source()).contains("class Travel").contains("public void go(");
    }

    @Test
    @DisplayName("one journey at a time, and the guard is a single atomic operation")
    void oneJourneyAtATime() {
        // Two things, both of which were wrong once.
        //
        // The double-teleport: somebody mid-warm-up who took an instant trip elsewhere went straight
        // through, and then the warm-up finished and moved them again — to the first destination,
        // seconds after they had arrived somewhere else.
        //
        // And the cooldown bypass: an instant trip never becomes a departure, so a caller charging
        // its cooldown on arrival saw nothing in progress and let a player warp as fast as they could
        // type. Both are one guard, and it has to be atomic — checking a set and then adding to it is
        // two commands on two Folia threads both passing.
        String body = source();
        int guard = body.indexOf("if (!inFlight.add(who))");
        int instant = body.indexOf("if (!trip.hasWarmup())");

        assertThat(guard)
                .as("nothing atomically claims the journey — a contains-then-add is two threads "
                        + "both getting through")
                .isPositive();
        assertThat(instant).as("the instant path is gone").isPositive();
        assertThat(guard)
                .as("the guard has to come before the instant path, or an instant trip goes through "
                        + "without ever consulting it")
                .isLessThan(instant);
        assertThat(body)
                .as("a contains() check would be the two-step version of the same guard")
                .doesNotContain("if (inFlight.contains(who))");
    }

    @Test
    @DisplayName("every way a journey can end lets go of the player")
    void nobodyIsLeftMarkedAsTravelling() {
        // Five ways out of go(), and the one that forgets to release is a player who can never travel
        // again until they log out — reported as "warping stopped working for me only".
        String body = source();

        assertThat(body)
                .as("the release is spread across the call sites instead of being in one wrapper, "
                        + "which is how one of them comes to be missed")
                .contains("private final class Finishing implements TravelWatcher");

        int wrapper = body.indexOf("private final class Finishing");
        String inTheWrapper = body.substring(wrapper);
        for (String ending : new String[]{"arrived", "cancelled", "refused"}) {
            int at = inTheWrapper.indexOf("public void " + ending + "(");
            assertThat(at).as("the wrapper does not handle %s", ending).isPositive();
            assertThat(inTheWrapper.substring(at, at + 260))
                    .as("%s ends a journey and does not let go of the player", ending)
                    .contains("inFlight.remove(who)");
        }

        assertThat(body)
                .as("a player who logs out mid-journey has to be released too, or the set grows by "
                        + "an entry for everybody who ever quit while warping")
                .contains("inFlight.remove(traveller)");
    }

    @Test
    @DisplayName("a journey and its countdown are never left without each other")
    void theJourneyAndTheTaskAreKeptTogether() {
        // The window: begin() succeeds, then a movement on another thread cancels the departure and
        // finds no journey to cancel the task with, and then the put() lands. Closed by re-checking
        // after the put.
        String body = source();
        int put = body.indexOf("journeys.put(");
        String afterThePut = body.substring(put);

        assertThat(afterThePut)
                .as("nothing re-checks the departure after the journey is stored, so a cancellation "
                        + "arriving in that window is lost and the player is told nothing")
                .contains("!departures.isLeaving(");
        assertThat(afterThePut)
                .as("the orphaned countdown has to be cancelled, or it runs on with nothing behind it")
                .contains("countdown().cancel()");
    }

    @Test
    @DisplayName("nothing handed to a scheduler can throw into it")
    void schedulerTasksCannotThrow() {
        // exceptionally() on the safety future does not cover these: the work is handed to a
        // scheduler and runs later, on another thread, after that stage has already completed. A
        // watcher that throws — a plugin's own message code, most likely — would surface as an
        // uncaught exception on the entity task looper, where on Folia it can take the region's task
        // queue with it.
        String body = source();

        assertThat(body)
                .as("every scheduled callback goes through the one wrapper that catches and logs")
                .contains("private void onThePlayersThread(");

        int wrapper = body.indexOf("private void onThePlayersThread(");
        String elsewhere = body.substring(0, wrapper);
        assertThat(elsewhere)
                .as("a raw Scheduling.entity outside the wrapper is a callback whose exceptions go "
                        + "nowhere anybody will read them")
                .doesNotContain("Scheduling.entity(plugin, traveller, () ->");
    }

    @Test
    @DisplayName("the arrival never falls back to the spot known to be dangerous")
    void nowhereSafeIsARefusal() {
        // Falling back to the exact coordinates puts the player in the place the check has just said
        // is unsafe, which is the whole thing the safety package exists to stop.
        String body = source();
        int found = body.indexOf("found.ifPresentOrElse(");
        String choice = body.substring(found, body.indexOf(";", found));

        assertThat(choice)
                .as("nowhere safe has to be a refusal, not a teleport to the original spot")
                .contains("TravelReason.NOWHERE_SAFE");
        assertThat(List.of(choice.split("NOWHERE_SAFE")))
                .as("the refusing branch is the second one — the first teleports")
                .hasSize(2);
    }
}
