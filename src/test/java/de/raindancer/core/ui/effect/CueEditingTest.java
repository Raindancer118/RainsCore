package de.raindancer.core.ui.effect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rebinding a cue from what somebody typed, and describing one so it can be shown.
 *
 * <h2>Why this arithmetic is Core's and is tested on its own</h2>
 * Every server owner who has ever tuned a sound has tuned it as text — {@code sounds.cannon} in a config file,
 * fifteen layers on one line. The old Hunger Games plugin had seven GUI menus for browsing, layering and
 * auditioning cues, and the port dropped all seven; Core owned the cues by then and had no page for them, so
 * the answer to "how do I change what a cannon sounds like" was "edit a file that no longer exists".
 *
 * <p>The page itself cannot be tested without a server. What can be — and is where every bug in a screen like
 * this actually lives — is whether a typed line becomes the sequence it describes, whether a mistake in it is
 * reported rather than swallowed, and whether a cue can be described back as the text it came from. A page
 * that renders a wrong description is a page that lies about what the server will play.
 */
class CueEditingTest {

    @Nested
    @DisplayName("reading what somebody typed")
    class Typing {

        @Test
        @DisplayName("a layered line becomes every layer")
        void layersSurvive() {
            SoundSequence typed = SoundSequence.parseAndExpand(
                    "ENTITY_GENERIC_EXPLODE~0.5; ENTITY_LIGHTNING_BOLT_THUNDER>1250");

            assertThat(typed.steps()).hasSize(2);
            assertThat(typed.lengthMillis())
                    .as("the delay is what makes thunder roll in behind an explosion rather than land on it")
                    .isEqualTo(1_250L);
        }

        @Test
        @DisplayName("a repeat becomes that many layers, a beat apart")
        void repeatsAreExpanded() {
            SoundSequence typed = SoundSequence.parseAndExpand("BLOCK_GLASS_BREAK^3");

            assertThat(typed.steps()).hasSize(3);
            assertThat(typed.steps().get(1).delayMillis())
                    .as("three breaks on the same tick is one louder break, not three")
                    .isEqualTo(SoundSequence.REPEAT_GAP_MILLIS);
        }

        @Test
        @DisplayName("a typo costs that layer and is named, not the whole line")
        void oneBadLayerIsNotTheWholeCue() {
            String written = "ENTITY_GENERIC_EXPLODE; NOT_A_NUMBER@x; BLOCK_GLASS_BREAK";

            assertThat(SoundSequence.parseAndExpand(written).steps())
                    .as("a typo in the second of three sounds should cost the second sound")
                    .hasSize(2);
            assertThat(SoundSequence.problemsIn(written))
                    .as("and it has to be shown, or the page silently plays something else than it says")
                    .hasSize(1);
        }

        @Test
        @DisplayName("a resource pack's own sound is left exactly as written")
        void customSoundsPassThrough() {
            // custom.halt is a real cue on a real server. Upper-casing or underscore-swapping it would
            // silence it, and the owner would have no way of knowing why.
            assertThat(SoundSequence.parseAndExpand("custom.halt").first().key()).isEqualTo("custom.halt");
        }

        @Test
        @DisplayName("particle layers read the same way, colour and all")
        void particlesTooo() {
            ParticleSequence typed = ParticleSequence.parse("DUST@40~0.5#ff2020; CRIT@30~0.5");

            assertThat(typed.bursts()).hasSize(2);
            assertThat(typed.bursts().get(0).count()).isEqualTo(40);
            assertThat(typed.bursts().get(0).speed())
                    .as("the colour rides in the extra value, which is Minecraft's own arrangement")
                    .isEqualTo(0xff2020);
        }
    }

    @Nested
    @DisplayName("showing it back")
    class Describing {

