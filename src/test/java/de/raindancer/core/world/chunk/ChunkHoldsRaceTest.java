package de.raindancer.core.world.chunk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The force-load flag outlives the process, so the last word given to the world has to match who is
 * holding the chunk — however keeps and releases from different regions interleave.
 */
class ChunkHoldsRaceTest {

    @Test
    @DisplayName("after keeps and releases racing each other, nobody holds it and the world says so too")
    void lastWordMatchesTheHolders() throws Exception {
        AtomicReference<Boolean> world = new AtomicReference<>(false);
        ChunkLoader loader = new ChunkLoader() {
            @Override
            public boolean isLoaded(ChunkAt chunk) {
                return true;
            }

            @Override
            public CompletableFuture<Boolean> load(ChunkAt chunk) {
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public void keepLoaded(ChunkAt chunk, boolean keep) {
                Thread.yield();
                world.set(keep);
            }
        };
        ChunkAt chunk = new ChunkAt("world", 0, 0);
        for (int round = 0; round < 300; round++) {
            ChunkHolds holds = new ChunkHolds(loader);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            pool.submit(() -> {
                go.await();
                for (int i = 0; i < 50; i++) {
                    holds.keep("a", chunk);
                    holds.release("a", chunk);
                }
                return null;
            });
            pool.submit(() -> {
                go.await();
                for (int i = 0; i < 50; i++) {
                    holds.keep("b", chunk);
                    holds.release("b", chunk);
                }
                return null;
            });
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            assertThat(holds.isHeld(chunk)).isFalse();
            assertThat(world.get()).as("round " + round + ": force-loaded with nobody holding it")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a second owner taking hold is a change, and says so")
    void aSecondOwnerIsAChange() {
        ChunkHolds holds = new ChunkHolds(new ChunkLoader() {
            @Override
            public boolean isLoaded(ChunkAt chunk) {
                return true;
            }

            @Override
            public CompletableFuture<Boolean> load(ChunkAt chunk) {
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public void keepLoaded(ChunkAt chunk, boolean keep) {
            }
        });
        ChunkAt chunk = new ChunkAt("world", 0, 0);

        assertThat(holds.keep("a", chunk)).isTrue();
        assertThat(holds.keep("b", chunk)).isTrue();
        assertThat(holds.keep("b", chunk)).as("already held by b").isFalse();
    }
}
