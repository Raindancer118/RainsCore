package de.raindancer.core.world.geometry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An open path of block columns — a road's centreline, a pipe run, a track. Not a
 * {@link ColumnPolygon}: a polyline does not close, and what a caller wants from it is not "is this
 * column inside" but "how wide a strip does this path cover".
 */
public final class Polyline {

    private final List<ColumnPolygon.Column> points;

    public Polyline(List<ColumnPolygon.Column> points) {
        if (points == null || points.size() < 2) {
            throw new IllegalArgumentException("A polyline needs at least 2 points, got "
                    + (points == null ? 0 : points.size()));
        }
        this.points = List.copyOf(points);
    }

    public List<ColumnPolygon.Column> points() {
        return points;
    }

    /**
     * Every column within {@code width / 2} of the path, measured perpendicular to whichever segment
     * is nearest — a buffered strip, capped square at both ends. {@code width} of 1 or 2 is just the
     * columns the line itself passes through, widened outward on each side as it grows.
     */
    public Set<ColumnPolygon.Column> footprint(double width) {
        double half = Math.max(0.5, width / 2.0);
        Set<ColumnPolygon.Column> footprint = new LinkedHashSet<>();
        for (int i = 0; i < points.size() - 1; i++) {
            footprint.addAll(segmentFootprint(points.get(i), points.get(i + 1), half));
        }
        return footprint;
    }

    private static Set<ColumnPolygon.Column> segmentFootprint(ColumnPolygon.Column a, ColumnPolygon.Column b,
                                                                double half) {
        Set<ColumnPolygon.Column> footprint = new LinkedHashSet<>();
        double ax = a.x() + 0.5;
        double az = a.z() + 0.5;
        double bx = b.x() + 0.5;
        double bz = b.z() + 0.5;
        double dx = bx - ax;
        double dz = bz - az;
        double length = Math.hypot(dx, dz);
        if (length < 1.0e-9) {
            footprint.add(a);
            return footprint;
        }

        int minX = (int) Math.floor(Math.min(ax, bx) - half) - 1;
        int maxX = (int) Math.ceil(Math.max(ax, bx) + half) + 1;
        int minZ = (int) Math.floor(Math.min(az, bz) - half) - 1;
        int maxZ = (int) Math.ceil(Math.max(az, bz) + half) + 1;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                double px = x + 0.5;
                double pz = z + 0.5;
                double distance = distanceToSegment(px, pz, ax, az, bx, bz, dx, dz, length);
                if (distance <= half) {
                    footprint.add(new ColumnPolygon.Column(x, z));
                }
            }
        }
        return footprint;
    }

    /** Perpendicular distance from a point to a segment, clamped to the segment's own ends. */
    private static double distanceToSegment(double px, double pz, double ax, double az, double bx, double bz,
                                             double dx, double dz, double length) {
        double t = ((px - ax) * dx + (pz - az) * dz) / (length * length);
        t = Math.max(0, Math.min(1, t));
        double nearestX = ax + t * dx;
        double nearestZ = az + t * dz;
        return Math.hypot(px - nearestX, pz - nearestZ);
    }

    /** Length of the path in blocks, summed segment by segment. */
    public double length() {
        double total = 0;
        for (int i = 0; i < points.size() - 1; i++) {
            ColumnPolygon.Column a = points.get(i);
            ColumnPolygon.Column b = points.get(i + 1);
            total += Math.hypot(b.x() - a.x(), b.z() - a.z());
        }
        return total;
    }

    /** Every lattice column the centreline itself passes through, for terrain sampling. */
    public List<ColumnPolygon.Column> sampledColumns() {
        List<ColumnPolygon.Column> sampled = new ArrayList<>();
        for (ColumnPolygon.Column column : footprint(1.0)) {
            sampled.add(column);
        }
        return sampled;
    }

    /**
     * Every lattice column along the centreline, one per unit step, <em>in path order</em> — unlike
     * {@link #sampledColumns()}, which is a set with no ordering promise. What a moving-average
     * terrain-following pass needs: "smooth over the points either side of this one" only means
     * something if "either side" follows the road rather than a hash bucket.
     */
    public List<ColumnPolygon.Column> orderedColumns() {
        List<ColumnPolygon.Column> ordered = new ArrayList<>();
        for (int i = 0; i < points.size() - 1; i++) {
            ColumnPolygon.Column a = points.get(i);
            ColumnPolygon.Column b = points.get(i + 1);
            int steps = Math.max(Math.abs(b.x() - a.x()), Math.abs(b.z() - a.z()));
            int startStep = (i == 0) ? 0 : 1;
            if (steps == 0) {
                if (startStep == 0) {
                    ordered.add(a);
                }
                continue;
            }
            for (int step = startStep; step <= steps; step++) {
                int x = a.x() + Math.round(((b.x() - a.x())) * (step / (float) steps));
                int z = a.z() + Math.round(((b.z() - a.z())) * (step / (float) steps));
                ordered.add(new ColumnPolygon.Column(x, z));
            }
        }
        if (ordered.isEmpty()) {
            ordered.addAll(points);
        }
        return ordered;
    }
}
