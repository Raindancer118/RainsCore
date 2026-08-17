package de.raindancer.core.world.geometry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A simple polygon of block columns (x/z) — no height, no world, no server.
 *
 * <p>Generalised from {@code claims-module}'s own {@code ClaimShape}: the same crossing-number
 * point-in-polygon test with a half-block boundary slack so a vertex or an edge a ray happens to
 * pass exactly through is still counted as inside, the same shoelace-plus-Pick's-theorem area, the
 * same chunk-key enumeration. Any module drawing a polygon on the ground — a claim, a town wall, a
 * plot — wants the same three questions answered: is this column inside, how big is it, which
 * chunks does it touch. Written here once so a second author gets them right the first time.
 */
public final class ColumnPolygon {

    private final List<Column> vertices;
    private final int minX;
    private final int minZ;
    private final int maxX;
    private final int maxZ;

    public ColumnPolygon(List<Column> vertices) {
        if (vertices == null || vertices.size() < 3) {
            throw new IllegalArgumentException("A polygon needs at least 3 vertices, got "
                    + (vertices == null ? 0 : vertices.size()));
        }
        this.vertices = List.copyOf(vertices);

        int loX = Integer.MAX_VALUE, loZ = Integer.MAX_VALUE, hiX = Integer.MIN_VALUE, hiZ = Integer.MIN_VALUE;
        for (Column column : this.vertices) {
            loX = Math.min(loX, column.x());
            loZ = Math.min(loZ, column.z());
            hiX = Math.max(hiX, column.x());
            hiZ = Math.max(hiZ, column.z());
        }
        this.minX = loX;
        this.minZ = loZ;
        this.maxX = hiX;
        this.maxZ = hiZ;
    }

    public static ColumnPolygon rectangle(int x1, int z1, int x2, int z2) {
        int loX = Math.min(x1, x2);
        int hiX = Math.max(x1, x2);
        int loZ = Math.min(z1, z2);
        int hiZ = Math.max(z1, z2);
        return new ColumnPolygon(List.of(
                new Column(loX, loZ), new Column(hiX, loZ),
                new Column(hiX, hiZ), new Column(loX, hiZ)));
    }

    public List<Column> vertices() {
        return vertices;
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

    public boolean isRectangle() {
        if (vertices.size() != 4) {
            return false;
        }
        for (Column column : vertices) {
            boolean onEdge = (column.x() == minX || column.x() == maxX)
                    && (column.z() == minZ || column.z() == maxZ);
            if (!onEdge) {
                return false;
            }
        }
        return true;
    }

    /** Crossing-number test against the column centre, plus boundary slack for edge/vertex columns. */
    public boolean contains(int x, int z) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) {
            return false;
        }
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

    private static boolean columnOnSegment(int x, int z, Column a, Column b) {
        if (x < Math.min(a.x(), b.x()) || x > Math.max(a.x(), b.x())
                || z < Math.min(a.z(), b.z()) || z > Math.max(a.z(), b.z())) {
            return false;
        }
        long cross = (long) (b.x() - a.x()) * (z - a.z()) - (long) (b.z() - a.z()) * (x - a.x());
        long slack = Math.abs(b.x() - a.x()) + Math.abs(b.z() - a.z());
        return Math.abs(cross) <= slack;
    }

    /** Horizontal area in blocks, via the shoelace formula plus Pick's theorem on the boundary. */
    public long areaBlocks() {
        if (isRectangle()) {
            return (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        }
        long twiceArea = 0L;
        int size = vertices.size();
        for (int i = 0, j = size - 1; i < size; j = i++) {
            Column current = vertices.get(i);
            Column previous = vertices.get(j);
            twiceArea += (long) previous.x() * current.z() - (long) current.x() * previous.z();
        }
        long polygonArea = Math.abs(twiceArea) / 2L;
        long boundary = 0L;
        for (int i = 0, j = size - 1; i < size; j = i++) {
            Column current = vertices.get(i);
            Column previous = vertices.get(j);
            boundary += Math.max(Math.abs(current.x() - previous.x()), Math.abs(current.z() - previous.z()));
        }
        return polygonArea + boundary / 2L + 1L;
    }

    /** Every column that lies exactly on the outline (the edges only, not the interior). */
    public List<Column> outlineColumns() {
        List<Column> outline = new ArrayList<>();
        int size = vertices.size();
        for (int i = 0, j = size - 1; i < size; j = i++) {
            outline.addAll(segmentColumns(vertices.get(j), vertices.get(i)));
        }
        return outline;
    }

    /** Every lattice column on the straight line from {@code a} to {@code b}, inclusive of both ends. */
    private static List<Column> segmentColumns(Column a, Column b) {
        List<Column> line = new ArrayList<>();
        int dx = b.x() - a.x();
        int dz = b.z() - a.z();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) {
            line.add(a);
            return line;
        }
        for (int step = 0; step <= steps; step++) {
            int x = a.x() + Math.round(dx * (step / (float) steps));
            int z = a.z() + Math.round(dz * (step / (float) steps));
            line.add(new Column(x, z));
        }
        return line;
    }

