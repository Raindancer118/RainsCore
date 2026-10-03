package de.raindancer.core.ui.effect;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** What a burst hands the server as its particle's data, and how a delay reaches it. */
class BukkitEffectSinkTest {

    @Test
    @DisplayName("a colour becomes a dust's colour, or the tint of a particle that takes one")
    void coloursBecomeData() {
        Object dust = BukkitEffectSink.dataFor(Particle.DustOptions.class, 0xff2020);
        assertThat(dust).isInstanceOf(Particle.DustOptions.class);
        assertThat(((Particle.DustOptions) dust).getColor()).isEqualTo(Color.fromRGB(0xff2020));

        assertThat(BukkitEffectSink.dataFor(Color.class, 0x00ff00)).isEqualTo(Color.fromRGB(0x00ff00));
    }

    @Test
    @DisplayName("a particle that needs nothing gets nothing; one that needs what a cue lacks is skipped")
    void whatCannotBeGiven() {
        assertThat(BukkitEffectSink.dataFor(Void.class, 0xff2020)).isNull();
        assertThat(BukkitEffectSink.dataFor(Particle.DustOptions.class, null))
                .isSameAs(BukkitEffectSink.MISSING);
        assertThat(BukkitEffectSink.dataFor(String.class, 0xff2020)).isSameAs(BukkitEffectSink.MISSING);
    }

    @Test
    @DisplayName("Core hands delayed layers to a scheduler, rather than playing them all at once")
    void delaysAreWired() throws IOException {
        String plugin = Files.readString(Path.of("src/main/java/de/raindancer/core/RainsCorePlugin.java"));

        assertThat(plugin).contains("effects.delayedPlaybackVia(");
        assertThat(plugin).contains("new BukkitEffectSink(this)");
    }
}
