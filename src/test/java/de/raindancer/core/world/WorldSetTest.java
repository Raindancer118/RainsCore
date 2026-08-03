package de.raindancer.core.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A farm world, and the two worlds that belong to it.
 *
 * <h2>What a set is for</h2>
 * A farm world is not one world. It is three: somewhere to mine, its own nether so people can farm
 * blaze rods without wrecking the main one, and its own end. The three have to be regenerated
 * together — a fresh overworld beside a strip-mined nether is half a farm world — and the portals
 * between them have to stay inside the set, or somebody stepping through a farm-world portal comes
 * out in the main nether and starts mining that instead.
 *
 * <p>All of that is naming, linking and scheduling, none of which needs a server. Creating and
 * regenerating the worlds does, and there is a live check for it.
 */
class WorldSetTest {

    // ------------------------------------------------------------------ naming

    @Nested
    @DisplayName("what the worlds are called")
    class Naming {

        @Test
        @DisplayName("the nether and the end are named after the overworld, as vanilla does")
        void namesFollowVanilla() {
            WorldSet farm = WorldSet.of("farmworld");
            assertThat(farm.overworld()).isEqualTo("farmworld");
            assertThat(farm.nether()).isEqualTo("farmworld_nether");
            assertThat(farm.end()).isEqualTo("farmworld_the_end");
        }

        @Test
        @DisplayName("all three are listed together, in the order somebody expects")
        void listsAllThree() {
            assertThat(WorldSet.of("farmworld").worlds())
                    .containsExactly("farmworld", "farmworld_nether", "farmworld_the_end");
        }

        @Test
        @DisplayName("a set can have only an overworld")
        void canBeOverworldOnly() {
            WorldSet flat = WorldSet.builder("flatlands").withNether(false).withEnd(false).build();
            assertThat(flat.worlds()).containsExactly("flatlands");
            assertThat(flat.hasNether()).isFalse();
        }

