package de.raindancer.core.world.safety;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reducing a real block down to the six things {@link SafeSpots} cares about.
 *
 * <h2>The bug this exists to catch</h2>
 * A player asked for a safe landing and was put underwater standing on kelp. {@link Block#isPassable()}
 * answered true for it — correctly, nothing about kelp stops you walking through it — and the reduction
 * asked that question before it asked whether the block was actually full of water. Kelp, seagrass and
 * any waterlogged block all share that shape: walkable and wet at once, which {@code Material.WATER}
 * alone does not cover.
 */
@DisplayName("turning a block into one of six answers")
class BukkitBlocksTest {

    private static Block blockOf(Material material) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(material);
        BlockData data = mock(BlockData.class);
        when(block.getBlockData()).thenReturn(data);
        return block;
    }

    private static Block waterlogged(Material material, boolean wet) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(material);
        Waterlogged data = mock(Waterlogged.class);
        when(data.isWaterlogged()).thenReturn(wet);
        when(block.getBlockData()).thenReturn(data);
        when(block.isPassable()).thenReturn(true);
        return block;
    }

    @Test
    @DisplayName("plain water is water, as it always was")
    void plainWaterIsWater() {
        assertThat(BukkitBlocks.kindOf(blockOf(Material.WATER))).isEqualTo(BlockKind.WATER);
    }

    @Nested
    @DisplayName("submerged, but not the material WATER")
    class Submerged {

        @Test
        @DisplayName("kelp is water, not passable air")
        void kelpIsWater() {
            Block kelp = waterlogged(Material.KELP, false);
            assertThat(BukkitBlocks.kindOf(kelp)).isEqualTo(BlockKind.WATER);
        }

        @Test
        @DisplayName("seagrass and tall seagrass are water too")
        void seagrassIsWater() {
            assertThat(BukkitBlocks.kindOf(waterlogged(Material.SEAGRASS, false)))
                    .isEqualTo(BlockKind.WATER);
            assertThat(BukkitBlocks.kindOf(waterlogged(Material.TALL_SEAGRASS, false)))
                    .isEqualTo(BlockKind.WATER);
        }

        @Test
        @DisplayName("a waterlogged block is water where a player would stand")
        void aWaterloggedBlockIsWater() {
            // Any material with a Waterlogged block data works for this; the flag is what matters. A
            // material already in isHarmful's own explicit list is used deliberately below, so the
            // "dry" half of this pair does not have to reach org.bukkit.Tag — which needs a live
            // server to answer at all and would make this test a Paper-server test rather than a
            // pure unit one.
            Block wet = waterlogged(Material.MAGMA_BLOCK, true);
            assertThat(BukkitBlocks.kindOf(wet)).isEqualTo(BlockKind.WATER);
        }

        @Test
        @DisplayName("the same block dry falls through to whatever it actually is")
        void theSameBlockDryIsNotWater() {
            Block dry = waterlogged(Material.MAGMA_BLOCK, false);
            assertThat(BukkitBlocks.kindOf(dry))
                    .as("dry, this is just a magma block again — harmful, not water")
                    .isEqualTo(BlockKind.HARMFUL);
        }
    }

    @Test
    @DisplayName("lava wins even over a submerged classification — nothing is both")
    void lavaComesFirst() {
        assertThat(BukkitBlocks.kindOf(blockOf(Material.LAVA))).isEqualTo(BlockKind.LAVA);
    }
}
