package de.raindancer.core.ui.effect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sounds and particles every plugin was doing on its own.
 *
 * <h2>Why this is Core's</h2>
 * Two reasons, and neither is "a wrapper is tidy".
 *
 * <p>The first is that nine plugins each picking their own click sound gives a server nine different
 * clicks, and a server owner who wants them quieter has to edit nine plugins — if they can find them
 * at all, which they cannot, because a sound is one line buried in a menu handler. A named cue that
 * every plugin asks for by meaning, and that is bound in one place, is the difference between a
 * server that sounds like itself and one that sounds like a plugin folder.
 *
 * <p>The second is the same collision as the action bar. A plugin playing a cue on every tick of
 * something is a plugin deafening a player, and it never knows, because from inside that plugin it is
 * one sound.
 */
@DisplayName("effects")
class EffectsTest {

    /** What would have reached a player, instead of a server. */
    private record Played(UUID player, String world, SoundCue sound, ParticleCue particles) {
    }

    private final List<Played> played = new ArrayList<>();
    private final List<String> stopped = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(1_000L);

    private Effects effects() {
        return new Effects(new EffectSink() {
            @Override
            public void toPlayer(UUID player, SoundCue sound) {
                played.add(new Played(player, null, sound, null));
            }

            @Override
            public void toPlayer(UUID player, ParticleCue particles) {
                played.add(new Played(player, null, null, particles));
            }

            @Override
            public void atPlace(String world, double x, double y, double z, SoundCue sound) {
                played.add(new Played(null, world, sound, null));
            }

            @Override
            public void atPlace(String world, double x, double y, double z, ParticleCue particles) {
                played.add(new Played(null, world, null, particles));
            }

            @Override
            public void stopForPlayer(UUID player, String soundKey) {
                stopped.add(player + "/" + soundKey);
            }

            @Override
            public void stopAllForPlayer(UUID player) {
                stopped.add(player + "/*");
            }
        }, now::get);
    }

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    // ------------------------------------------------------------------ the shared vocabulary

    @Nested
    @DisplayName("the named cues")
    class Vocabulary {

        @Test
        @DisplayName("the ones every plugin needs are there without being defined")
        void shipsWithDefaults() {
            Effects effects = effects();
            assertThat(effects.isDefined(Cues.OK)).isTrue();
            assertThat(effects.isDefined(Cues.NO)).isTrue();
            assertThat(effects.isDefined(Cues.CLICK)).isTrue();
            assertThat(effects.isDefined(Cues.TELEPORT)).isTrue();
        }

        @Test
        @DisplayName("playing one sends what it is bound to")
        void playsWhatItIsBoundTo() {
            Effects effects = effects();
            effects.play(ALICE, Cues.OK);

            assertThat(played).hasSize(1);
            assertThat(played.get(0).player()).isEqualTo(ALICE);
            assertThat(played.get(0).sound()).isNotNull();
        }

        @Test
        @DisplayName("a plugin can add its own")
        void pluginsBringTheirOwn() {
            Effects effects = effects();
            effects.define("ghastlines:whoosh",
                    Effect.of(new SoundCue("entity.ghast.shoot", 0.8f, 1.2f)));

            assertThat(effects.isDefined("ghastlines:whoosh")).isTrue();
            effects.play(ALICE, "ghastlines:whoosh");
            assertThat(played.get(0).sound().key()).isEqualTo("entity.ghast.shoot");
        }

        @Test
        @DisplayName("rebinding one changes it everywhere at once")
        void rebindingIsTheWholePoint() {
            Effects effects = effects();
            effects.define(Cues.CLICK, Effect.of(new SoundCue("block.wood.hit", 0.3f, 1.0f)));

            effects.play(ALICE, Cues.CLICK);
            assertThat(played.get(0).sound().key())
                    .as("one line in one place has to change what every menu in every plugin "
                            + "sounds like, or nobody will ever change it")
                    .isEqualTo("block.wood.hit");
        }

