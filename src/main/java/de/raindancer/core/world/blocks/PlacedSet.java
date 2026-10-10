package de.raindancer.core.world.blocks;

import java.util.Arrays;

/** The placed blocks of one chunk as a sorted array of packed positions — what the chunk's data holds. */
final class PlacedSet {

    static final int[] EMPTY = new int[0];

    private PlacedSet() {
    }

    /** x and z within the chunk, y with room for any world height Paper allows. */
    static int pack(int x, int y, int z) {
        return ((y + 4096) << 8) | ((x & 15) << 4) | (z & 15);
    }

    static boolean contains(int[] set, int packed) {
        return Arrays.binarySearch(set, packed) >= 0;
    }

    static int[] add(int[] set, int packed) {
        int at = Arrays.binarySearch(set, packed);
        if (at >= 0) {
            return set;
        }
        int insert = -at - 1;
        int[] grown = new int[set.length + 1];
        System.arraycopy(set, 0, grown, 0, insert);
        grown[insert] = packed;
        System.arraycopy(set, insert, grown, insert + 1, set.length - insert);
        return grown;
    }

    static int[] remove(int[] set, int packed) {
        int at = Arrays.binarySearch(set, packed);
        if (at < 0) {
            return set;
        }
        int[] shrunk = new int[set.length - 1];
        System.arraycopy(set, 0, shrunk, 0, at);
        System.arraycopy(set, at + 1, shrunk, at, set.length - at - 1);
        return shrunk;
    }
}
