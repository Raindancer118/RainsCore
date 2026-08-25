package de.raindancer.core.world.geometry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A closed shape on the ground, described by its corners and measured in whole columns.
 *
 * <p>A <em>column</em> is one x/z position with no height at all: the shape of a town wall, a claim
 * border or a plaza is decided on the ground and only afterwards given a height by whoever builds
 * it. Keeping the two apart is what lets every shape question here be answered by arithmetic, with
 * no server anywhere near it — the same seam {@code world.safety} draws with {@link
 * de.raindancer.core.world.safety.Spot}.
 *
 * <p>The ring is <em>implicitly closed</em>: the last corner joins back to the first, and neither
 * {@link #vertices()} nor {@link #outlineColumns()} repeats it. A caller that has to close the ring
 * by hand forgets to exactly once, and the resulting shape has one open side that no amount of
 * staring at the corner list explains.
 */
public record ColumnPolygon(List<Column> vertices) {

    /** One x/z position on the ground. */
    public record Column(int x, int z) {

        public Column offset(int dx, int dz) {
            return new Column(x + dx, z + dz);
        }
    }

    public ColumnPolygon {
        vertices = List.copyOf(vertices);
        if (vertices.size() < 3) {
            throw new IllegalArgumentException("a polygon needs at least three corners, got " + vertices.size());
        }
    }

    /**
     * Every column the edges pass through, once each, walking the ring in order.
     *
     * <p>Ordered rather than a set because callers draw with it — a wall built in ring order rises
     * the way somebody would build it, while a set's iteration order makes it appear in patches.
     */
    public List<Column> outlineColumns() {
        Set<Column> seen = new LinkedHashSet<>();
        for (int i = 0; i < vertices.size(); i++) {
            Column from = vertices.get(i);
            Column to = vertices.get((i + 1) % vertices.size());
            seen.addAll(line(from, to));
        }
        return List.copyOf(seen);
    }

    /** Whether this column is part of the shape — the edge itself counts as inside. */
    public boolean contains(Column column) {
        return outlineColumns().contains(column) || enclosed(column);
    }

    /** Every column the shape covers, its outline included. */
    public Set<Column> interiorColumns() {
        Set<Column> columns = new LinkedHashSet<>(outlineColumns());
        for (int x = minX(); x <= maxX(); x++) {
            for (int z = minZ(); z <= maxZ(); z++) {
                Column column = new Column(x, z);
                if (enclosed(column)) {
                    columns.add(column);
                }
            }
        }
        return columns;
    }

    /**
     * The same shape with every corner cut to an arc of about {@code radius} blocks.
     *
     * <p>The radius is clamped per corner to half of its shorter edge. Without that, a radius wider
     * than an edge makes the two arcs meeting on that edge overshoot each other, and the shape folds
     * inside out — a wall that crosses itself and encloses the wrong side.
     *
     * @param radius blocks; {@code 0} or less returns this polygon unchanged
     */
    public ColumnPolygon rounded(int radius) {
        if (radius <= 0) {
            return this;
        }
        List<Column> rounded = new ArrayList<>();
        int count = vertices.size();
        for (int i = 0; i < count; i++) {
            Column previous = vertices.get((i - 1 + count) % count);
            Column corner = vertices.get(i);
            Column next = vertices.get((i + 1) % count);

            double toPrevious = distance(corner, previous);
            double toNext = distance(corner, next);
            int cut = (int) Math.floor(Math.min(radius, Math.min(toPrevious, toNext) / 2.0));
            if (cut < 1) {
                addUnlessRepeated(rounded, corner);
                continue;
            }
            double[] start = along(corner, previous, cut);
            double[] end = along(corner, next, cut);
            int steps = Math.max(2, cut);
            for (int step = 0; step <= steps; step++) {
                double t = step / (double) steps;
                double inverse = 1 - t;
                double x = inverse * inverse * start[0] + 2 * inverse * t * corner.x() + t * t * end[0];
                double z = inverse * inverse * start[1] + 2 * inverse * t * corner.z() + t * t * end[1];
                addUnlessRepeated(rounded, new Column((int) Math.round(x), (int) Math.round(z)));
            }
        }
        return new ColumnPolygon(rounded);
    }

    public int minX() {
        return vertices.stream().mapToInt(Column::x).min().orElseThrow();
    }

    public int maxX() {
        return vertices.stream().mapToInt(Column::x).max().orElseThrow();
    }

    public int minZ() {
        return vertices.stream().mapToInt(Column::z).min().orElseThrow();
    }

    public int maxZ() {
        return vertices.stream().mapToInt(Column::z).max().orElseThrow();
    }

    /**
     * Ray casting from the column's centre. Corner cases on the boundary itself are decided by
     * {@link #contains} instead, which asks the outline first — a boundary answer from a ray cast
     * depends on which side of a corner the ray happens to leave, and is a coin toss.
     */
    private boolean enclosed(Column column) {
        double pointX = column.x() + 0.5;
        double pointZ = column.z() + 0.5;
        boolean inside = false;
        int count = vertices.size();
        for (int i = 0, j = count - 1; i < count; j = i++) {
            double xi = vertices.get(i).x() + 0.5;
            double zi = vertices.get(i).z() + 0.5;
            double xj = vertices.get(j).x() + 0.5;
            double zj = vertices.get(j).z() + 0.5;
            boolean straddles = (zi > pointZ) != (zj > pointZ);
            if (straddles && pointX < (xj - xi) * (pointZ - zi) / (zj - zi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static void addUnlessRepeated(List<Column> columns, Column column) {
        if (columns.isEmpty() || !columns.get(columns.size() - 1).equals(column)) {
            columns.add(column);
        }
    }

    private static double distance(Column from, Column to) {
        double dx = to.x() - from.x();
        double dz = to.z() - from.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** The point {@code blocks} away from {@code from} on the way to {@code towards}. */
    private static double[] along(Column from, Column towards, double blocks) {
        double dx = towards.x() - from.x();
        double dz = towards.z() - from.z();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length == 0) {
            return new double[] {from.x(), from.z()};
        }
        return new double[] {from.x() + dx / length * blocks, from.z() + dz / length * blocks};
    }

    /** Bresenham, inclusive of both ends — shared with {@link Polyline}. */
    static List<Column> line(Column from, Column to) {
        List<Column> columns = new ArrayList<>();
        int x = from.x();
        int z = from.z();
        int dx = Math.abs(to.x() - x);
        int dz = Math.abs(to.z() - z);
        int stepX = x < to.x() ? 1 : -1;
        int stepZ = z < to.z() ? 1 : -1;
        int error = dx - dz;
        while (true) {
            columns.add(new Column(x, z));
            if (x == to.x() && z == to.z()) {
                return columns;
            }
            int doubled = error * 2;
            if (doubled > -dz) {
                error -= dz;
                x += stepX;
            }
            if (doubled < dx) {
                error += dx;
                z += stepZ;
            }
        }
    }
}
