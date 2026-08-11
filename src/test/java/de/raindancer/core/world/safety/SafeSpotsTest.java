package de.raindancer.core.world.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether it is safe to put a player somewhere, and where to put them instead.
 *
 * <h2>Why this is worth its own package</h2>
 * Because every teleport in every plugin needs it and nobody writes it properly. A warp set on a
 * platform that has since been mined, a home in a house somebody flooded, a farm world regenerated
 * under somebody's bed — all of them end with a player suffocating in stone or falling into lava, and
 * the plugin that put them there had no idea.
 *
 * <p>All of it against a grid rather than a server: these are the rules, and rules are exactly what a
 * live test is bad at exercising. Being on fire is one line here and a lot of setup on a real server.
 */
@DisplayName("safe spots")
class SafeSpotsTest {

    /** A world made of whatever the test says, and air everywhere else. */
    private static final class Grid implements Blocks {

        private final Map<Spot, BlockKind> blocks = new HashMap<>();
        private final Set<Spot> unloaded = new HashSet<>();
        private final Set<Spot> notGround = new HashSet<>();
        private int lowest = -64;
        private int highest = 320;

        /** Solid, but a tree or a roof rather than the terrain itself — see {@link #isNaturalGround}. */
        Grid notNaturalGround(int x, int y, int z) {
            notGround.add(new Spot("world", x, y, z));
            return this;
        }

        Grid put(int x, int y, int z, BlockKind kind) {
            blocks.put(new Spot("world", x, y, z), kind);
            return this;
        }

        /** A floor of solid blocks across the whole area at this height. */
        Grid floorAt(int y) {
            for (int x = -20; x <= 20; x++) {
                for (int z = -20; z <= 20; z++) {
                    put(x, y, z, BlockKind.SOLID);
                }
            }
            return this;
        }

        Grid column(int x, int z, int fromY, int toY, BlockKind kind) {
            for (int y = fromY; y <= toY; y++) {
                put(x, y, z, kind);
            }
            return this;
        }

        Grid notLoaded(int x, int z) {
            for (int y = lowest; y < highest; y++) {
                unloaded.add(new Spot("world", x, y, z));
            }
            return this;
        }

        @Override
        public BlockKind at(Spot spot) {
            if (spot.y() < lowest || spot.y() >= highest) {
                return BlockKind.UNKNOWN;
            }
            return blocks.getOrDefault(spot, BlockKind.PASSABLE);
        }

        @Override
        public boolean isLoaded(Spot spot) {
            return !unloaded.contains(new Spot(spot.world(), spot.x(), spot.y(), spot.z()));
        }

        @Override
        public int lowestY() {
            return lowest;
        }

        @Override
        public int highestY() {
            return highest;
        }

        /**
         * The real heightmap, computed from what was actually placed — not the default fallback —
         * so a test can tell the fast path from the walking one apart, and so a search seeded from
         * high above finds the same answer whichever path it takes.
         */
        @Override
        public int highestSolidY(int x, int z) {
            for (int y = highest - 1; y >= lowest; y--) {
                if (blocks.getOrDefault(new Spot("world", x, y, z), BlockKind.PASSABLE) == BlockKind.SOLID) {
                    return y;
                }
            }
            return lowest;
        }

        @Override
        public boolean isNaturalGround(Spot spot) {
            return !notGround.contains(spot);
        }
    }

    private static Spot at(int x, int y, int z) {
        return new Spot("world", x, y, z);
    }

    // ------------------------------------------------------------------ is this spot safe

    @Nested
    @DisplayName("judging one spot")
    class Judging {

        @Test
        @DisplayName("solid ground with room to stand is safe")
        void plainGroundIsSafe() {
            SafeSpots safety = new SafeSpots(new Grid().floorAt(63));
            assertThat(safety.check(at(0, 64, 0))).isEqualTo(Danger.NONE);
            assertThat(safety.isSafe(at(0, 64, 0))).isTrue();
        }

        @Test
        @DisplayName("a block where the player's body would be is not safe")
        void suffocatingIsNotSafe() {
            Grid world = new Grid().floorAt(63).put(0, 64, 0, BlockKind.SOLID);
            assertThat(new SafeSpots(world).check(at(0, 64, 0)))
                    .isEqualTo(Danger.INSIDE_A_BLOCK);
        }