        @Test
        @DisplayName("a name has to be usable as a folder")
        void refusesBadNames() {
            assertThatThrownBy(() -> WorldSet.of("../escape"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> WorldSet.of("with spaces"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> WorldSet.of(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("it must not be one of the server's own worlds")
        void refusesTheMainWorlds() {
            assertThatThrownBy(() -> WorldSet.of("world"))
                    .as("regenerating this would delete the server")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ------------------------------------------------------------------ membership

    @Nested
    @DisplayName("which worlds belong to it")
    class Membership {

        private final WorldSet farm = WorldSet.of("farmworld");

        @Test
        @DisplayName("its own three, and nothing else")
        void knowsItsOwn() {
            assertThat(farm.contains("farmworld")).isTrue();
            assertThat(farm.contains("farmworld_nether")).isTrue();
            assertThat(farm.contains("farmworld_the_end")).isTrue();
            assertThat(farm.contains("world")).isFalse();
            assertThat(farm.contains("farmworld2")).isFalse();
            assertThat(farm.contains(null)).isFalse();
        }

        @Test
        @DisplayName("which part of the set a world is")
        void namesThePart() {
            assertThat(farm.partOf("farmworld")).contains(WorldSet.Part.OVERWORLD);
            assertThat(farm.partOf("farmworld_nether")).contains(WorldSet.Part.NETHER);
            assertThat(farm.partOf("farmworld_the_end")).contains(WorldSet.Part.END);
            assertThat(farm.partOf("world")).isEmpty();
        }
    }

    // ------------------------------------------------------------------ portals

    /**
     * The rule that makes a farm world worth having: a portal inside it goes to its own nether.
     * Without this, stepping through one lands in the main nether and the farm world protects
     * nothing.
     */
    @Nested
    @DisplayName("where a portal in it leads")
    class Portals {

        private final WorldSet farm = WorldSet.of("farmworld");

        @Test
        @DisplayName("from its overworld to its own nether")
        void overworldToNether() {
            assertThat(farm.portalTarget("farmworld", WorldSet.Part.NETHER))
                    .contains("farmworld_nether");
        }

        @Test
        @DisplayName("and back again")
        void netherToOverworld() {
            assertThat(farm.portalTarget("farmworld_nether", WorldSet.Part.OVERWORLD))
                    .contains("farmworld");
        }

        @Test
        @DisplayName("to its own end")
        void overworldToEnd() {
            assertThat(farm.portalTarget("farmworld", WorldSet.Part.END))
                    .contains("farmworld_the_end");
        }

        @Test
        @DisplayName("a world outside the set is none of its business")
        void leavesOtherWorldsAlone() {
            assertThat(farm.portalTarget("world", WorldSet.Part.NETHER))
                    .as("the main world's portals are not a farm world's to redirect")
                    .isEmpty();
        }

        @Test
        @DisplayName("a set with no nether sends nobody there")
        void noNetherNoPortal() {
            WorldSet flat = WorldSet.builder("flatlands").withNether(false).build();
            assertThat(flat.portalTarget("flatlands", WorldSet.Part.NETHER)).isEmpty();
        }

        @Test
        @DisplayName("the nether is still one to eight, as everywhere else")
        void keepsTheNetherRatio() {
            assertThat(WorldSet.scaleCoordinate(800, WorldSet.Part.OVERWORLD, WorldSet.Part.NETHER))
                    .isEqualTo(100);
            assertThat(WorldSet.scaleCoordinate(100, WorldSet.Part.NETHER, WorldSet.Part.OVERWORLD))
                    .isEqualTo(800);
            assertThat(WorldSet.scaleCoordinate(100, WorldSet.Part.OVERWORLD, WorldSet.Part.END))
                    .as("the end is not scaled")
                    .isEqualTo(100);
        }
    }

    // ------------------------------------------------------------------ regeneration

    @Nested
    @DisplayName("deciding when to regenerate")
    class Regeneration {

        @Test
        @DisplayName("not before its time")
        void notYet() {
            WorldSet farm = WorldSet.builder("farmworld").every(Duration.ofDays(7)).build();
            Instant made = Instant.ofEpochSecond(1_000_000);
            assertThat(farm.isDue(made, made.plus(Duration.ofDays(3)))).isFalse();
        }

        @Test
        @DisplayName("once its time is up")
        void whenDue() {
            WorldSet farm = WorldSet.builder("farmworld").every(Duration.ofDays(7)).build();
            Instant made = Instant.ofEpochSecond(1_000_000);
            assertThat(farm.isDue(made, made.plus(Duration.ofDays(8)))).isTrue();
        }

        @Test
        @DisplayName("never, when nobody asked for it")
        void neverWhenNotScheduled() {
            WorldSet farm = WorldSet.of("farmworld");
            assertThat(farm.isDue(Instant.EPOCH, Instant.now())).isFalse();
            assertThat(farm.regenerateEvery()).isEmpty();
        }

        @Test
        @DisplayName("when it was never made, it is due immediately")
        void dueWhenNeverMade() {
            WorldSet farm = WorldSet.builder("farmworld").every(Duration.ofDays(7)).build();
            assertThat(farm.isDue(null, Instant.now())).isTrue();
        }

        @Test
        @DisplayName("how long is left can be asked, to tell players before it happens")
        void reportsTimeLeft() {
            WorldSet farm = WorldSet.builder("farmworld").every(Duration.ofDays(7)).build();
            Instant made = Instant.ofEpochSecond(1_000_000);
            assertThat(farm.until(made, made.plus(Duration.ofDays(5))))
                    .contains(Duration.ofDays(2));
        }
    }

    // ------------------------------------------------------------------ the seed

    @Nested
    @DisplayName("the seed")
    class Seeds {

        @Test
        @DisplayName("a fresh one each time, so a regenerated world is actually new")
        void freshSeedEachTime() {
            WorldSet farm = WorldSet.of("farmworld");
            assertThat(farm.nextSeed()).isNotEqualTo(farm.nextSeed());
        }

        @Test
        @DisplayName("unless one was fixed, for a server that wants the same map back")
        void canBeFixed() {
            WorldSet farm = WorldSet.builder("farmworld").seed(12345L).build();
            assertThat(farm.nextSeed()).isEqualTo(12345L);
            assertThat(farm.nextSeed()).isEqualTo(12345L);
        }
    }

    // ------------------------------------------------------------------ borders

    @Test
    @DisplayName("a border can be set, so a farm world does not sprawl for ever")
    void carriesABorder() {
        WorldSet farm = WorldSet.builder("farmworld").border(5000).build();
        assertThat(farm.border()).contains(5000);
        assertThat(WorldSet.of("farmworld").border()).isEmpty();
    }

    @Test
    @DisplayName("two sets with the same name are the same set")
    void isAValue() {
        assertThat(WorldSet.of("farmworld")).isEqualTo(WorldSet.of("farmworld"));
        assertThat(WorldSet.of("farmworld")).isNotEqualTo(WorldSet.of("other"));
    }

    @Test
    @DisplayName("nulls do not throw")
    void survivesNulls() {
        WorldSet farm = WorldSet.of("farmworld");
        assertThatCode(() -> {
            farm.contains(null);
            farm.partOf(null);
            farm.portalTarget(null, null);
        }).doesNotThrowAnyException();
    }
}
