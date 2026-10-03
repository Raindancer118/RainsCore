package de.raindancer.core.platform.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClosestTest {

    private static final List<String> WORLDS = List.of("world", "world_nether", "world_the_end", "farmworld", "lobby");

    @Test
    @DisplayName("a slip of a letter or two finds the name, best first")
    void slips() {
        assertThat(Closest.to("wrold", WORLDS, 3)).first().isEqualTo("world");
        assertThat(Closest.to("lobyy", WORLDS, 3)).containsExactly("lobby");
        assertThat(Closest.to("World-Nether", WORLDS, 1)).containsExactly("world_nether");
    }

    @Test
    @DisplayName("part of a name finds the names containing it")
    void parts() {
        assertThat(Closest.to("nether", WORLDS, 3)).containsExactly("world_nether");
        assertThat(Closest.to("end", WORLDS, 3)).containsExactly("world_the_end");
    }

    @Test
    @DisplayName("nothing alike, nothing offered; no limit, nothing offered")
    void nothing() {
        assertThat(Closest.to("spawn", WORLDS, 3)).isEmpty();
        assertThat(Closest.to("", WORLDS, 3)).isEmpty();
        assertThat(Closest.to("world", WORLDS, 0)).isEmpty();
        assertThat(Closest.to(null, WORLDS, 3)).isEmpty();
    }

    @Test
    @DisplayName("two letters swapped are one slip")
    void swapped() {
        assertThat(Closest.distance("ab", "ba")).isEqualTo(1);
        assertThat(Closest.distance("kitten", "sitting")).isEqualTo(3);
        assertThat(Closest.distance("", "abc")).isEqualTo(3);
    }
}