        @Test
        @DisplayName("a block where the player's head would be is not safe either")
        void needsTwoBlocksOfRoom() {
            Grid world = new Grid().floorAt(63).put(0, 65, 0, BlockKind.SOLID);
            assertThat(new SafeSpots(world).check(at(0, 64, 0)))
                    .as("a player is two blocks tall; checking only the feet is how somebody ends "
                            + "up with their head in a ceiling")
                    .isEqualTo(Danger.INSIDE_A_BLOCK);
        }

        @Test
        @DisplayName("standing on lava is not safe")
        void lavaBelowIsNotSafe() {
            Grid world = new Grid().floorAt(63).put(0, 63, 0, BlockKind.LAVA);
            assertThat(new SafeSpots(world).check(at(0, 64, 0))).isEqualTo(Danger.LAVA);
        }

        @Test
        @DisplayName("standing in lava is not safe")
        void lavaAtTheFeetIsNotSafe() {
            Grid world = new Grid().floorAt(63).put(0, 64, 0, BlockKind.LAVA);
            assertThat(new SafeSpots(world).check(at(0, 64, 0))).isEqualTo(Danger.LAVA);
        }

        @Test
        @DisplayName("fire, cactus and the rest are not safe")
        void harmfulIsNotSafe() {
            Grid world = new Grid().floorAt(63).put(0, 64, 0, BlockKind.HARMFUL);
            assertThat(new SafeSpots(world).check(at(0, 64, 0))).isEqualTo(Danger.HURTS);
        }

        @Test
        @DisplayName("standing on a cactus is not safe either")
        void harmfulBelowIsNotSafe() {
            Grid world = new Grid().floorAt(63).put(0, 63, 0, BlockKind.HARMFUL);
            assertThat(new SafeSpots(world).check(at(0, 64, 0))).isEqualTo(Danger.HURTS);
        }

        @Test
        @DisplayName("underwater is refused by default, and allowed if you say so")
        void waterIsAChoice() {
            Grid world = new Grid().floorAt(63).put(0, 64, 0, BlockKind.WATER);
            assertThat(new SafeSpots(world).check(at(0, 64, 0)))
                    .as("survivable is not the same as somewhere to arrive")
                    .isEqualTo(Danger.UNDERWATER);

            SafeSpots swimming = new SafeSpots(world);
            swimming.allowWater(true);
            assertThat(swimming.check(at(0, 64, 0))).isEqualTo(Danger.NONE);
        }

        @Test
        @DisplayName("inside a portal is not safe, because it moves you again")
        void portalIsNotSafe() {
            Grid world = new Grid().floorAt(63).put(0, 64, 0, BlockKind.PORTAL);
            assertThat(new SafeSpots(world).check(at(0, 64, 0))).isEqualTo(Danger.PORTAL);
        }

        @Test
        @DisplayName("nothing underneath at all is not safe")
        void noGroundIsNotSafe() {
            assertThat(new SafeSpots(new Grid()).check(at(0, 100, 0)))
                    .isEqualTo(Danger.NOTHING_BELOW);
        }

        @Test
        @DisplayName("a drop far enough to hurt is not safe")
        void aLongDropIsNotSafe() {
            Grid world = new Grid().floorAt(10);
            assertThat(new SafeSpots(world).check(at(0, 60, 0)))
                    .as("landing is part of arriving")
                    .isEqualTo(Danger.A_LONG_WAY_DOWN);
        }

        @Test
        @DisplayName("a short drop is fine")
        void aShortDropIsFine() {
            Grid world = new Grid().floorAt(61);
            assertThat(new SafeSpots(world).check(at(0, 63, 0))).isEqualTo(Danger.NONE);
        }

        @Test
        @DisplayName("outside the world is not safe")
        void outsideTheWorldIsNotSafe() {
            SafeSpots safety = new SafeSpots(new Grid());
            assertThat(safety.check(at(0, -100, 0))).isEqualTo(Danger.OUT_OF_THE_WORLD);
            assertThat(safety.check(at(0, 400, 0))).isEqualTo(Danger.OUT_OF_THE_WORLD);
        }

        @Test
        @DisplayName("a chunk nobody has loaded is not called safe")
        void unloadedIsNotSafe() {
            Grid world = new Grid().floorAt(63).notLoaded(0, 0);
            assertThat(new SafeSpots(world).check(at(0, 64, 0)))
                    .as("a spot that could not be checked is not a spot that is safe, and saying "
                            + "which of the two it is beats guessing")
                    .isEqualTo(Danger.NOT_LOADED);
        }

