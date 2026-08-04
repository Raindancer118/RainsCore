package de.raindancer.core.world.spawn;

import de.raindancer.core.world.safety.Spot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a wave is, and where its creatures land.
 *
 * <p>Both are values and arithmetic, which is the whole reason they are Core's and not a screen's: the
 * things worth getting right — that a pack never lands on top of the person it was aimed at, that a
 * wave cannot be six hundred zombies at once — are exactly the things that cannot be checked by
 * looking at a running server.
 */
class WaveTest {

    private static final Spot CENTRE = new Spot("world", 0, 64, 0);

    @Nested
    @DisplayName("the plan")
    class ThePlan {

        @Test
        @DisplayName("a wave is its packs, and it knows how big and how long it is")
        void shape() {
            Wave wave = Wave.of(List.of("zombie"), 4, 5, 10, 20L);

            assertThat(wave.packs()).hasSize(4);
            assertThat(wave.total()).isEqualTo(20);
            assertThat(wave.lengthTicks()).isEqualTo(60L);
        }

        @Test
        @DisplayName("timings are from the start, not from the pack before")
        void timingsAreAbsolute() {
            // A plan whose timings are relative cannot be read without adding them all up, and a pack
            // that failed would shift every one after it.
            List<Long> at = Wave.of(List.of("zombie"), 4, 1, 10, 100L).packs().stream()
                    .map(Wave.Pack::afterTicks).toList();

            assertThat(at).containsExactly(0L, 100L, 200L, 300L);
        }

        @Test
        @DisplayName("several kinds are dealt round-robin, so a mixed pack is actually mixed")
        void kindsAreInterleaved() {
            Wave.Pack pack = Wave.of(List.of("zombie", "skeleton"), 1, 5, 10, 0L).packs().getFirst();

            assertThat(pack.creatures())
                    .containsExactly("zombie", "skeleton", "zombie", "skeleton", "zombie");
        }

        @Test
        @DisplayName("the breakdown says what is coming")
        void breakdown() {
            Wave wave = Wave.of(List.of("zombie", "skeleton"), 2, 3, 10, 20L);

            assertThat(wave.breakdown()).containsEntry("zombie", 3).containsEntry("skeleton", 3);
        }

        @Test
        @DisplayName("silly numbers are clamped rather than obeyed")
        void clamped() {
            // These arrive from a screen where somebody held a button down. A wave of six hundred is
            // not a request, and the useful answer is the biggest one this is willing to run.
            assertThat(Wave.of(List.of("zombie"), 1000, 1, 10, 20L).packs())
                    .hasSize(Wave.MOST_PACKS);
            assertThat(Wave.of(List.of("zombie"), 1, 1000, 10, 20L).packs().getFirst().size())
                    .isEqualTo(Wave.MOST_PER_PACK);
            assertThat(Wave.of(List.of("zombie"), 0, 0, 10, -5L).packs()).hasSize(1);
            assertThat(Wave.of(List.of("zombie"), 0, 0, 10, -5L).lengthTicks()).isZero();
        }

        @Test
        @DisplayName("a wave of nothing is empty rather than a crash")
        void nothingToSpawn() {
            assertThat(Wave.of(List.of(), 3, 3, 10, 20L).packs()).isEmpty();
            assertThat(Wave.of(null, 3, 3, 10, 20L).total()).isZero();
        }

        @Test
        @DisplayName("one pack reads as one pack")
        void justOne() {
            Wave wave = Wave.justOne(List.of("zombie"), 6, 8);

            assertThat(wave.packs()).hasSize(1);
            assertThat(wave.total()).isEqualTo(6);
            assertThat(wave.lengthTicks()).isZero();
        }
    }

    @Nested
    @DisplayName("where they land")
    class TheRing {

        @Test
        @DisplayName("nothing lands on top of whoever it was aimed at")
        void nobodyIsSpawnedInside() {
            // The one that would actually hurt somebody: a pile on one block suffocates itself and
            // arrives as a single lump of damage nobody can react to.
            for (int radius : List.of(0, 1, 3, 10, 24, 100)) {
                for (Spot spot : Swarm.ring(CENTRE, 12, radius, 5L)) {
                    assertThat(spot.distanceSquaredTo(CENTRE))
                            .as("radius %s put one at %s", radius, spot)
                            .isGreaterThanOrEqualTo((long) Swarm.NEAREST * Swarm.NEAREST);
                }
            }
        }

