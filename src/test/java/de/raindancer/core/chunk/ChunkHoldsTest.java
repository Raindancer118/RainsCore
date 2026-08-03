package de.raindancer.core.chunk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who is keeping which chunks loaded, and when they can go.
 *
 * <h2>Why this is not just a set</h2>
 * Because two plugins can want the same chunk and only one of them is done with it. A ghast line
 * keeping its landing pad loaded and a farm world keeping its spawn loaded may be the same chunk,
 * and the ghast line finishing must not unload it under the farm world. Counting who is holding what
 * is the whole job, and getting it wrong is either a chunk that unloads while somebody is standing
 * in it or one that never unloads again.
 *
 * <p>The second failure is the one that bites in practice: a force-loaded chunk is written into the
 * world's own data and survives a restart, so a plugin that forgets to let go leaves a server ticking
 * chunks nobody can account for, for ever, with nothing in any log to say why.
 */
@DisplayName("chunk holds")
class ChunkHoldsTest {

    /** What would have been asked of the server, instead of a server. */
    private static final class Recorder implements ChunkLoader {

        private final Set<ChunkAt> kept = new LinkedHashSet<>();
        private final List<ChunkAt> released = new ArrayList<>();
        private final List<ChunkAt> loaded = new ArrayList<>();

        @Override
        public boolean isLoaded(ChunkAt chunk) {
            return kept.contains(chunk);
        }

        @Override
        public java.util.concurrent.CompletableFuture<Boolean> load(ChunkAt chunk) {
            loaded.add(chunk);
            return java.util.concurrent.CompletableFuture.completedFuture(true);
        }

        @Override
        public void keepLoaded(ChunkAt chunk, boolean keep) {
            if (keep) {
                kept.add(chunk);
            } else {
                kept.remove(chunk);
                released.add(chunk);
            }
        }
    }

    private final Recorder server = new Recorder();

    private static ChunkAt chunk(int x, int z) {
        return new ChunkAt("world", x, z);
    }

    // ------------------------------------------------------------------ holding

    @Nested
    @DisplayName("keeping one loaded")
    class Holding {

        @Test
        @DisplayName("asking for one holds it")
        void holdsIt() {
            ChunkHolds holds = new ChunkHolds(server);
            assertThat(holds.keep("GhastLines", chunk(0, 0))).isTrue();

            assertThat(server.kept).containsExactly(chunk(0, 0));
            assertThat(holds.isHeld(chunk(0, 0))).isTrue();
            assertThat(holds.heldBy("GhastLines")).containsExactly(chunk(0, 0));
        }

        @Test
        @DisplayName("asking twice is not two holds")
        void isIdempotentPerOwner() {
            ChunkHolds holds = new ChunkHolds(server);
            holds.keep("GhastLines", chunk(0, 0));
            assertThat(holds.keep("GhastLines", chunk(0, 0)))
                    .as("a plugin calling this on every join must not need to count its own calls")
                    .isFalse();

            holds.release("GhastLines", chunk(0, 0));
            assertThat(server.kept)
                    .as("one release from the only holder must actually let it go")
                    .isEmpty();
        }

        @Test
        @DisplayName("one holder letting go does not drop somebody else's chunk")
        void countsHolders() {
            ChunkHolds holds = new ChunkHolds(server);
            holds.keep("GhastLines", chunk(0, 0));
            holds.keep("FarmWorld", chunk(0, 0));

            holds.release("GhastLines", chunk(0, 0));
            assertThat(server.kept)
                    .as("unloading a chunk somebody else is standing in is the failure this "
                            + "whole class exists to prevent")
                    .containsExactly(chunk(0, 0));

            holds.release("FarmWorld", chunk(0, 0));
            assertThat(server.kept).isEmpty();
        }

        @Test
        @DisplayName("releasing something nobody held is not an error")
        void releasingNothing() {
            ChunkHolds holds = new ChunkHolds(server);
            assertThat(holds.release("GhastLines", chunk(0, 0))).isFalse();
            assertThat(server.released).isEmpty();
        }