        @Test
        @DisplayName("a cue nobody defined does nothing rather than throwing")
        void unknownCuesAreQuiet() {
            Effects effects = effects();
            effects.play(ALICE, "nobody:defined-this");

            assertThat(played)
                    .as("a typo in a sound name must not take down whatever was happening")
                    .isEmpty();
            assertThat(effects.problems()).isNotEmpty();
        }

        @Test
        @DisplayName("a cue can be silenced without every plugin being changed")
        void canBeSilenced() {
            Effects effects = effects();
            effects.define(Cues.CLICK, Effect.silence());

            effects.play(ALICE, Cues.CLICK);
            assertThat(played)
                    .as("an owner who finds the menu clicks annoying should be able to turn them "
                            + "off, not uninstall something")
                    .isEmpty();
        }

        @Test
        @DisplayName("everything can be turned off at once")
        void canBeTurnedOffEntirely() {
            Effects effects = effects();
            effects.enabled(false);
            effects.play(ALICE, Cues.OK);
            assertThat(played).isEmpty();
        }
    }

    // ------------------------------------------------------------------ sound and particles

    @Nested
    @DisplayName("what a cue is made of")
    class Composition {

        @Test
        @DisplayName("one can be a sound, particles, or both")
        void bothHalvesAreOptional() {
            Effects effects = effects();
            effects.define("test:both", new Effect(new SoundCue("block.note_block.bell", 1f, 1f),
                    new ParticleCue("HAPPY_VILLAGER", 10, 0.4, 0.4, 0.4, 0.02)));

            effects.play(ALICE, "test:both");
            assertThat(played).hasSize(2);
            assertThat(played).anyMatch(what -> what.sound() != null);
            assertThat(played).anyMatch(what -> what.particles() != null);
        }

        @Test
        @DisplayName("particles alone reach a player without a sound")
        void particlesAlone() {
            Effects effects = effects();
            effects.define("test:sparkle",
                    Effect.of(new ParticleCue("END_ROD", 5, 0.2, 0.2, 0.2, 0.01)));

            effects.play(ALICE, "test:sparkle");
            assertThat(played).hasSize(1);
            assertThat(played.get(0).particles().particle()).isEqualTo("END_ROD");
        }

        @Test
        @DisplayName("volume and pitch are kept within what the game accepts")
        void clampsWhatTheGameWillTake() {
            SoundCue tooLoud = new SoundCue("x", 99f, 99f);
            assertThat(tooLoud.pitch())
                    .as("the protocol takes 0.5 to 2.0, and out-of-range is silently ignored by "
                            + "the client — which reads as 'the sound is broken'")
                    .isEqualTo(2.0f);
            assertThat(tooLoud.volume()).isLessThanOrEqualTo(10f);

            SoundCue negative = new SoundCue("x", -5f, -5f);
            assertThat(negative.volume()).isZero();
            assertThat(negative.pitch()).isEqualTo(0.5f);
        }

