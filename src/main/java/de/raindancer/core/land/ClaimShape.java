package de.raindancer.core.land;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The horizontal footprint of a claim as a simple polygon of block columns plus a vertical range.
 * <p>
 * A rectangular claim is just a four vertex polygon, so the whole plugin only ever deals with one
 * shape type. Vertices are block coordinates and describe the <em>column</em> they sit in: the
 * polygon covers a vertex column entirely, which is what players expect when they click a block.
 * <p>
 * Immutable — resizing produces a new instance.
 */
public final class ClaimShape {

    private final List<ClaimPoint> vertices;
    private final int minY;
    private final int maxY;

    // Cached bounding box, derived from the vertices.
    private final int minX;
    private final int minZ;
    private final int maxX;
    private final int maxZ;

    public ClaimShape(List<ClaimPoint> vertices, int minY, int maxY) {
        if (vertices == null || vertices.size() < 3) {
            throw new IllegalArgumentException("A claim shape needs at least 3 vertices, got "
                    + (vertices == null ? 0 : vertices.size()));
        }
        this.vertices = List.copyOf(vertices);
        this.minY = Math.min(minY, maxY);
        this.maxY = Math.max(minY, maxY);

        int loX = Integer.MAX_VALUE, loZ = Integer.MAX_VALUE, hiX = Integer.MIN_VALUE, hiZ = Integer.MIN_VALUE;
        for (ClaimPoint point : this.vertices) {
            loX = Math.min(loX, point.x());
            loZ = Math.min(loZ, point.z());
            hiX = Math.max(hiX, point.x());
            hiZ = Math.max(hiZ, point.z());
        }
        this.minX = loX;
        this.minZ = loZ;
        this.maxX = hiX;
        this.maxZ = hiZ;
    }

    /** Builds a rectangular shape from two opposite corner columns. */
    public static ClaimShape rectangle(int x1, int z1, int x2, int z2, int minY, int maxY) {
        int loX = Math.min(x1, x2);
        int hiX = Math.max(x1, x2);
        int loZ = Math.min(z1, z2);
        int hiZ = Math.max(z1, z2);
        return new ClaimShape(List.of(
                new ClaimPoint(loX, loZ),
                new ClaimPoint(hiX, loZ),
                new ClaimPoint(hiX, hiZ),
                new ClaimPoint(loX, hiZ)), minY, maxY);
    }

    public List<ClaimPoint> vertices() {
        return vertices;
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    public int minX() {
        return minX;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxZ() {
        return maxZ;
    }

    public int height() {
        return maxY - minY + 1;
    }

    public boolean isRectangle() {
        if (vertices.size() != 4) {
            return false;
        }
        for (ClaimPoint point : vertices) {
            boolean onEdge = (point.x() == minX || point.x() == maxX) && (point.z() == minZ || point.z() == maxZ);
            if (!onEdge) {
                return false;
            }
        }
        return true;
    }

    public ClaimShape withVerticalRange(int newMinY, int newMaxY) {
        return new ClaimShape(vertices, newMinY, newMaxY);
    }

    public boolean containsY(int y) {
        return y >= minY && y <= maxY;
    }

    public boolean containsColumn(int x, int z) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) {
            return false;
        }
        // Crossing number test against the centre of the block column. Using +0.5 offsets keeps
        // vertices and edges away from the ray, which removes the classic on-edge ambiguity.
        double px = x + 0.5D;
        double pz = z + 0.5D;
        boolean inside = false;
        int size = vertices.size();
        for (int i = 0, j = size - 1; i < size; j = i++) {
            double xi = vertices.get(i).x() + 0.5D;
            double zi = vertices.get(i).z() + 0.5D;
            double xj = vertices.get(j).x() + 0.5D;
            double zj = vertices.get(j).z() + 0.5D;
            if (((zi > pz) != (zj > pz)) && (px < (xj - xi) * (pz - zi) / (zj - zi) + xi)) {
                inside = !inside;
            }
        }
        if (inside) {
            return true;
        }
        // The crossing test can exclude columns that a vertex or an edge passes exactly through.
        // Those columns are visually part of the claim, so include them explicitly.
        return touchesBoundary(x, z);
    }