        @Test
        @DisplayName("standing exactly on the floor of the world is not held up by nothing")
        void theVoidBelowTheWorldIsNotStandable() {
            // A world whose lowest layer is open air rather than bedrock — a void world, or an
            // ordinary one somebody has mined right down to the floor. There is nothing at all one
            // block below the world's own lowest coordinate; that is not "cannot be checked, so
            // assume solid", it is the one place that assumption is actually wrong.
            Grid world = new Grid();
            Spot floor = at(0, world.lowestY(), 0);
            assertThat(new SafeSpots(world).check(floor))
                    .as("nothing below the bottom of the world is exactly as unsafe as nothing "
                            + "below anywhere else")
                    .isEqualTo(Danger.NOTHING_BELOW);
        }
    }

    // ------------------------------------------------------------------ finding a better one

    @Nested
    @DisplayName("finding somewhere better")
    class Finding {

        @Test
        @DisplayName("a spot that is already safe is left alone")
        void keepsASafeSpot() {
            SafeSpots safety = new SafeSpots(new Grid().floorAt(63));
            assertThat(safety.nearestSafe(at(0, 64, 0), 8)).contains(at(0, 64, 0));
        }

        @Test
        @DisplayName("somebody inside a block is lifted out of it")
        void looksUpwards() {
            Grid world = new Grid().floorAt(63).column(0, 0, 64, 70, BlockKind.SOLID);
            Optional<Spot> found = new SafeSpots(world).nearestSafe(at(0, 64, 0), 8);

            assertThat(found).isPresent();
            assertThat(found.orElseThrow().y())
                    .as("out of the stone, onto the top of it")
                    .isEqualTo(71);
        }

        @Test
        @DisplayName("somebody in mid-air is put down on the ground")
        void looksDownwards() {
            Grid world = new Grid().floorAt(63);
            assertThat(new SafeSpots(world).nearestSafe(at(0, 80, 0), 32))
                    .contains(at(0, 64, 0));
        }

        @Test
        @DisplayName("when the column is hopeless it looks sideways")
        void looksSideways() {
            Grid world = new Grid().floorAt(63);
            // A pillar of lava filling the whole column at 0,0 — nothing in it is ever safe.
            world.column(0, 0, 63, 90, BlockKind.LAVA);

            Optional<Spot> found = new SafeSpots(world).nearestSafe(at(0, 64, 0), 8);
            assertThat(found).isPresent();
            assertThat(found.orElseThrow().x() != 0 || found.orElseThrow().z() != 0).isTrue();
            assertThat(new SafeSpots(world).isSafe(found.orElseThrow())).isTrue();
        }

        @Test
        @DisplayName("it finds the nearest one, not just any one")
        void prefersTheNearest() {
            Grid world = new Grid().floorAt(63);
            world.column(0, 0, 63, 80, BlockKind.LAVA);

            Spot found = new SafeSpots(world).nearestSafe(at(0, 64, 0), 16).orElseThrow();
            assertThat(found.distanceSquaredTo(at(0, 64, 0)))
                    .as("dropping somebody two hundred blocks away is not a rescue")
                    .isLessThanOrEqualTo(at(1, 64, 1).distanceSquaredTo(at(0, 64, 0)) + 4);
        }

        @Test
        @DisplayName("nowhere safe within reach is an honest empty, not a guess")
        void givesUpHonestly() {
            Grid world = new Grid();
            // Lava everywhere there is any ground at all.
            for (int x = -20; x <= 20; x++) {
                for (int z = -20; z <= 20; z++) {
                    world.put(x, 63, z, BlockKind.LAVA);
                    world.put(x, 64, z, BlockKind.LAVA);
                }
            }
            assertThat(new SafeSpots(world).nearestSafe(at(0, 64, 0), 6))
                    .as("a plugin that gets a spot back assumes it is safe; a guess here is "
                            + "somebody in lava")
                    .isEmpty();
        }