        @Test
        @DisplayName("a cue with no name at all is refused when it is made")
        void refusesNamelessSounds() {
            assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new SoundCue(" ", 1f, 1f))).isNotNull();
        }
    }

    // ------------------------------------------------------------------ not deafening anybody

    /**
     * The arbitration half. A plugin playing a cue every tick is a plugin deafening somebody, and it
     * cannot tell — from inside, it is one sound.
     */
    @Nested
    @DisplayName("not playing the same thing over and over")
    class Throttling {

        @Test
        @DisplayName("the same cue twice in a moment is played once")
        void suppressesRepeats() {
            Effects effects = effects();
            effects.play(ALICE, Cues.CLICK);
            effects.play(ALICE, Cues.CLICK);

            assertThat(played).hasSize(1);
        }

        @Test
        @DisplayName("once the moment has passed it plays again")
        void playsAgainLater() {
            Effects effects = effects();
            effects.play(ALICE, Cues.CLICK);
            now.addAndGet(1_000);
            effects.play(ALICE, Cues.CLICK);

            assertThat(played).hasSize(2);
        }

        @Test
        @DisplayName("a cue played faster than the gap is still heard, over and over")
        void aFastLoopIsThrottledRatherThanSilenced() {
            Effects effects = effects();
            // A plugin playing a cue every 50ms with a 120ms gap. Found by review: the window was
            // pushed forward on every attempt rather than only when the cue played, so after the
            // first one it was never heard again at all — suppression turned into silence, quietly.
            for (int tick = 0; tick < 20; tick++) {
                effects.play(ALICE, Cues.CLICK);
                now.addAndGet(50);
            }

            assertThat(played.size())
                    .as("a second of a cue every 50ms with a 120ms gap should be heard several "
                            + "times, not once")
                    .isGreaterThan(3);
        }

        @Test
        @DisplayName("two different cues do not suppress each other")
        void differentCuesAreIndependent() {
            Effects effects = effects();
            effects.play(ALICE, Cues.CLICK);
            effects.play(ALICE, Cues.OK);

            assertThat(played)
                    .as("a click and a confirmation are two things happening, not one repeated")
                    .hasSize(2);
        }

        @Test
        @DisplayName("two players do not suppress each other")
        void playersAreIndependent() {
            Effects effects = effects();
            effects.play(ALICE, Cues.CLICK);
            effects.play(BOB, Cues.CLICK);

            assertThat(played).hasSize(2);
        }

        @Test
        @DisplayName("how long the moment lasts can be changed, including to nothing")
        void theWindowIsAdjustable() {
            Effects effects = effects();
            effects.minimumGap(java.time.Duration.ZERO);
            effects.play(ALICE, Cues.CLICK);
            effects.play(ALICE, Cues.CLICK);

            assertThat(played)
                    .as("a plugin that genuinely wants a rattle of clicks should be able to have "
                            + "one")
                    .hasSize(2);
        }

        @Test
        @DisplayName("a player who leaves is forgotten")
        void forgetsPlayers() {
            Effects effects = effects();
            effects.play(ALICE, Cues.CLICK);
            effects.forget(ALICE);

            assertThat(effects.remembering())
                    .as("one entry per player per cue, kept for ever, is a leak that grows with "
                            + "every player who ever joins")
                    .isZero();
        }
    }

    // ------------------------------------------------------------------ somewhere rather than someone

    @Nested
    @DisplayName("playing somewhere rather than to somebody")
    class InTheWorld {

        @Test
        @DisplayName("a cue can happen at a place, for everyone nearby")
        void playsAtAPlace() {
            Effects effects = effects();
            effects.playAt("world", 10, 64, 20, Cues.TELEPORT);

            assertThat(played).isNotEmpty();
            assertThat(played.get(0).world()).isEqualTo("world");
            assertThat(played.get(0).player()).isNull();
        }

        @Test
        @DisplayName("a place is not throttled against a player's own cues")
        void placesAreNotPlayers() {
            Effects effects = effects();
            effects.play(ALICE, Cues.TELEPORT);
            effects.playAt("world", 10, 64, 20, Cues.TELEPORT);

            assertThat(played).filteredOn(what -> what.player() != null)
                    .as("somebody else teleporting nearby is a different event from your own")
                    .isNotEmpty();
            assertThat(played).filteredOn(what -> what.world() != null)
                    .as("and the one at a place must not be swallowed by the player's own")
                    .isNotEmpty();
        }
    }

    // ------------------------------------------------------------------ making it stop

    /**
     * Turning a sound off again.
     *
     * <p>Needed as soon as anything is longer than an instant: a jukebox cue, a countdown drone, a
     * boss theme. Without it a plugin's only way to end one is to wait, and a player who logs out
     * mid-cue and back in gets it again on top of itself.
     */
    @Nested
    @DisplayName("stopping one")
    class Stopping {

        @Test
        @DisplayName("a cue that is playing can be stopped")
        void stopsACue() {
            Effects effects = effects();
            effects.play(ALICE, Cues.TELEPORT);
            effects.stop(ALICE, Cues.TELEPORT);

            assertThat(stopped).hasSize(1);
            assertThat(stopped.get(0)).contains("entity.enderman.teleport");
        }

        @Test
        @DisplayName("stopping one does not stop the others")
        void stopsOnlyThatOne() {
            Effects effects = effects();
            effects.stop(ALICE, Cues.CLICK);

            assertThat(stopped).hasSize(1);
            assertThat(stopped.get(0)).doesNotContain("*");
        }

        @Test
        @DisplayName("everything a player can hear can be stopped at once")
        void stopsEverything() {
            Effects effects = effects();
            effects.stopAll(ALICE);

            assertThat(stopped).containsExactly(ALICE + "/*");
        }

        @Test
        @DisplayName("stopping a cue nobody defined does nothing rather than throwing")
        void stoppingTheUnknown() {
            Effects effects = effects();
            effects.stop(ALICE, "nobody:defined-this");
            assertThat(stopped).isEmpty();
        }

        @Test
        @DisplayName("stopping a silent cue does nothing")
        void stoppingSilence() {
            Effects effects = effects();
            effects.define("test:quiet", Effect.silence());
            effects.stop(ALICE, "test:quiet");
            assertThat(stopped).isEmpty();
        }

        @Test
        @DisplayName("stopping clears the repeat window, so it can start again immediately")
        void stoppingLetsItPlayAgain() {
            Effects effects = effects();
            effects.play(ALICE, Cues.CLICK);
            effects.stop(ALICE, Cues.CLICK);
            effects.play(ALICE, Cues.CLICK);

            assertThat(played)
                    .as("a plugin restarting a cue it just stopped is doing so deliberately")
                    .hasSize(2);
        }

        @Test
        @DisplayName("particles cannot be stopped, and it does not pretend otherwise")
        void particlesCannotBeStopped() {
            Effects effects = effects();
            effects.define("test:sparkle",
                    Effect.of(new ParticleCue("END_ROD", 5, 0.2, 0.2, 0.2, 0.01)));
            effects.play(ALICE, "test:sparkle");
            effects.stop(ALICE, "test:sparkle");

            assertThat(stopped)
                    .as("particles are drawn and gone; there is nothing to stop, and inventing a "
                            + "call that silently does nothing would be worse than not having one")
                    .isEmpty();
        }
    }

    // ------------------------------------------------------------------ the shipped vocabulary

    @Nested
    @DisplayName("what a plugin gets without defining anything")
    class Shipped {

        @Test
        @DisplayName("there is a cue for each of the things plugins actually do")
        void coversTheCommonCases() {
            Effects effects = effects();
            for (String cue : Cues.all()) {
                assertThat(effects.isDefined(cue))
                        .as(cue + " is named in Cues but nothing is bound to it")
                        .isTrue();
            }
            assertThat(Cues.all())
                    .as("a handful of cues means every plugin still invents its own")
                    .hasSizeGreaterThanOrEqualTo(16);
        }

        @Test
        @DisplayName("the ones that should be seen as well as heard have particles")
        void someHaveParticles() {
            Effects effects = effects();
            for (String cue : new String[]{Cues.TELEPORT, Cues.EARNED, Cues.HEAL, Cues.MAGIC,
                    Cues.SUMMON, Cues.VANISH}) {
                assertThat(effects.boundTo(cue).orElseThrow().particles())
                        .as(cue + " should be visible, not only audible")
                        .isNotNull();
            }
        }

        @Test
        @DisplayName("every shipped cue actually does something")
        void noneAreAccidentallySilent() {
            Effects effects = effects();
            for (String cue : Cues.all()) {
                assertThat(effects.boundTo(cue).orElseThrow().isSilent())
                        .as(cue + " is bound to nothing at all")
                        .isFalse();
            }
        }
    }
}
