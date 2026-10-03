package de.raindancer.core.world.locate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The order a search over the world takes, and the moment it may stop — without touching a world.
 *
 * <p>The area around an origin is cut into square cells of {@code 2 × cellRadius + 1} chunks, and the
 * cells are searched ring by ring outwards from the one the origin is in. After each finished ring the
 * search knows how far out it has looked in every direction; once the best hit so far is closer than
 * that, nothing still unsearched can beat it, and the search is settled. It is also settled when the
 * rings reach {@code maxRadiusChunks} — the answer is then the best within that, or nothing.
 *
 * <p>Not thread-safe: one search is driven by one thread at a time.
 *
 * @param <T> what a hit is — a location, for the structure locator
 */
public final class SpiralSearch<T> {

    /** One cell to search: its centre chunk and the radius around it. */
    public record Cell(int centreChunkX, int centreChunkZ, int radiusChunks) {
    }

    private final int originChunkX;
    private final int originChunkZ;
    private final double originBlockX;
    private final double originBlockZ;
    private final int cellRadius;
    private final int cellSize;
    private final int maxRings;

    private int ring;
    private final List<Cell> pending = new ArrayList<>();
    private T best;
    private double bestDistance = Double.POSITIVE_INFINITY;
    private int cellsSearched;

    /**
     * @param originBlockX    where the search is measured from, in blocks
     * @param cellRadius      how far each cell reaches from its centre, in chunks — small, so one cell is
     *                        cheap; 4 means 9 × 9 chunks a cell
     * @param maxRadiusChunks how far the search goes at most, in chunks
     */
    public SpiralSearch(double originBlockX, double originBlockZ, int cellRadius, int maxRadiusChunks) {
        this.originBlockX = originBlockX;
        this.originBlockZ = originBlockZ;
        this.originChunkX = Math.floorDiv((int) Math.floor(originBlockX), 16);
        this.originChunkZ = Math.floorDiv((int) Math.floor(originBlockZ), 16);
        this.cellRadius = Math.max(0, cellRadius);
        this.cellSize = 2 * this.cellRadius + 1;
        int reach = Math.max(0, maxRadiusChunks);
        // The fewest rings whose square reaches the asked radius.
        this.maxRings = Math.max(0, Math.ceilDiv(reach - this.cellRadius, cellSize));
        pending.add(cellAt(0, 0));
    }

    private Cell cellAt(int ringX, int ringZ) {
        return new Cell(originChunkX + ringX * cellSize, originChunkZ + ringZ * cellSize, cellRadius);
    }

    /** The next cell to search, or empty once the search is settled. */
    public Optional<Cell> next() {
        if (settled()) {
            return Optional.empty();
        }
        if (pending.isEmpty()) {
            ring++;
            for (int x = -ring; x <= ring; x++) {
                for (int z = -ring; z <= ring; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) == ring) {
                        pending.add(cellAt(x, z));
                    }
                }
            }
            // Nearest cells of the ring first, so an early hit can settle it sooner.
            pending.sort((one, other) -> Double.compare(distanceToCell(one), distanceToCell(other)));
        }
        cellsSearched++;
        return Optional.of(pending.removeFirst());
    }

    /** Reports a hit at this block position. Only a closer one than the best so far is kept. */
    public void found(double blockX, double blockZ, T hit) {
        double distance = Math.hypot(blockX - originBlockX, blockZ - originBlockZ);
        if (hit != null && distance < bestDistance) {
            best = hit;
            bestDistance = distance;
        }
    }

    /**
     * Whether nothing left to search could beat what has been found — or there is nothing left to
     * search at all.
     */
    public boolean settled() {
        if (!pending.isEmpty()) {
            return false;
        }
        if (ring >= maxRings) {
            return true;
        }
        return best != null && bestDistance <= searchedRadiusBlocks();
    }

    /** How far out the finished rings have looked in every direction, in blocks. */
    public double searchedRadiusBlocks() {
        int finishedRings = pending.isEmpty() ? ring : ring - 1;
        if (finishedRings < 0) {
            return 0;
        }
        // Half the width of the finished square, minus how far the origin sits off its chunk's corner,
        // so the guarantee holds wherever in its chunk the origin is.
        double halfWidthChunks = finishedRings * cellSize + cellRadius;
        double offset = Math.max(Math.abs(originBlockX - (originChunkX * 16 + 8)),
                Math.abs(originBlockZ - (originChunkZ * 16 + 8)));
        return Math.max(0, halfWidthChunks * 16 + 8 - offset);
    }

    public Optional<T> best() {
        return Optional.ofNullable(best);
    }

    public double bestDistance() {
        return bestDistance;
    }

    public int cellsSearched() {
        return cellsSearched;
    }

    private double distanceToCell(Cell cell) {
        double centreX = cell.centreChunkX() * 16 + 8;
        double centreZ = cell.centreChunkZ() * 16 + 8;
        return Math.hypot(centreX - originBlockX, centreZ - originBlockZ);
    }
}