        @Test
        @DisplayName("it will not wander into chunks nobody has loaded")
        void staysInLoadedChunks() {
            Grid world = new Grid().floorAt(63);
            world.column(0, 0, 63, 80, BlockKind.LAVA);
            for (int x = -20; x <= 20; x++) {
                for (int z = -20; z <= 20; z++) {
                    if (x != 0 || z != 0) {
                        world.notLoaded(x, z);
                    }
                }
            }
            assertThat(new SafeSpots(world).nearestSafe(at(0, 64, 0), 16))
                    .as("generating chunks to answer 'is this safe' turns a teleport into a "
                            + "freeze for everybody on the server")
                    .isEmpty();
        }

        @Test
        @DisplayName("a radius of nothing checks only the spot given")
        void zeroRadius() {
            Grid world = new Grid().floorAt(63).put(0, 64, 0, BlockKind.SOLID);
            assertThat(new SafeSpots(world).nearestSafe(at(0, 64, 0), 0)).isEmpty();
        }
    }

    // ------------------------------------------------------------------ the blocks around it

    /**
     * Looking at what is next to a spot, not only at the spot itself.
     *
     * <p>Off by default because it is a judgement rather than a fact: a warp with a decorative
     * campfire beside it is fine, and a plugin that refused it would be wrong. But standing one block
     * from a lava lake is somewhere a player arrives, turns around and dies in, having been told the
     * spot was safe — so a caller who wants the stricter question has to be able to ask it.
     */
    @Nested
    @DisplayName("checking the blocks around it")
    class Surroundings {

        @Test
        @DisplayName("it is off unless asked for")
        void offByDefault() {
            Grid world = new Grid().floorAt(63).put(1, 64, 0, BlockKind.LAVA);
            SafeSpots safety = new SafeSpots(world);

            assertThat(safety.surroundingRadius()).isZero();
            assertThat(safety.check(at(0, 64, 0)))
                    .as("a decorative campfire beside a warp is not a reason to refuse it")
                    .isEqualTo(Danger.NONE);
        }

        @Test
        @DisplayName("lava next door is found when it is asked for")
        void findsLavaNextDoor() {
            Grid world = new Grid().floorAt(63).put(1, 64, 0, BlockKind.LAVA);
            SafeSpots safety = new SafeSpots(world);
            safety.surroundingRadius(1);

            assertThat(safety.check(at(0, 64, 0))).isEqualTo(Danger.LAVA_NEARBY);
        }

        @Test
        @DisplayName("a diagonal neighbour counts too")
        void findsItDiagonally() {
            Grid world = new Grid().floorAt(63).put(1, 64, 1, BlockKind.LAVA);
            SafeSpots safety = new SafeSpots(world);
            safety.surroundingRadius(1);

            assertThat(safety.check(at(0, 64, 0)))
                    .as("lava does not respect the four compass directions")
                    .isEqualTo(Danger.LAVA_NEARBY);
        }

        @Test
        @DisplayName("something at head height counts too")
        void looksAtHeadHeight() {
            Grid world = new Grid().floorAt(63).put(1, 65, 0, BlockKind.LAVA);
            SafeSpots safety = new SafeSpots(world);
            safety.surroundingRadius(1);

            assertThat(safety.check(at(0, 64, 0))).isEqualTo(Danger.LAVA_NEARBY);
        }

        @Test
        @DisplayName("fire and cactus next door are reported as what they are")
        void tellsThemApart() {
            Grid world = new Grid().floorAt(63).put(1, 64, 0, BlockKind.HARMFUL);
            SafeSpots safety = new SafeSpots(world);
            safety.surroundingRadius(1);

            assertThat(safety.check(at(0, 64, 0)))
                    .as("'there is lava nearby' and 'there is a cactus nearby' are different "
                            + "things to be told")
                    .isEqualTo(Danger.HURTS_NEARBY);
        }

        @Test
        @DisplayName("lava wins over a cactus when both are around")
        void reportsTheWorstOne() {
            Grid world = new Grid().floorAt(63)
                    .put(1, 64, 0, BlockKind.HARMFUL)
                    .put(-1, 64, 0, BlockKind.LAVA);
            SafeSpots safety = new SafeSpots(world);
            safety.surroundingRadius(1);

            assertThat(safety.check(at(0, 64, 0))).isEqualTo(Danger.LAVA_NEARBY);
        }