    /**
     * The same outline with every vertex replaced by a sampled circular arc tangent to its two
     * incident edges — a rounded corner instead of a sharp turn. {@code radius} is clamped to at
     * most a third of the shorter of the two incident edges, so a small polygon cannot round itself
     * into a shape with fewer sides than it started with.
     */
    public ColumnPolygon rounded(int radius) {
        if (radius <= 0) {
            return this;
        }
        List<Column> result = new ArrayList<>();
        int size = vertices.size();
        for (int i = 0; i < size; i++) {
            Column prev = vertices.get((i - 1 + size) % size);
            Column curr = vertices.get(i);
            Column next = vertices.get((i + 1) % size);
            result.addAll(roundedCorner(prev, curr, next, radius));
        }
        return new ColumnPolygon(result);
    }

    private static List<Column> roundedCorner(Column prev, Column curr, Column next, int radius) {
        double toPrevX = prev.x() - curr.x();
        double toPrevZ = prev.z() - curr.z();
        double toNextX = next.x() - curr.x();
        double toNextZ = next.z() - curr.z();
        double lenPrev = Math.hypot(toPrevX, toPrevZ);
        double lenNext = Math.hypot(toNextX, toNextZ);
        if (lenPrev == 0 || lenNext == 0) {
            return List.of(curr);
        }
        double r = Math.min(radius, Math.min(lenPrev, lenNext) / 3.0);
        if (r < 1.0) {
            return List.of(curr);
        }

        double startX = curr.x() + (toPrevX / lenPrev) * r;
        double startZ = curr.z() + (toPrevZ / lenPrev) * r;
        double endX = curr.x() + (toNextX / lenNext) * r;
        double endZ = curr.z() + (toNextZ / lenNext) * r;

        // The arc centre: on the internal angle bisector, at the distance that makes it tangent to
        // both edges — a right triangle between the vertex, a tangent point and the centre.
        double bisectX = (toPrevX / lenPrev) + (toNextX / lenNext);
        double bisectZ = (toPrevZ / lenPrev) + (toNextZ / lenNext);
        double bisectLen = Math.hypot(bisectX, bisectZ);
        if (bisectLen < 1.0e-9) {
            // The two edges point in opposite directions — no real corner to round.
            return List.of(curr);
        }
        double cosHalfAngle = ((toPrevX / lenPrev) * (bisectX / bisectLen))
                + ((toPrevZ / lenPrev) * (bisectZ / bisectLen));
        cosHalfAngle = Math.max(1.0e-6, Math.min(1.0, cosHalfAngle));
        double centreDistance = r / Math.sin(Math.acos(cosHalfAngle));
        double centreX = curr.x() + (bisectX / bisectLen) * centreDistance;
        double centreZ = curr.z() + (bisectZ / bisectLen) * centreDistance;

        double startAngle = Math.atan2(startZ - centreZ, startX - centreX);
        double endAngle = Math.atan2(endZ - centreZ, endX - centreX);
        double sweep = endAngle - startAngle;
        while (sweep <= -Math.PI) {
            sweep += 2 * Math.PI;
        }
        while (sweep > Math.PI) {
            sweep -= 2 * Math.PI;
        }

        int samples = Math.max(2, (int) Math.ceil(Math.abs(sweep) * r / 1.5));
        List<Column> arc = new ArrayList<>(samples + 1);
        for (int s = 0; s <= samples; s++) {
            double angle = startAngle + sweep * (s / (double) samples);
            int x = (int) Math.round(centreX + Math.cos(angle) * r);
            int z = (int) Math.round(centreZ + Math.sin(angle) * r);
            arc.add(new Column(x, z));
        }
        return arc;
    }

    /** Chunk keys (as {@link org.bukkit.Chunk#getChunkKey()} produces) touched by the bounding box. */
    public List<Long> coveredChunkKeys() {
        List<Long> keys = new ArrayList<>();
        int fromChunkX = minX >> 4;
        int toChunkX = maxX >> 4;
        int fromChunkZ = minZ >> 4;
        int toChunkZ = maxZ >> 4;
        for (int cx = fromChunkX; cx <= toChunkX; cx++) {
            for (int cz = fromChunkZ; cz <= toChunkZ; cz++) {
                keys.add(((long) cz << 32) | (cx & 0xFFFFFFFFL));
            }
        }
        return keys;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ColumnPolygon polygon)) {
            return false;
        }
        return vertices.equals(polygon.vertices);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vertices);
    }

    @Override
    public String toString() {
        return "ColumnPolygon[vertices=" + vertices.size()
                + ", x=" + minX + ".." + maxX + ", z=" + minZ + ".." + maxZ + "]";
    }

    /** One block column. */
    public record Column(int x, int z) {

        public String serialize() {
            return x + "," + z;
        }

        public static Column deserialize(String raw) {
            String[] parts = raw.split(",");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Malformed column: " + raw);
            }
            return new Column(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
        }
    }
}
