package de.raindancer.core.world.manage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What somebody means by the seed they typed, and which seed a world is then actually made with.
 */
class WorldSeedTest {

    @Test
    @DisplayName("a number is that exact seed, negative ones included")
    void aNumberIsFixed() {
        assertThat(WorldSeed.parse("12345")).contains(WorldSeed.fixed(12345L));
        assertThat(WorldSeed.parse("-4172144997902289642")).contains(WorldSeed.fixed(-4172144997902289642L));
    }

    @Test
    @DisplayName("words that mean a fresh map or the map it had, in either spelling people use")
    void theWords() {
        assertThat(WorldSeed.parse("random")).contains(WorldSeed.random());
        assertThat(WorldSeed.parse("NEW")).contains(WorldSeed.random());
        assertThat(WorldSeed.parse("same")).contains(WorldSeed.same());
        assertThat(WorldSeed.parse("old")).contains(WorldSeed.same());
        assertThat(WorldSeed.parse("keep")).contains(WorldSeed.same());
    }

    @Test
    @DisplayName("any other text is hashed exactly the way the vanilla world screen hashes it")
    void textIsHashedLikeVanilla() {
        // The create-world screen turns a non-numeric seed into String#hashCode. Doing the same here
        // is what makes "glacier" produce the map a player already knows from singleplayer.
        assertThat(WorldSeed.parse("glacier")).contains(WorldSeed.fixed("glacier".hashCode()));
    }

    @Test
    @DisplayName("nothing typed is no answer, rather than a seed of zero")
    void blankIsNothing() {
        assertThat(WorldSeed.parse(null)).isEmpty();
        assertThat(WorldSeed.parse("   ")).isEmpty();
    }

    @Test
    @DisplayName("a number too long for a long is text, as vanilla treats it, not an error")
    void anOverflowIsText() {
        String tooLong = "99999999999999999999999";
        assertThat(WorldSeed.parse(tooLong)).contains(WorldSeed.fixed(tooLong.hashCode()));
    }

    @Test
    @DisplayName("resolving: fixed is itself, same is the previous seed, random is left to the server")
    void resolving() {
        assertThat(WorldSeed.fixed(7).resolve(OptionalLong.of(3))).hasValue(7);
        assertThat(WorldSeed.same().resolve(OptionalLong.of(3))).hasValue(3);
        assertThat(WorldSeed.random().resolve(OptionalLong.of(3))).isEmpty();
    }

    @Test
    @DisplayName("keeping the seed of a world that never had one is a fresh map, not seed zero")
    void sameWithoutAPreviousSeed() {
        assertThat(WorldSeed.same().resolve(OptionalLong.empty())).isEmpty();
    }

    @Test
    @DisplayName("it reads as what it is")
    void describes() {
        assertThat(WorldSeed.random().describe()).isEqualTo("a random seed");
        assertThat(WorldSeed.same().describe()).isEqualTo("the same seed");
        assertThat(WorldSeed.fixed(42).describe()).isEqualTo("seed 42");
    }
}
