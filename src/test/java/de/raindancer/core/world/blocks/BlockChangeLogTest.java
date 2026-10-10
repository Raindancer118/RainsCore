package de.raindancer.core.world.blocks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class BlockChangeLogTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final long MS = 1_000_000L;

    private final AtomicLong clock = new AtomicLong(1_000 * MS);
    private final BlockChangeLog log = new BlockChangeLog(3_000 * MS, clock::get);

    @Test
    @DisplayName("a change counts for a box that holds it, from a moment before it on")
    void changedSince() {
        log.changed(WORLD, 10, 63, -5);
        assertThat(log.changedSince(WORLD, 9, 62, -6, 11, 64, -4, 999 * MS)).isTrue();
        assertThat(log.changedSince(WORLD, 10, 63, -5, 10, 63, -5, 1_000 * MS)).as("the same instant counts").isTrue();
        assertThat(log.changedSince(WORLD, 9, 62, -6, 11, 64, -4, 1_001 * MS)).as("asked about later").isFalse();
    }

    @Test
    @DisplayName("only the asked box and world count")
    void boxAndWorld() {
        log.changed(WORLD, 10, 63, -5);
        assertThat(log.changedSince(WORLD, 11, 62, -6, 12, 64, -4, 0)).isFalse();
        assertThat(log.changedSince(WORLD, 9, 64, -6, 11, 65, -4, 0)).isFalse();
        assertThat(log.changedSince(OTHER, 9, 62, -6, 11, 64, -4, 0)).isFalse();
    }

    @Test
    @DisplayName("negative coordinates and the bottom and top of the world keep apart")
    void packing() {
        log.changed(WORLD, -1, -64, -1);
        log.changed(WORLD, 33_554_431, 2047, -33_554_432);
        assertThat(log.changedSince(WORLD, -1, -64, -1, -1, -64, -1, 0)).isTrue();
        assertThat(log.changedSince(WORLD, 0, -64, -1, 0, -64, -1, 0)).isFalse();
        assertThat(log.changedSince(WORLD, -1, -63, -1, -1, -63, -1, 0)).isFalse();
        assertThat(log.changedSince(WORLD, 33_554_431, 2047, -33_554_432, 33_554_431, 2047, -33_554_432, 0)).isTrue();
    }

    @Test
    @DisplayName("a second change at the same block moves its time on")
    void latestWins() {
        log.changed(WORLD, 1, 1, 1);
        clock.set(2_000 * MS);
        log.changed(WORLD, 1, 1, 1);
        assertThat(log.changedSince(WORLD, 1, 1, 1, 1, 1, 1, 1_500 * MS)).isTrue();
    }

    @Test
    @DisplayName("changes are forgotten once older than the keep time")
    void forgets() {
        log.changed(WORLD, 1, 1, 1);
        clock.addAndGet(3_001 * MS);
        assertThat(log.changedSince(WORLD, 1, 1, 1, 1, 1, 1, 0)).isFalse();
        log.prune();
        assertThat(log.size()).isZero();
    }

    @Test
    @DisplayName("a box too large to walk block by block is answered from the changes instead")
    void hugeBox() {
        log.changed(WORLD, 500, 70, 500);
        assertThat(log.changedSince(WORLD, -1000, -64, -1000, 1000, 300, 1000, 0)).isTrue();
        assertThat(log.changedSince(WORLD, -1000, -64, -1000, 400, 300, 1000, 0)).isFalse();
    }
}
