package de.raindancer.core.world.chunk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Walking a region and making sure every chunk in it has been generated at least once, throttled
 * rather than all at once.
 *
 * <h2>Why this exists</h2>
 * {@link ChunkHolds#forAMoment} already brings a chunk in, generating it if it has never existed —
 * but on demand, in the one request that happened to need it. A module that wants a whole region
 * already generated <em>before</em> anybody asks — the rtp-module's scatter radius, a farm world
 * right after it is regenerated — needs the same {@link ChunkLoader#load} called across every chunk
 * in that region, spread out so it does not try to generate them all in the same tick. This is that,
 * kept generic: it knows nothing about "rtp" or "farm world", only "these chunks, this many at a
 * time".
 *
 * <h2>Why it is a queue with a step, not a loop</h2>
 * A loop that ran until done would be a loop a module calls once and forgets, which is exactly the
 * "generate everything right now" behaviour this is meant to avoid. A step is what a scheduler calls
 * once a tick, so pacing is the caller's decision and this only ever does one batch at a time.
 */
@DisplayName("chunk pregeneration")
class ChunkPregenTest {

    /** What would have been asked of the server, instead of a server. */
    private static final class Recorder implements ChunkLoader {
        private final List<ChunkAt> loaded = new ArrayList<>();

        @Override
        public boolean isLoaded(ChunkAt chunk) {
            return loaded.contains(chunk);
        }

        @Override
        public CompletableFuture<Boolean> load(ChunkAt chunk) {
            loaded.add(chunk);
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public void keepLoaded(ChunkAt chunk, boolean keep) {
            throw new AssertionError("pregeneration must never force-load — a look is not a hold");
        }
    }

    private final Recorder server = new Recorder();

    private static List<ChunkAt> square(int radius) {
        List<ChunkAt> chunks = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                chunks.add(new ChunkAt("world", x, z));
            }
        }
        return chunks;
    }

    @Nested
    @DisplayName("stepping through the region")
    class Stepping {

        @Test
        @DisplayName("a step asks the loader for exactly the batch size, no more")
        void stepsInBatches() {
            ChunkPregen pregen = new ChunkPregen(server, square(1)); // 9 chunks
            int done = pregen.step(4).join();

            assertThat(done).isEqualTo(4);
            assertThat(server.loaded).hasSize(4);
        }

        @Test
        @DisplayName("stepping again continues where the last step left off, never repeating a chunk")
        void continuesWithoutRepeating() {
            ChunkPregen pregen = new ChunkPregen(server, square(1)); // 9 chunks
            pregen.step(4).join();
            pregen.step(4).join();

            assertThat(server.loaded).hasSize(8);
            assertThat(server.loaded).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("a step larger than what remains only asks for what remains")
        void finalStepIsPartial() {
            ChunkPregen pregen = new ChunkPregen(server, square(1)); // 9 chunks
            pregen.step(4).join();
            pregen.step(4).join();
            int last = pregen.step(4).join();

            assertThat(last).isEqualTo(1);
            assertThat(server.loaded).hasSize(9);
        }

        @Test
        @DisplayName("once every chunk is generated, a step does nothing and reports done")
        void reportsDoneOnceExhausted() {
            ChunkPregen pregen = new ChunkPregen(server, square(1)); // 9 chunks
            pregen.step(20).join();

            assertThat(pregen.progress().done()).isEqualTo(9);
            assertThat(pregen.progress().state()).isEqualTo(ChunkPregen.State.DONE);

            int afterDone = pregen.step(4).join();
            assertThat(afterDone).isZero();
            assertThat(server.loaded).hasSize(9);
        }

        @Test
        @DisplayName("never force-loads what it walks — a generation pass is not a hold")
        void neverForceLoads() {
            ChunkPregen pregen = new ChunkPregen(server, square(1));
            // Recorder throws if keepLoaded is ever called; reaching the assertion at all is the proof.
            pregen.step(9).join();
            assertThat(server.loaded).hasSize(9);
        }
    }

    @Nested
    @DisplayName("pausing and cancelling")
    class PausingAndCancelling {

        @Test
        @DisplayName("a paused pregen answers every step with zero, and touches nothing")
        void pausedStepsDoNothing() {
            ChunkPregen pregen = new ChunkPregen(server, square(1));
            pregen.pause();

            assertThat(pregen.step(4).join()).isZero();
            assertThat(server.loaded).isEmpty();
            assertThat(pregen.progress().state()).isEqualTo(ChunkPregen.State.PAUSED);
        }

        @Test
        @DisplayName("resuming carries on from wherever it paused, not from the start")
        void resumesWhereItLeftOff() {
            ChunkPregen pregen = new ChunkPregen(server, square(1));
            pregen.step(4).join();
            pregen.pause();
            pregen.step(4).join();
            pregen.resume();
            pregen.step(4).join();

            assertThat(server.loaded).hasSize(8);
        }

        @Test
        @DisplayName("a cancelled pregen is done for good, not merely paused")
        void cancelIsPermanent() {
            ChunkPregen pregen = new ChunkPregen(server, square(1));
            pregen.step(4).join();
            pregen.cancel();

            assertThat(pregen.step(4).join()).isZero();
            assertThat(pregen.progress().state()).isEqualTo(ChunkPregen.State.DONE);
            pregen.resume();
            assertThat(pregen.step(4).join())
                    .as("resuming a cancelled run must not bring the remaining chunks back")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("progress")
    class Progress {

        @Test
        @DisplayName("reports total and done honestly, and starts running")
        void reportsTotals() {
            ChunkPregen pregen = new ChunkPregen(server, square(1));
            assertThat(pregen.progress().total()).isEqualTo(9);
            assertThat(pregen.progress().done()).isZero();
            assertThat(pregen.progress().state()).isEqualTo(ChunkPregen.State.RUNNING);

            pregen.step(3).join();
            assertThat(pregen.progress().done()).isEqualTo(3);
        }

        @Test
        @DisplayName("duplicate chunks in the requested region are only ever generated once")
        void dedupesTheRegion() {
            ChunkAt same = new ChunkAt("world", 0, 0);
            ChunkPregen pregen = new ChunkPregen(server, List.of(same, same, same));

            assertThat(pregen.progress().total()).isEqualTo(1);
            assertThat(pregen.step(10).join()).isEqualTo(1);
        }

        @Test
        @DisplayName("an empty region is done immediately")
        void emptyRegionIsDoneImmediately() {
            ChunkPregen pregen = new ChunkPregen(server, List.of());
            assertThat(pregen.progress().state()).isEqualTo(ChunkPregen.State.DONE);
            assertThat(pregen.step(4).join()).isZero();
        }
    }
}
