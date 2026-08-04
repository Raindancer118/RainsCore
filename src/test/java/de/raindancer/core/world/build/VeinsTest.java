package de.raindancer.core.world.build;

import de.raindancer.core.world.safety.Spot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ore veins: the shape they take, and what they are willing to bury themselves in.
 *
 * <p>Both halves are here because both are testable, and the second is the one that matters. This is a
 * tool a moderator points at the ground while standing in somebody's build, and there is no undo — so
 * "it never replaces a block a player placed" is not a nicety, it is the property that keeps the worst
 * outcome at "nothing happened".
 */
class VeinsTest {

    private static final Spot CENTRE = new Spot("world", 100, 40, 100);

    /** A world as a map, which is all {@link Ground} ever needed to be. */
    private static final class FakeGround implements Ground {

        private final Map<Spot, String> blocks = new HashMap<>();
        private final Set<Spot> unloaded = new HashSet<>();
        private String everywhereElse = "STONE";

        FakeGround put(Spot spot, String material) {
            blocks.put(spot, material);
            return this;
        }

        FakeGround unload(Spot spot) {
            unloaded.add(spot);
            return this;
        }

        FakeGround fillWith(String material) {
            everywhereElse = material;
            return this;
        }

        @Override
        public String materialAt(Spot spot) {
            return blocks.getOrDefault(spot, everywhereElse);
        }

        @Override
        public boolean set(Spot spot, String material) {
            blocks.put(spot, material);
            return true;
        }

        @Override
        public boolean isLoaded(Spot spot) {
            return !unloaded.contains(spot);
        }

        long countOf(String material) {
            return blocks.values().stream().filter(material::equals).count();
        }
    }

    @Nested
    @DisplayName("the shape")
    class Shape {

        @Test
        @DisplayName("a vein has the number of blocks it was asked for")
        void theRightSize() {
            assertThat(OreVein.around(CENTRE, 12, 1L).size()).isEqualTo(12);
            assertThat(OreVein.around(CENTRE, 1, 1L).size()).isOne();
        }

        @Test
        @DisplayName("every block is connected to another, so it reads as one vein")
        void itIsOneLump() {
            // The property that makes it a vein rather than ore sprinkled in a box. Checked by walking
            // out from the centre: everything must be reachable.
            List<Spot> blocks = OreVein.around(CENTRE, 30, 7L).blocks();
            Set<Spot> all = new HashSet<>(blocks);
            Set<Spot> reached = new HashSet<>();
            java.util.Deque<Spot> queue = new java.util.ArrayDeque<>();
            queue.add(blocks.getFirst());
            reached.add(blocks.getFirst());
            while (!queue.isEmpty()) {
                Spot at = queue.poll();
                for (Spot next : List.of(at.offset(1, 0, 0), at.offset(-1, 0, 0), at.offset(0, 1, 0),
                        at.offset(0, -1, 0), at.offset(0, 0, 1), at.offset(0, 0, -1))) {
                    if (all.contains(next) && reached.add(next)) {
                        queue.add(next);
                    }
                }
            }
            assertThat(reached).as("the vein is in two pieces").hasSameSizeAs(all);
        }

        @Test
        @DisplayName("it starts where it was aimed and stays within reach of there")
        void itStaysNearTheCentre() {
            // A vein whose far end is twelve blocks away is one the person who placed it cannot find.
            OreVein vein = OreVein.around(CENTRE, 40, 3L);

            assertThat(vein.blocks().getFirst()).isEqualTo(CENTRE);
            assertThat(vein.blocks()).allSatisfy(spot ->
                    assertThat(spot.distanceSquaredTo(CENTRE)).isLessThanOrEqualTo(36L));
        }

        @Test
        @DisplayName("no block appears twice")
        void noDuplicates() {
            assertThat(OreVein.around(CENTRE, 40, 9L).blocks()).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the same seed gives the same vein, and a different one does not")
        void seeded() {
            assertThat(OreVein.around(CENTRE, 20, 42L)).isEqualTo(OreVein.around(CENTRE, 20, 42L));
            assertThat(OreVein.around(CENTRE, 20, 42L)).isNotEqualTo(OreVein.around(CENTRE, 20, 43L));
        }

        @Test
        @DisplayName("a silly size is clamped rather than obeyed")
        void clamped() {
            // Zero is a command that silently does nothing; ten thousand is a crater. Both arrive from
            // a screen where somebody held a button down.
            assertThat(OreVein.around(CENTRE, 0, 1L).size()).isOne();
            assertThat(OreVein.around(CENTRE, -5, 1L).size()).isOne();
            assertThat(OreVein.around(CENTRE, 10_000, 1L).size()).isEqualTo(OreVein.MOST_BLOCKS);
        }
    }