        @Test
        @DisplayName("a wider radius looks further")
        void radiusWidens() {
            Grid world = new Grid().floorAt(63).put(3, 64, 0, BlockKind.LAVA);
            SafeSpots near = new SafeSpots(world);
            near.surroundingRadius(1);
            SafeSpots far = new SafeSpots(world);
            far.surroundingRadius(3);

            assertThat(near.check(at(0, 64, 0))).isEqualTo(Danger.NONE);
            assertThat(far.check(at(0, 64, 0))).isEqualTo(Danger.LAVA_NEARBY);
        }

        @Test
        @DisplayName("what is wrong with the spot itself is still said first")
        void theSpotItselfComesFirst() {
            Grid world = new Grid().floorAt(63)
                    .put(0, 64, 0, BlockKind.SOLID)
                    .put(1, 64, 0, BlockKind.LAVA);
            SafeSpots safety = new SafeSpots(world);
            safety.surroundingRadius(1);

            assertThat(safety.check(at(0, 64, 0)))
                    .as("being inside a block is the more useful thing to hear about")
                    .isEqualTo(Danger.INSIDE_A_BLOCK);
        }

        @Test
        @DisplayName("a search avoids spots with something nasty beside them")
        void searchingRespectsIt() {
            Grid world = new Grid().floorAt(63)
                    .column(0, 0, 64, 70, BlockKind.SOLID)
                    .put(1, 71, 0, BlockKind.LAVA);
            SafeSpots safety = new SafeSpots(world);
            safety.surroundingRadius(1);

            Spot found = safety.nearestSafe(at(0, 64, 0), 8).orElseThrow();
            assertThat(found)
                    .as("a rescue that puts somebody next to lava has not rescued them")
                    .isNotEqualTo(at(0, 71, 0));
            assertThat(safety.isSafe(found)).isTrue();
        }

        @Test
        @DisplayName("an unloaded neighbour is not guessed at either way")
        void unloadedNeighbourIsIgnored() {
            Grid world = new Grid().floorAt(63).notLoaded(1, 0);
            SafeSpots safety = new SafeSpots(world);
            safety.surroundingRadius(1);

            assertThat(safety.check(at(0, 64, 0)))
                    .as("refusing every spot next to an unloaded chunk would refuse most of the "
                            + "edge of the loaded world")
                    .isEqualTo(Danger.NONE);
        }
    }

    @Nested
    @DisplayName("landing on the terrain itself, not on whatever is stacked on top of it")
    class NaturalGroundOnly {

        @Test
        @DisplayName("off by default: a solid platform is exactly as good as the ground under it")
        void offByDefault() {
            // A wooden platform floating above ordinary ground.
            Grid world = new Grid().floorAt(63)
                    .put(0, 70, 0, BlockKind.SOLID).notNaturalGround(0, 70, 0);
            SafeSpots safety = new SafeSpots(world);

            assertThat(safety.isNaturalGroundOnly()).isFalse();
            assertThat(safety.nearestSafe(at(0, 80, 0), 1)).contains(at(0, 71, 0));
        }

        @Test
        @DisplayName("a platform that is not the terrain is refused once this is turned on")
        void refusesWhatIsNotTheTerrain() {
            // A wooden platform floating above ordinary ground — a tree canopy, a roof, a bridge.
            Grid world = new Grid().floorAt(63)
                    .put(0, 70, 0, BlockKind.SOLID).notNaturalGround(0, 70, 0);
            SafeSpots safety = new SafeSpots(world);
            safety.naturalGroundOnly(true);

            assertThat(safety.nearestSafe(at(0, 80, 0), 1))
                    .as("the platform is a perfectly good standing spot by every other rule, and is "
                            + "refused — and the search does not stop there, it keeps going down "
                            + "to the real ground instead")
                    .contains(at(0, 64, 0));
            assertThat(safety.nearestConsistentHeight(at(0, 100, 0), 0, 8))
                    .as("passed straight over, all the way down to the real ground")
                    .contains(at(0, 64, 0));
        }

        @Test
        @DisplayName("ordinary terrain is unaffected either way")
        void ordinaryGroundIsUnaffected() {
            Grid world = new Grid().floorAt(63);
            SafeSpots safety = new SafeSpots(world);
            safety.naturalGroundOnly(true);

            assertThat(safety.nearestSafe(at(0, 80, 0), 1)).contains(at(0, 64, 0));
        }

