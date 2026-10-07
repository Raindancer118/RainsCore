package de.raindancer.core.moderation.players;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Doing things to a player's body: air, size, speed, flight through the air, a clean slate, a bang.
 * The decisions — ranges, refusals, what counts as nothing to do — are here; only the doing needs a server.
 */
class PlayerBodyTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID GONE = UUID.nameUUIDFromBytes("gone".getBytes());

    private final List<String> did = new ArrayList<>();
    private Body alice;
    private PlayerBody body;

    /** A made-up player's body, so the rules have something to be right about. */
    private static final class Body {
        int air = 300;
        int maxAir = 300;
        double health = 20;
        double scale = 1;
        float walk = 0.2f;
        float fly = 0.1f;
        int fire;
    }

    @BeforeEach
    void setUp() {
        alice = new Body();
        body = new PlayerBody(new PlayerBodySink() {
            @Override
            public Optional<BodyState> stateOf(UUID who) {
                return ALICE.equals(who)
                        ? Optional.of(new BodyState(alice.air, alice.maxAir, alice.health, alice.scale,
                                alice.walk, alice.fly, alice.fire))
                        : Optional.empty();
            }

            @Override
            public void air(UUID who, int air) {
                alice.air = air;
                did.add("air=" + air);
            }

            @Override
            public void drowningDamage(UUID who, double amount) {
                alice.health -= amount;
                did.add("drown=" + amount);
            }

            @Override
            public void launch(UUID who, double x, double y, double z, boolean relativeToLook) {
                did.add("launch=" + x + "," + y + "," + z + (relativeToLook ? ":look" : ""));
            }

            @Override
            public void scale(UUID who, double scale) {
                alice.scale = scale;
                did.add("scale=" + scale);
            }

            @Override
            public void walkSpeed(UUID who, float speed) {
                alice.walk = speed;
                did.add("walk=" + speed);
            }

            @Override
            public void flySpeed(UUID who, float speed) {
                alice.fly = speed;
                did.add("fly=" + speed);
            }

            @Override
            public void wipe(UUID who, java.util.Set<PlayerBody.Wipe> parts) {
                did.add("wipe=" + parts);
            }

            @Override
            public void explode(UUID who, float power, boolean breakBlocks, boolean fire) {
                did.add("boom=" + power + (breakBlocks ? ":blocks" : "") + (fire ? ":fire" : ""));
            }

            @Override
            public void ignite(UUID who, int ticks) {
                alice.fire = ticks;
                did.add("fire=" + ticks);
            }
        });
    }

    @Nested
    @DisplayName("air")
    class Air {

        @Test
        @DisplayName("breathe fills their lungs, and says so when they were already full")
        void breathe() {
            assertThat(body.breathe(ALICE)).isEqualTo(Outcome.NOTHING_TO_DO);
            alice.air = 10;

            assertThat(body.breathe(ALICE)).isEqualTo(Outcome.DONE);
            assertThat(alice.air).isEqualTo(300);
        }

        @Test
        @DisplayName("drown empties their lungs and hurts like water does — never to death")
        void drown() {
            assertThat(body.drown(ALICE, 4)).isEqualTo(Outcome.DONE);
            assertThat(did).containsExactly("air=0", "drown=4.0");

            alice.health = 3;
            assertThat(body.drown(ALICE, 4)).isEqualTo(Outcome.WOULD_KILL);
        }

        @Test
        @DisplayName("nobody online, nothing done")
        void offline() {
            assertThat(body.breathe(GONE)).isEqualTo(Outcome.NOT_ONLINE);
            assertThat(body.drown(GONE, 2)).isEqualTo(Outcome.NOT_ONLINE);
            assertThat(did).isEmpty();
        }
    }

    @Nested
    @DisplayName("size and speed")
    class SizeAndSpeed {

        @Test
        @DisplayName("scale goes from a sixteenth to sixteen times, and nothing outside it")
        void scale() {
            assertThat(body.scale(ALICE, 2)).isEqualTo(Outcome.DONE);
            assertThat(alice.scale).isEqualTo(2);
            assertThat(body.scale(ALICE, 2)).isEqualTo(Outcome.NOTHING_TO_DO);
            assertThat(body.scale(ALICE, 17)).isEqualTo(Outcome.OUT_OF_RANGE);
            assertThat(body.scale(ALICE, 0.01)).isEqualTo(Outcome.OUT_OF_RANGE);
        }

        @Test
        @DisplayName("speed levels 0–10 are multiples of vanilla, capped at the game's 1.0")
        void speedLevels() {
            assertThat(PlayerBody.walkSpeedFor(1)).isEqualTo(0.2f);
            assertThat(PlayerBody.flySpeedFor(1)).isEqualTo(0.1f);
            assertThat(PlayerBody.walkSpeedFor(10)).isEqualTo(1.0f);
            assertThat(PlayerBody.flySpeedFor(10)).isEqualTo(1.0f);
            assertThat(PlayerBody.walkSpeedFor(0)).isEqualTo(0f);
        }

        @Test
        @DisplayName("walk and fly speed are set separately, and out of range is refused")
        void speeds() {
            assertThat(body.walkSpeed(ALICE, 3)).isEqualTo(Outcome.DONE);
            assertThat(body.flySpeed(ALICE, 5)).isEqualTo(Outcome.DONE);
            assertThat(alice.walk).isEqualTo(PlayerBody.walkSpeedFor(3));
            assertThat(alice.fly).isEqualTo(PlayerBody.flySpeedFor(5));
            assertThat(body.walkSpeed(ALICE, 11)).isEqualTo(Outcome.OUT_OF_RANGE);
            assertThat(body.flySpeed(ALICE, -1)).isEqualTo(Outcome.OUT_OF_RANGE);
        }

        @Test
        @DisplayName("speed back to normal")
        void normal() {
            body.walkSpeed(ALICE, 4);
            assertThat(body.resetSpeeds(ALICE)).isEqualTo(Outcome.DONE);
            assertThat(alice.walk).isEqualTo(0.2f);
            assertThat(alice.fly).isEqualTo(0.1f);
        }
    }

    @Nested
    @DisplayName("launching")
    class Launching {

        @Test
        @DisplayName("up is straight up at the power asked")
        void up() {
            assertThat(body.launch(ALICE, PlayerBody.Launch.UP, 2)).isEqualTo(Outcome.DONE);
            assertThat(did).containsExactly("launch=0.0,2.0,0.0");
        }

        @Test
        @DisplayName("forward is where they are looking, with a little lift so they leave the ground")
        void forward() {
            body.launch(ALICE, PlayerBody.Launch.FORWARD, 3);
            assertThat(did).containsExactly("launch=0.0,0.5,3.0:look");
        }

        @Test
        @DisplayName("power is capped so nobody is flung out of the loaded world")
        void capped() {
            assertThat(body.launch(ALICE, PlayerBody.Launch.UP, 50)).isEqualTo(Outcome.OUT_OF_RANGE);
            assertThat(body.launch(ALICE, PlayerBody.Launch.UP, 0)).isEqualTo(Outcome.NOTHING_TO_DO);
        }
    }

    @Test
    @DisplayName("wipe takes exactly the parts asked for, and nothing when asked for none")
    void wipe() {
        assertThat(body.wipe(ALICE, EnumSet.noneOf(PlayerBody.Wipe.class))).isEqualTo(Outcome.NOTHING_TO_DO);
        assertThat(body.wipe(ALICE, PlayerBody.Wipe.STANDARD)).isEqualTo(Outcome.DONE);
        assertThat(did).containsExactly("wipe=[INVENTORY, ADVANCEMENTS, EXPERIENCE]");
    }

    @Test
    @DisplayName("an explosion is bounded; blocks and fire only when asked")
    void explode() {
        assertThat(body.explode(ALICE, 4, false, false)).isEqualTo(Outcome.DONE);
        assertThat(body.explode(ALICE, 200, false, false)).isEqualTo(Outcome.OUT_OF_RANGE);
        assertThat(body.explode(ALICE, 0, false, false)).isEqualTo(Outcome.NOTHING_TO_DO);
        assertThat(did).containsExactly("boom=4.0");
    }

    @Test
    @DisplayName("ignite sets them alight for as long as asked, within reason")
    void ignite() {
        assertThat(body.ignite(ALICE, 5)).isEqualTo(Outcome.DONE);
        assertThat(alice.fire).isEqualTo(100);
        assertThat(body.ignite(ALICE, 0)).isEqualTo(Outcome.NOTHING_TO_DO);
        assertThat(body.ignite(ALICE, 3601)).isEqualTo(Outcome.OUT_OF_RANGE);
    }
}