        @Test
        @DisplayName("a cue can be written back as the text it came from")
        void itRoundTrips() {
            String written = "ENTITY_GENERIC_EXPLODE@0.4~1.6>200";

            // Not character-identical — the numbers are normalised — but every part has to survive, or the
            // page shows an owner something other than what the server will play.
            String shown = SoundSequence.parse(written).written();
            assertThat(shown).contains("entity.generic.explode").contains("0.4").contains("1.6")
                    .contains("200");
        }

        @Test
        @DisplayName("silence is a decision and reads as one")
        void silenceIsNotNothing() {
            assertThat(SoundSequence.silence().isSilent()).isTrue();
            assertThat(Effect.silence().isSilent()).isTrue();
            assertThat(SoundSequence.silence().written())
                    .as("an empty string, so a page can show the box as empty rather than as broken")
                    .isEmpty();
        }

        @Test
        @DisplayName("rebinding one half keeps the other")
        void halfARebindingIsNotHalfACue() {
            // What the page does when somebody changes only the sound: the particles that went with it must
            // not vanish as a side effect.
            Effect before = Effect.of(SoundSequence.parseAndExpand("BLOCK_ANVIL_USE"),
                    ParticleSequence.parse("FLAME@20"));

            Effect after = new Effect(SoundSequence.parseAndExpand("BLOCK_BELL_USE"), before.bursts());

            assertThat(after.sounds().first().key()).isEqualTo("block.bell.use");
            assertThat(after.bursts().bursts()).hasSize(1);
        }

        @Test
        @DisplayName("the longest a sequence may be is a cap, not a crash")
        void absurdInputIsBounded() {
            String tooMany = String.join("; ", java.util.Collections.nCopies(60, "BLOCK_GLASS_BREAK"));

            assertThat(SoundSequence.parse(tooMany).steps())
                    .hasSizeLessThanOrEqualTo(SoundSequence.MOST_STEPS);
            assertThat(SoundSequence.problemsIn(tooMany))
                    .as("silently keeping the first thirty-two would read as the rest having been accepted")
                    .isNotEmpty();
        }
    }

    @Nested
    @DisplayName("what a page needs to list")
    class Listing {

        @Test
        @DisplayName("Core's own cues are all there to be shown")
        void thereIsSomethingToList() {
            Effects effects = new Effects(new RecordingSink(), () -> 0L);

            assertThat(effects.all())
                    .as("a cue page over an empty registry would be a page nobody could use")
                    .isNotEmpty();
            assertThat(effects.all().keySet()).contains(Cues.OK, Cues.NO);
        }

        @Test
        @DisplayName("every listed cue can be described without throwing")
        void nothingBreaksTheList() {
            Effects effects = new Effects(new RecordingSink(), () -> 0L);

            // A page draws one row per cue. One cue that throws while being described is a page that will
            // not open, and the cue it broke on is the one nobody can see to fix.
            for (var entry : effects.all().entrySet()) {
                assertThat(entry.getValue().sounds().written()).isNotNull();
                assertThat(entry.getValue().isSilent() || entry.getValue().sounds().first() != null
                        || !entry.getValue().bursts().isNothing()).isTrue();
            }
        }
    }

    /** A sink that records rather than talking to a server. */
    private static final class RecordingSink implements EffectSink {
        final List<String> played = new java.util.ArrayList<>();

        @Override
        public void toPlayer(java.util.UUID player, SoundCue sound) {
            played.add(sound.key());
        }

        @Override
        public void toPlayer(java.util.UUID player, ParticleCue particles) {
            played.add(particles.particle());
        }

        @Override
        public void atPlace(String world, double x, double y, double z, SoundCue sound) {
            played.add(sound.key());
        }

        @Override
        public void atPlace(String world, double x, double y, double z, ParticleCue particles) {
            played.add(particles.particle());
        }

        @Override
        public void stopForPlayer(java.util.UUID player, String soundKey) {
        }

        @Override
        public void stopAllForPlayer(java.util.UUID player) {
        }
    }
}