    @Nested
    @DisplayName("what it will and will not bury itself in")
    class Placing {

        @Test
        @DisplayName("it fills natural ground")
        void naturalGround() {
            FakeGround ground = new FakeGround();
            Veins.Placed placed = new Veins(ground).place(OreVein.around(CENTRE, 10, 1L), "IRON_ORE");

            assertThat(placed.blocks()).isEqualTo(10);
            assertThat(placed.skipped()).isZero();
            assertThat(ground.countOf("IRON_ORE")).isEqualTo(10);
        }

        @Test
        @DisplayName("it never replaces a block a player could have placed")
        void placedBlocksSurvive() {
            // The property this whole class exists for. A moderator drops a vein while standing in
            // somebody's house; the house has to still be there afterwards.
            FakeGround ground = new FakeGround().fillWith("OAK_PLANKS");

            Veins.Placed placed = new Veins(ground).place(OreVein.around(CENTRE, 10, 1L), "IRON_ORE");

            assertThat(placed.blocks()).isZero();
            assertThat(placed.isEmpty()).isTrue();
            assertThat(ground.countOf("IRON_ORE")).isZero();
        }

        @Test
        @DisplayName("it does not fill air, so a vein in a cave lines the walls instead of hanging")
        void airIsNotFilled() {
            FakeGround ground = new FakeGround().fillWith("AIR");

            assertThat(new Veins(ground).place(OreVein.around(CENTRE, 10, 1L), "IRON_ORE").blocks())
                    .isZero();
        }

        @Test
        @DisplayName("one chest in the way costs that block and not the vein")
        void oneObstacle() {
            FakeGround ground = new FakeGround().put(CENTRE, "CHEST");

            Veins.Placed placed = new Veins(ground).place(OreVein.around(CENTRE, 10, 1L), "IRON_ORE");

            assertThat(placed.blocks()).isEqualTo(9);
            assertThat(placed.skipped()).isOne();
            assertThat(ground.materialAt(CENTRE)).isEqualTo("CHEST");
        }

        @Test
        @DisplayName("it stops at the edge of what is loaded rather than pulling the world in")
        void unloadedGroundIsLeftAlone() {
            FakeGround ground = new FakeGround().unload(CENTRE);

            Veins.Placed placed = new Veins(ground).place(OreVein.around(CENTRE, 10, 1L), "IRON_ORE");

            assertThat(placed.skipped()).isGreaterThanOrEqualTo(1);
            assertThat(ground.materialAt(CENTRE)).isEqualTo("STONE");
        }

        @Test
        @DisplayName("ore in deepslate comes out as deepslate ore")
        void theDeepslateForm() {
            // Per block rather than per vein: one that straddles the boundary is ore above and
            // deepslate ore below, which is what the world itself does.
            assertThat(Veins.variantFor("IRON_ORE", "DEEPSLATE")).isEqualTo("DEEPSLATE_IRON_ORE");
            assertThat(Veins.variantFor("IRON_ORE", "STONE")).isEqualTo("IRON_ORE");
            // And only where one exists, or it would place nothing at all.
            assertThat(Veins.variantFor("NETHER_QUARTZ_ORE", "DEEPSLATE"))
                    .isEqualTo("NETHER_QUARTZ_ORE");
            assertThat(Veins.variantFor("DEEPSLATE_IRON_ORE", "DEEPSLATE"))
                    .isEqualTo("DEEPSLATE_IRON_ORE");
        }

        @Test
        @DisplayName("a vein can be laid over an existing one")
        void oresAreReplaceable() {
            // Otherwise placing one in a naturally ore-rich patch leaves holes wherever the world got
            // there first.
            assertThat(Veins.isNatural("COAL_ORE")).isTrue();
            assertThat(Veins.isNatural("DEEPSLATE_DIAMOND_ORE")).isTrue();
            assertThat(Veins.isNatural("CHEST")).isFalse();
            assertThat(Veins.isNatural(null)).isFalse();
        }

        @Test
        @DisplayName("no ore at all places nothing rather than air")
        void nothingToPlace() {
            FakeGround ground = new FakeGround();

            assertThat(new Veins(ground).place(OreVein.around(CENTRE, 5, 1L), "  ").blocks()).isZero();
            assertThat(new Veins(ground).place(OreVein.around(CENTRE, 5, 1L), null).blocks()).isZero();
        }

        @Test
        @DisplayName("the offered ores are all things a vein may replace, so none of them is a trap")
        void everyOfferedOreIsPlaceable() {
            assertThat(Veins.ores()).isNotEmpty();
            assertThat(Veins.ores()).doesNotHaveDuplicates();
        }
    }
}
