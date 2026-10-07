package de.raindancer.core.ui.effect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Which particles can be worn at all, and which of them take a colour. */
class ParticleShowsTest {

    @Test
    @DisplayName("a particle that needs nothing can be shown, and takes no colour")
    void plainParticles() {
        assertThat(ParticleShows.canShow("flame")).isTrue();
        assertThat(ParticleShows.takesColour("FLAME")).isFalse();
    }

    @Test
    @DisplayName("dust needs a colour, and with one it can be shown")
    void dustTakesAColour() {
        assertThat(ParticleShows.canShow("DUST")).isTrue();
        assertThat(ParticleShows.takesColour("DUST")).isTrue();
    }

    @Test
    @DisplayName("a particle that needs a block or an item cannot be worn — it would show nothing")
    void blockParticlesCannot() {
        assertThat(ParticleShows.canShow("BLOCK")).isFalse();
        assertThat(ParticleShows.canShow("ITEM")).isFalse();
    }

    @Test
    @DisplayName("a name that is no particle is not shown, rather than thrown about")
    void unknown() {
        assertThat(ParticleShows.canShow("sparkles")).isFalse();
        assertThat(ParticleShows.canShow(null)).isFalse();
        assertThat(ParticleShows.takesColour("sparkles")).isFalse();
    }

    @Test
    @DisplayName("a gradient runs from the first colour to the second along the shape; without a second it is one colour")
    void gradient() {
        assertThat(ParticleShows.colourAlong(0xFF0000, 0x0000FF, 0)).isEqualTo(0xFF0000);
        assertThat(ParticleShows.colourAlong(0xFF0000, 0x0000FF, 1)).isEqualTo(0x0000FF);
        assertThat(ParticleShows.colourAlong(0xFF0000, 0x0000FF, 0.5)).isEqualTo(0x800080);
        assertThat(ParticleShows.colourAlong(0x00FF00, null, 0.7)).isEqualTo(0x00FF00);
    }
}
