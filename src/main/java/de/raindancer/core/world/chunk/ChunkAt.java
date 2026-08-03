package de.raindancer.core.world.chunk;

/**
 * One chunk, by world and chunk coordinates.
 *
 * @param world the world's name
 * @param x     chunk x — sixteen blocks wide, not one
 * @param z     chunk z
 */
public record ChunkAt(String world, int x, int z) {

    /**
     * The chunk a block is in.
     *
     * <p>An arithmetic shift rather than a division, because dividing a negative by sixteen rounds
     * towards zero: {@code -1 / 16} is 0, which puts every block west or north of spawn in the wrong
     * chunk. It is a one-line bug that only shows up on half the map, which is why it survives so
     * often.
     */
    public static ChunkAt ofBlock(String world, int blockX, int blockZ) {
        return new ChunkAt(world, blockX >> 4, blockZ >> 4);
    }

    /** The lowest block x inside this chunk. */
    public int firstBlockX() {
        return x << 4;
    }

    /** The lowest block z inside this chunk. */
    public int firstBlockZ() {
        return z << 4;
    }

    @Override
    public String toString() {
        return world + " chunk " + x + ", " + z;
    }
}
