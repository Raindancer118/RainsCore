package de.raindancer.core.world.teleport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where somebody was before they were moved — what {@code /back} goes to.
 *
 * <h2>Why this is Core's rather than the teleport requests'</h2>
 * Because "the last place I was" is asked by more than one thing. A warp, a home, a teleport request
 * and a death all move somebody, and all four want to be undoable. It lived in the teleport-request
 * plugin because that is where {@code /back} was typed, not because that is where it belongs — and a
 * home teleport there recorded nothing, so {@code /back} after {@code /home} took you to wherever the
 * last teleport request had.
 *
 * <h2>The one rule that is not obvious</h2>
 * A death outranks a teleport. Somebody who dies and then is moved by a plugin still wants
 * {@code /back} to mean their body — that is the case where it matters most, and their armour is on
 * the floor there. So a teleport cannot overwrite a death; only using it, or dying again, clears it.
 */
class ReturnsTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private Returns returns;

    @BeforeEach
    void setUp() {
        returns = new Returns();
    }

    private static Waypoint where(String world, int x, Waypoint.Cause why, long at) {
        return new Waypoint(world, x, 64, 0, 0f, 0f, why, at);
    }

    private static Waypoint teleported(int x) {
        return where("world", x, Waypoint.Cause.TELEPORT, 1_000);
    }

    private static Waypoint died(int x) {
        return where("world", x, Waypoint.Cause.DEATH, 2_000);
    }

    @Nested
    @DisplayName("remembering somewhere")
    class Remembering {

        @Test
        @DisplayName("the place is kept and can be read back")
        void itIsKept() {
            assertThat(returns.remember(ALICE, teleported(10))).isTrue();

            assertThat(returns.of(ALICE)).contains(teleported(10));
        }

        @Test
        @DisplayName("a later teleport replaces an earlier one")
        void aLaterTeleportWins() {
            returns.remember(ALICE, teleported(10));
            returns.remember(ALICE, teleported(20));

            assertThat(returns.of(ALICE).orElseThrow().x()).isEqualTo(20);
        }

        @Test
        @DisplayName("nowhere is not somewhere")
        void nullIsRefused() {
            assertThat(returns.remember(ALICE, null)).isFalse();
            assertThat(returns.remember(null, teleported(10))).isFalse();
            assertThat(returns.tracked()).isZero();
        }

        @Test
        @DisplayName("one player's place is not anybody else's")
        void theyAreKeptApart() {
            returns.remember(ALICE, teleported(10));
            returns.remember(BOB, teleported(20));

            assertThat(returns.of(ALICE).orElseThrow().x()).isEqualTo(10);
            assertThat(returns.of(BOB).orElseThrow().x()).isEqualTo(20);
        }

        @Test
        @DisplayName("somebody who has been nowhere has nowhere to go back to")
        void nothingRememberedIsEmpty() {
            assertThat(returns.of(ALICE)).isEmpty();
            assertThat(returns.of(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("dying")
    class Dying {

        @Test
        @DisplayName("a death is remembered")
        void aDeathIsKept() {
            assertThat(returns.remember(ALICE, died(10))).isTrue();

            assertThat(returns.of(ALICE).orElseThrow().cause()).isEqualTo(Waypoint.Cause.DEATH);
        }

        @Test
        @DisplayName("a teleport does not overwrite where they died")
        void aTeleportCannotOverwriteADeath() {
            // The case that matters most: they died, something moved them, and /back has to still
            // mean their body — their armour is on the floor there.
            returns.remember(ALICE, died(10));

            assertThat(returns.remember(ALICE, teleported(20)))
                    .as("the teleport has to be refused, and say so, or a caller cannot tell")
                    .isFalse();
            assertThat(returns.of(ALICE).orElseThrow().x()).isEqualTo(10);
        }

        @Test
        @DisplayName("dying again does overwrite it")
        void aSecondDeathWins() {
            returns.remember(ALICE, died(10));

            assertThat(returns.remember(ALICE, died(20))).isTrue();
            assertThat(returns.of(ALICE).orElseThrow().x()).isEqualTo(20);
        }

        @Test
        @DisplayName("using it clears the death, so the next teleport is remembered again")
        void usingItReleasesTheHold() {
            returns.remember(ALICE, died(10));
            returns.take(ALICE);

            assertThat(returns.remember(ALICE, teleported(20))).isTrue();
            assertThat(returns.of(ALICE).orElseThrow().x()).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("going back")
    class Taking {

        @Test
        @DisplayName("taking it gives it and forgets it")
        void takingConsumesIt() {
            returns.remember(ALICE, teleported(10));

            assertThat(returns.take(ALICE)).contains(teleported(10));
            assertThat(returns.of(ALICE))
                    .as("/back twice in a row must not be a way to hop between two places for ever")
                    .isEmpty();
        }

        @Test
        @DisplayName("taking nothing is empty rather than an error")
        void takingNothing() {
            assertThat(returns.take(ALICE)).isEmpty();
            assertThat(returns.take(null)).isEmpty();
        }

        @Test
        @DisplayName("looking at it does not use it up")
        void lookingIsFree() {
            // What a menu asks to grey a button, or a command asks before checking a cooldown.
            returns.remember(ALICE, teleported(10));

            assertThat(returns.of(ALICE)).isPresent();
            assertThat(returns.of(ALICE)).isPresent();
            assertThat(returns.take(ALICE)).isPresent();
        }
    }

    @Nested
    @DisplayName("letting go")
    class Forgetting {

        @Test
        @DisplayName("somebody who logged out is forgotten")
        void quittingForgets() {
            returns.remember(ALICE, teleported(10));

            returns.forget(ALICE);

            assertThat(returns.tracked())
                    .as("a place kept for somebody who has gone is an entry per player who has ever "
                            + "been teleported on this server")
                    .isZero();
        }

        @Test
        @DisplayName("everything can be dropped at once")
        void everythingCanBeDropped() {
            returns.remember(ALICE, teleported(10));
            returns.remember(BOB, died(20));

            returns.clear();

            assertThat(returns.tracked()).isZero();
        }
    }

    @Nested
    @DisplayName("the place itself")
    class TheWaypoint {

        @Test
        @DisplayName("it reads its coordinates the way a person would")
        void itReadsAsCoordinates() {
            assertThat(new Waypoint("world", 121.4, 64.0, -310.6, 0f, 0f,
                    Waypoint.Cause.TELEPORT, 0).coordinates())
                    .isEqualTo("121, 64, -311");
        }

        @Test
        @DisplayName("each reason says what it is, and none of them is blank")
        void everyReasonReads() {
            for (Waypoint.Cause cause : Waypoint.Cause.values()) {
                assertThat(cause.describe())
                        .as("%s has nothing to say about itself", cause)
                        .isNotBlank();
            }
            assertThat(Waypoint.Cause.TELEPORT.describe())
                    .isNotEqualTo(Waypoint.Cause.DEATH.describe());
        }

        @Test
        @DisplayName("the world is a name, so a place outlives its world being unloaded")
        void theWorldIsAName() {
            // The same reasoning as Poi: a place in a world that is not loaded should be unreachable
            // until it comes back, never thrown away — and holding a World pins an unloaded one in
            // the heap.
            Waypoint somewhere = teleported(10);

            assertThat(somewhere.world()).isEqualTo("world");
        }

        @Test
        @DisplayName("a place needs a world")
        void aPlaceNeedsAWorld() {
            assertThat(new Waypoint(null, 0, 0, 0, 0f, 0f, Waypoint.Cause.TELEPORT, 0).isUsable())
                    .isFalse();
            assertThat(new Waypoint("  ", 0, 0, 0, 0f, 0f, Waypoint.Cause.TELEPORT, 0).isUsable())
                    .isFalse();
            assertThat(teleported(10).isUsable()).isTrue();
        }
    }
}