        @Test
        @DisplayName("a plugin going away lets go of everything it held")
        void releasesEverythingOfOnePlugin() {
            ChunkHolds holds = new ChunkHolds(server);
            holds.keep("GhastLines", chunk(0, 0));
            holds.keep("GhastLines", chunk(1, 0));
            holds.keep("FarmWorld", chunk(5, 5));

            assertThat(holds.releaseAllFrom("GhastLines")).isEqualTo(2);
            assertThat(server.kept)
                    .as("a force-loaded chunk is written into the world and survives a restart; "
                            + "a plugin that forgets leaves it ticking for ever")
                    .containsExactly(chunk(5, 5));
        }

        @Test
        @DisplayName("everything can be let go at once, for a shutdown")
        void releasesEverything() {
            ChunkHolds holds = new ChunkHolds(server);
            holds.keep("GhastLines", chunk(0, 0));
            holds.keep("FarmWorld", chunk(5, 5));

            assertThat(holds.releaseAll()).isEqualTo(2);
            assertThat(server.kept).isEmpty();
            assertThat(holds.all()).isEmpty();
        }

        @Test
        @DisplayName("it says who is holding what, so a leak has a name on it")
        void namesTheHolders() {
            ChunkHolds holds = new ChunkHolds(server);
            holds.keep("GhastLines", chunk(0, 0));
            holds.keep("FarmWorld", chunk(0, 0));

            assertThat(holds.holdersOf(chunk(0, 0)))
                    .containsExactlyInAnyOrder("GhastLines", "FarmWorld");
            assertThat(holds.all()).containsExactly(chunk(0, 0));
        }

        @Test
        @DisplayName("a hold with no owner is refused rather than becoming untraceable")
        void refusesAnonymousHolds() {
            ChunkHolds holds = new ChunkHolds(server);
            assertThat(holds.keep(null, chunk(0, 0))).isFalse();
            assertThat(holds.keep("  ", chunk(0, 0)))
                    .as("a permanently loaded chunk nobody's name is on is a leak nobody can find")
                    .isFalse();
            assertThat(server.kept).isEmpty();
        }
    }

    // ------------------------------------------------------------------ just for a moment

    @Nested
    @DisplayName("loading one for a moment")
    class ForAMoment {

        @Test
        @DisplayName("a chunk that is wanted briefly is loaded and not held")
        void loadsWithoutHolding() {
            ChunkHolds holds = new ChunkHolds(server);
            assertThat(holds.forAMoment(chunk(2, 2)).join()).isTrue();

            assertThat(server.loaded).containsExactly(chunk(2, 2));
            assertThat(holds.isHeld(chunk(2, 2)))
                    .as("a look is not a hold; making every check permanent is how a server ends "
                            + "up ticking the whole map")
                    .isFalse();
        }

        @Test
        @DisplayName("one that is already loaded is not loaded again")
        void skipsWhatIsAlreadyThere() {
            ChunkHolds holds = new ChunkHolds(server);
            holds.keep("FarmWorld", chunk(2, 2));

            assertThat(holds.forAMoment(chunk(2, 2)).join()).isTrue();
            assertThat(server.loaded).isEmpty();
        }
    }

    // ------------------------------------------------------------------ what it is about

    @Nested
    @DisplayName("a chunk position")
    class Positions {

        @Test
        @DisplayName("it is worked out from block coordinates the way the game does")
        void fromBlocks() {
            assertThat(ChunkAt.ofBlock("world", 0, 0)).isEqualTo(chunk(0, 0));
            assertThat(ChunkAt.ofBlock("world", 15, 15)).isEqualTo(chunk(0, 0));
            assertThat(ChunkAt.ofBlock("world", 16, 16)).isEqualTo(chunk(1, 1));
            assertThat(ChunkAt.ofBlock("world", -1, -1))
                    .as("dividing a negative by sixteen rounds towards zero, which puts everything "
                            + "west of spawn in the wrong chunk — the classic one-line bug here")
                    .isEqualTo(chunk(-1, -1));
            assertThat(ChunkAt.ofBlock("world", -16, -16)).isEqualTo(chunk(-1, -1));
            assertThat(ChunkAt.ofBlock("world", -17, -17)).isEqualTo(chunk(-2, -2));
        }

        @Test
        @DisplayName("two chunks with the same numbers in different worlds are different chunks")
        void worldMatters() {
            assertThat(new ChunkAt("world", 0, 0)).isNotEqualTo(new ChunkAt("nether", 0, 0));
        }
    }
}
