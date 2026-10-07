package de.raindancer.core.world.teleport;

import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effect;
import de.raindancer.core.ui.effect.EffectSink;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.effect.ParticleCue;
import de.raindancer.core.ui.effect.ParticleShape;
import de.raindancer.core.ui.effect.SoundCue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a teleport looks and sounds like: the server's cues unless the traveller chose otherwise, and
 * "nothing" being a choice of its own rather than "whatever the server says".
 */
class TravelShowTest {

    private static final UUID BO = UUID.randomUUID();
    private static final UUID CY = UUID.randomUUID();

    private final List<String> heard = new ArrayList<>();
    private final Effects effects = new Effects(new EffectSink() {
        @Override
        public void toPlayer(UUID player, SoundCue sound) {
            heard.add("player:" + sound.key());
        }

        @Override
        public void toPlayer(UUID player, ParticleCue particles) {
            heard.add("player:" + particles.particle());
        }

        @Override
        public void atPlace(String world, double x, double y, double z, SoundCue sound) {
            heard.add(world + ":" + sound.key());
        }

        @Override
        public void atPlace(String world, double x, double y, double z, ParticleCue particles) {
            heard.add(world + ":" + particles.particle());
        }

        @Override
        public void stopForPlayer(UUID player, String soundKey) {
        }

        @Override
        public void stopAllForPlayer(UUID player) {
        }
    }, () -> 0L);

    private final TravelShow show = new TravelShow(effects);

    @Test
    @DisplayName("Core ships a departure sound, waiting particles and an arrival, all rebindable as cues")
    void serverDefaults() {
        assertThat(Cues.all()).contains(Cues.TELEPORT_DEPART, Cues.TELEPORT_WAIT, Cues.TELEPORT);
        show.departed(BO, "world", 0, 64, 0);
        show.arrived(BO, "world_nether", 0, 64, 0);
        assertThat(heard).isNotEmpty();
        assertThat(heard).anyMatch(line -> line.startsWith("world:"));
        assertThat(heard).anyMatch(line -> line.startsWith("world_nether:"));
        assertThat(show.waitParticle(BO)).contains("PORTAL");
        assertThat(show.waitShape(BO)).isEqualTo(ParticleShape.SPIRAL);
    }

    @Test
    @DisplayName("the owner rebinding a cue changes every traveller who has not chosen their own")
    void ownerRebinds() {
        effects.define(Cues.TELEPORT_DEPART, Effect.of(new SoundCue("block.bell.use", 1f, 1f)));
        effects.define(Cues.TELEPORT_WAIT, Effect.of(ParticleCue.of("END_ROD", 1)));
        show.departed(BO, "world", 0, 0, 0);
        assertThat(heard).containsExactly("world:block.bell.use");
        assertThat(show.waitParticle(BO)).contains("END_ROD");
    }

    @Test
    @DisplayName("a traveller's own choice replaces the sound, keeps the server's arrival particles")
    void ownChoice() {
        show.looks(who -> who.equals(BO)
                ? new TravelLook(new SoundCue("block.amethyst_block.chime", 1f, 1f),
                        new SoundCue("entity.player.levelup", 1f, 1f), "HEART", ParticleShape.HALO)
                : TravelLook.SERVERS);

        show.departed(BO, "w", 0, 0, 0);
        assertThat(heard).containsExactly("w:block.amethyst_block.chime");
        heard.clear();
        show.arrived(BO, "w", 0, 0, 0);
        assertThat(heard).containsExactly("w:entity.player.levelup", "w:PORTAL");
        assertThat(show.waitParticle(BO)).contains("HEART");
        assertThat(show.waitShape(BO)).isEqualTo(ParticleShape.HALO);

        heard.clear();
        show.departed(CY, "w", 0, 0, 0);
        assertThat(heard).doesNotContain("w:block.amethyst_block.chime");
        assertThat(show.waitParticle(CY)).contains("PORTAL");
    }

    @Test
    @DisplayName("choosing nothing is silence and no particles, not the server's default")
    void nothing() {
        show.looks(who -> new TravelLook(TravelLook.NOTHING_HEARD, TravelLook.NOTHING_HEARD, "", null));
        show.departed(BO, "w", 0, 0, 0);
        show.arrived(BO, "w", 0, 0, 0);
        assertThat(heard).containsExactly("w:PORTAL");
        assertThat(show.waitParticle(BO)).isEmpty();
    }

    @Test
    @DisplayName("effects switched off server-wide are off for everybody's choices too")
    void switchedOff() {
        show.looks(who -> new TravelLook(new SoundCue("block.bell.use", 1f, 1f), null, null, null));
        effects.enabled(false);
        show.departed(BO, "w", 0, 0, 0);
        show.arrived(BO, "w", 0, 0, 0);
        assertThat(heard).isEmpty();
        assertThat(show.waitParticle(BO)).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("a plugin letting go of the looks only lets go of its own, and a broken one costs nothing")
    void release() {
        TravelLooks mine = who -> new TravelLook(null, null, "HEART", null);
        TravelLooks theirs = who -> new TravelLook(null, null, "FLAME", null);
        show.looks(mine);
        show.release(theirs);
        assertThat(show.waitParticle(BO)).contains("HEART");
        show.release(mine);
        assertThat(show.waitParticle(BO)).contains("PORTAL");

        show.looks(who -> {
            throw new IllegalStateException("cosmetics fell over");
        });
        assertThat(show.waitParticle(BO)).contains("PORTAL");
    }
}