    private boolean touchesBoundary(int x, int z) {
        int size = vertices.size();
        for (int i = 0, j = size - 1; i < size; j = i++) {
            if (columnOnSegment(x, z, vertices.get(j), vertices.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean columnOnSegment(int x, int z, ClaimPoint a, ClaimPoint b) {
        if (x < Math.min(a.x(), b.x()) || x > Math.max(a.x(), b.x())
                || z < Math.min(a.z(), b.z()) || z > Math.max(a.z(), b.z())) {
            return false;
        }
        long cross = (long) (b.x() - a.x()) * (z - a.z()) - (long) (b.z() - a.z()) * (x - a.x());
        // Allow a half block of slack so diagonal edges stay connected instead of leaving gaps.
        long slack = Math.abs(b.x() - a.x()) + Math.abs(b.z() - a.z());
        return Math.abs(cross) <= slack;
    }

    public boolean containsBlock(int x, int y, int z) {
        return containsY(y) && containsColumn(x, z);
    }

    /** Horizontal footprint in blocks, via the shoelace formula on the expanded column outline. */
    public long areaBlocks() {
        if (isRectangle()) {
            return (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        }
        long twiceArea = 0L;
        int size = vertices.size();
        for (int i = 0, j = size - 1; i < size; j = i++) {
            ClaimPoint current = vertices.get(i);
            ClaimPoint previous = vertices.get(j);
            twiceArea += (long) previous.x() * current.z() - (long) current.x() * previous.z();
        }
        long polygonArea = Math.abs(twiceArea) / 2L;
        // Pick's theorem correction: the lattice polygon area under-counts the blocks whose columns
        // the outline passes through. Adding half the boundary length plus one approximates them.
        long boundary = 0L;
        for (int i = 0, j = size - 1; i < size; j = i++) {
            ClaimPoint current = vertices.get(i);
            ClaimPoint previous = vertices.get(j);
            boundary += Math.max(Math.abs(current.x() - previous.x()), Math.abs(current.z() - previous.z()));
        }
        return polygonArea + boundary / 2L + 1L;
    }

    public long volumeBlocks() {
        return areaBlocks() * height();
    }

    /** Bounding box overlap — cheap pre-filter before the exact test. */
    public boolean boundsIntersect(ClaimShape other) {
        return minX <= other.maxX && maxX >= other.minX
                && minZ <= other.maxZ && maxZ >= other.minZ
                && minY <= other.maxY && maxY >= other.minY;
    }

    /**
     * True when the two shapes share at least one block. Vertical ranges must overlap, which is what
     * makes stacked claims (one underground, one on the surface) possible.
     */
    public boolean intersects(ClaimShape other) {
        if (!boundsIntersect(other)) {
            return false;
        }
        int loX = Math.max(minX, other.minX);
        int hiX = Math.min(maxX, other.maxX);
        int loZ = Math.max(minZ, other.minZ);
        int hiZ = Math.min(maxZ, other.maxZ);
        for (int x = loX; x <= hiX; x++) {
            for (int z = loZ; z <= hiZ; z++) {
                if (containsColumn(x, z) && other.containsColumn(x, z)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when every column of this shape lies inside {@code other} and the Y range fits too. */
    public boolean isContainedIn(ClaimShape other) {
        if (minY < other.minY || maxY > other.maxY) {
            return false;
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (containsColumn(x, z) && !other.containsColumn(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Chunk keys (as produced by {@link org.bukkit.Chunk#getChunkKey()}) touched by the bounding box. */
    public List<Long> coveredChunkKeys() {
        List<Long> keys = new ArrayList<>();
        int fromChunkX = minX >> 4;
        int toChunkX = maxX >> 4;
        int fromChunkZ = minZ >> 4;
        int toChunkZ = maxZ >> 4;
        for (int cx = fromChunkX; cx <= toChunkX; cx++) {
            for (int cz = fromChunkZ; cz <= toChunkZ; cz++) {
                keys.add(chunkKey(cx, cz));
            }
        }
        return keys;
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkZ << 32) | (chunkX & 0xFFFFFFFFL);
    }

    public ClaimPoint centre() {
        return new ClaimPoint((minX + maxX) / 2, (minZ + maxZ) / 2);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ClaimShape shape)) {
            return false;
        }
        return minY == shape.minY && maxY == shape.maxY && vertices.equals(shape.vertices);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vertices, minY, maxY);
    }

    @Override
    public String toString() {
        return "ClaimShape[vertices=" + vertices.size() + ", x=" + minX + ".." + maxX
                + ", z=" + minZ + ".." + maxZ + ", y=" + minY + ".." + maxY + "]";
    }
}