        @Test
        @DisplayName("standing in water is judged as water, never as ground")
        void waterIsNeverJudgedAsGround() {
            Grid world = new Grid().floorAt(63).put(0, 64, 0, BlockKind.WATER);
            SafeSpots safety = new SafeSpots(world);
            safety.allowWater(true);
            safety.naturalGroundOnly(true);

            assertThat(safety.isSafe(at(0, 64, 0)))
                    .as("naturalGroundOnly asks a question about solid ground; water was never "
                            + "solid to begin with and answers to allowWater alone")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("a spot whose surroundings agree with it")
    class ConsistentHeight {

        @Test
        @DisplayName("plain nearestSafe lands at the bottom of a ravine directly below the search")
        void theBugThisFixes() {
            // Ordinary ground at 64 everywhere, except one column carved out down to a floor at 31.
            Grid world = new Grid().floorAt(63)
                    .column(5, 5, 30, 63, BlockKind.PASSABLE)
                    .put(5, 30, 5, BlockKind.SOLID);

            Spot found = new SafeSpots(world).nearestSafe(at(5, 100, 5), 8).orElseThrow();

            assertThat(found)
                    .as("the exact column is checked first and the ravine floor is a valid standing "
                            + "spot, so the plain search stops right there")
                    .isEqualTo(at(5, 31, 5));
        }

        @Test
        @DisplayName("the consistent search refuses that same ravine and finds the surface instead")
        void findsTheSurfaceInstead() {
            Grid world = new Grid().floorAt(63)
                    .column(5, 5, 30, 63, BlockKind.PASSABLE)
                    .put(5, 30, 5, BlockKind.SOLID);

            Spot found = new SafeSpots(world).nearestConsistentHeight(at(5, 100, 5), 8, 1)
                    .orElseThrow();

            assertThat(found.y())
                    .as("the ravine floor's neighbours are all thirty-odd blocks higher, so it fails "
                            + "the agreement check and the search moves on to ordinary ground")
                    .isEqualTo(64);
        }

        @Test
        @DisplayName("ordinary gentle unevenness within the tolerance is still accepted")
        void gentleUnevennessIsFine() {
            // A one-block step, not a hole: (5,5) sits a block lower than its neighbours.
            Grid world = new Grid().floorAt(63).put(5, 63, 5, BlockKind.PASSABLE)
                    .put(5, 62, 5, BlockKind.SOLID);

            Spot found = new SafeSpots(world).nearestConsistentHeight(at(5, 100, 5), 8, 1)
                    .orElseThrow();

            assertThat(found).isEqualTo(at(5, 63, 5));
        }

        @Test
        @DisplayName("a step bigger than the tolerance is refused, just like the deep ravine")
        void aStepBiggerThanToleranceIsRefused() {
            Grid world = new Grid().floorAt(63).put(5, 63, 5, BlockKind.PASSABLE)
                    .put(5, 62, 5, BlockKind.PASSABLE)
                    .put(5, 61, 5, BlockKind.SOLID);

            Spot found = new SafeSpots(world).nearestConsistentHeight(at(5, 100, 5), 8, 1)
                    .orElseThrow();

            assertThat(found.y())
                    .as("two blocks lower than every neighbour is outside a tolerance of one")
                    .isEqualTo(64);
        }

        @Test
        @DisplayName("uniformly rugged terrain falls back to the plain search rather than refusing")
        void fallsBackWhenNothingAgrees() {
            // Every column at a different height from its neighbours — nothing will ever agree.
            Grid world = new Grid();
            for (int x = -5; x <= 5; x++) {
                for (int z = -5; z <= 5; z++) {
                    world.put(x, 60 + ((x + z) % 3 == 0 ? 4 : 0), z, BlockKind.SOLID);
                }
            }

            Optional<Spot> found = new SafeSpots(world).nearestConsistentHeight(at(0, 100, 0), 4, 0);

            assertThat(found)
                    .as("better to land somewhere than to refuse a request just because the terrain "
                            + "is rugged everywhere")
                    .isPresent();
        }

        @Test
        @DisplayName("a null spot is refused outright, same as every other search here")
        void nullIsRefused() {
            assertThat(new SafeSpots(new Grid()).nearestConsistentHeight(null, 8, 1)).isEmpty();
        }

        /** Counts every block read, so a search's cost can be asserted rather than timed. */
        private static final class CountingBlocks implements Blocks {
            private final Blocks delegate;
            private int reads;

            CountingBlocks(Blocks delegate) {
                this.delegate = delegate;
            }

            @Override
            public BlockKind at(Spot spot) {
                reads++;
                return delegate.at(spot);
            }

            @Override
            public boolean isLoaded(Spot spot) {
                return delegate.isLoaded(spot);
            }

            @Override
            public int lowestY() {
                return delegate.lowestY();
            }

            @Override
            public int highestY() {
                return delegate.highestY();
            }

            @Override
            public int highestSolidY(int x, int z) {
                return delegate.highestSolidY(x, z);
            }

            @Override
            public boolean isNaturalGround(Spot spot) {
                return delegate.isNaturalGround(spot);
            }
        }

        @Test
        @DisplayName("checking a neighbour's height never rescans the whole world height for it")
        void neighbourChecksAreBounded() {
            // A deep world (the default -64..320 the Grid fixture already uses) with an isolated
            // ravine at the exact search origin, dropped from high above — precisely the shape a
            // scattered random-teleport arrival produces, and precisely the shape that made the
            // first version of this search take the better part of a minute in practice. Radius 2
            // is enough rings to exercise several agreement checks — including ones next to the
            // ravine that correctly disagree — without the *unrelated* per-ring base search (the
            // part this fix does not touch) dominating the count and hiding a regression in it.
            Grid world = new Grid().floorAt(63)
                    .column(0, 0, 30, 63, BlockKind.PASSABLE)
                    .put(0, 30, 0, BlockKind.SOLID);
            CountingBlocks counted = new CountingBlocks(world);
            SafeSpots safety = new SafeSpots(counted);

            Spot found = safety.nearestConsistentHeight(at(0, 300, 0), 2, 1).orElseThrow();

            assertThat(found.y())
                    .as("still finds the real surface, not the ravine floor")
                    .isEqualTo(64);
            // Measured at 862 for this exact scenario once both the neighbour window and the
            // heightmap-seeded primary search were in place — down from just over 190,000 with
            // either one missing. This bound sits comfortably above that and nowhere near either
            // of the ways it used to regress: a neighbour check that rescans the whole world height,
            // or a primary search that never uses the heightmap at all.
            assertThat(counted.reads)
                    .as("a neighbour check that rescans the whole world height per candidate, a "
                            + "primary search that ignores the heightmap, or a fallback that "
                            + "repeats the whole search, is the bug this test exists to catch")
                    .isLessThan(5_000);
        }

        @Test
        @DisplayName("a missed heightmap window falls back to the walk near the surface, not the sky")
        void fallbackStartsNearTheSurfaceNotTheSky() {
            // A heightmap that lies: it claims the surface is at 90 — a leaf canopy, say — but
            // nothing is actually standable anywhere near there, so the window this class checks
            // first comes back empty every time. Real ground is a floor at 63, dozens of blocks
            // below. Before this fix, the fallback walk that follows a missed window started
            // wherever the column was originally pointed — here, the sky at 300 — and re-introduced
            // the exact quadratic cost the heightmap seeding exists to avoid. Fixed, it starts from
            // the heightmap's own answer instead, wrong as that answer turned out to be, which is
            // still dramatically closer to the real ground than the sky was.
            Grid world = new Grid().floorAt(63);
            Blocks lyingAboutTheSurface = new Blocks() {
                @Override
                public BlockKind at(Spot spot) {
                    return world.at(spot);
                }

                @Override
                public boolean isLoaded(Spot spot) {
                    return world.isLoaded(spot);
                }

                @Override
                public int lowestY() {
                    return world.lowestY();
                }

                @Override
                public int highestY() {
                    return world.highestY();
                }

                @Override
                public int highestSolidY(int x, int z) {
                    return 90;
                }
            };
            CountingBlocks counted = new CountingBlocks(lyingAboutTheSurface);

            Spot found = new SafeSpots(counted).nearestConsistentHeight(at(0, 300, 0), 0, 1)
                    .orElseThrow();

            assertThat(found).as("still finds the real floor").isEqualTo(at(0, 64, 0));
            assertThat(counted.reads)
                    .as("starting the fallback from the sky at 300 rather than the heightmap's own "
                            + "(wrong) answer of 90 costs many times this for the identical scenario")
                    .isLessThan(3_000);
        }
    }
}