        @Test
        @DisplayName("nothing lands out of sight either")
        void nothingArrivesOverTheHorizon() {
            for (Spot spot : Swarm.ring(CENTRE, 12, 10_000, 5L)) {
                assertThat(spot.distanceSquaredTo(CENTRE))
                        .isLessThanOrEqualTo((long) (Swarm.FURTHEST + 3) * (Swarm.FURTHEST + 3));
            }
        }

        @Test
        @DisplayName("there is one position per creature")
        void onePositionEach() {
            assertThat(Swarm.ring(CENTRE, 7, 8, 1L)).hasSize(7);
            assertThat(Swarm.ringFor(CENTRE, List.of("zombie", "skeleton"), 8, 1L)).hasSize(2);
        }

        @Test
        @DisplayName("they arrive spread out rather than in one place")
        void theyAreSpreadOut() {
            Set<Spot> distinct = new HashSet<>(Swarm.ring(CENTRE, 8, 10, 3L));

            assertThat(distinct).as("a ring that is really a pile").hasSizeGreaterThan(5);
        }

        @Test
        @DisplayName("they stay on the level they were aimed at")
        void sameHeight() {
            assertThat(Swarm.ring(CENTRE, 8, 10, 3L))
                    .allSatisfy(spot -> assertThat(spot.y()).isEqualTo(CENTRE.y()));
        }

        @Test
        @DisplayName("the same seed gives the same arrangement")
        void seeded() {
            assertThat(Swarm.ring(CENTRE, 8, 10, 11L)).isEqualTo(Swarm.ring(CENTRE, 8, 10, 11L));
            assertThat(Swarm.ring(CENTRE, 8, 10, 11L)).isNotEqualTo(Swarm.ring(CENTRE, 8, 10, 12L));
        }

        @Test
        @DisplayName("a pack of one is still placed beside, not on top")
        void oneCreature() {
            assertThat(Swarm.ring(CENTRE, 1, 5, 1L).getFirst().distanceSquaredTo(CENTRE))
                    .isGreaterThanOrEqualTo((long) Swarm.NEAREST * Swarm.NEAREST);
        }
    }

    @Nested
    @DisplayName("putting one down")
    class Placing {

        /** A world that takes everything, or refuses whatever it is told to. */
        private static final class FakeSpawner implements Spawner {

            private final List<String> spawned = new ArrayList<>();
            private final Set<Spot> unloaded = new HashSet<>();
            private boolean refuseEverything;

            @Override
            public boolean spawn(Spot spot, String type) {
                if (refuseEverything) {
                    return false;
                }
                spawned.add(type);
                return true;
            }

            @Override
            public boolean isLoaded(Spot spot) {
                return !unloaded.contains(spot);
            }
        }

        @Test
        @DisplayName("every creature in the pack is spawned")
        void allOfThem() {
            FakeSpawner spawner = new FakeSpawner();
            Wave.Pack pack = Wave.justOne(List.of("zombie", "skeleton"), 6, 8).packs().getFirst();

            Spawns.Arrived arrived = new Spawns(spawner).place(pack, CENTRE, 1L);

            assertThat(arrived.spawned()).isEqualTo(6);
            assertThat(arrived.refused()).isZero();
            assertThat(spawner.spawned).containsExactly("zombie", "skeleton", "zombie", "skeleton",
                    "zombie", "skeleton");
        }

        @Test
        @DisplayName("one refusal costs one creature, not the pack")
        void aPartialPack() {
            // The version that gives up on the first failure is the one where a pack aimed at a cave
            // mouth arrives as two zombies because the third position was inside the hill.
            FakeSpawner spawner = new FakeSpawner();
            Wave.Pack pack = Wave.justOne(List.of("zombie"), 6, 8).packs().getFirst();
            spawner.unloaded.add(Swarm.ringFor(CENTRE, pack.creatures(), pack.radius(), 1L).getFirst());

            Spawns.Arrived arrived = new Spawns(spawner).place(pack, CENTRE, 1L);

            assertThat(arrived.spawned()).isEqualTo(5);
            assertThat(arrived.refused()).isOne();
        }

        @Test
        @DisplayName("a world that takes nothing is reported rather than pretended about")
        void nothingArrived() {
            FakeSpawner spawner = new FakeSpawner();
            spawner.refuseEverything = true;

            Spawns.Arrived arrived = new Spawns(spawner)
                    .place(Wave.justOne(List.of("zombie"), 4, 8).packs().getFirst(), CENTRE, 1L);

            assertThat(arrived.isEmpty()).isTrue();
            assertThat(arrived.refused()).isEqualTo(4);
        }
    }
}
