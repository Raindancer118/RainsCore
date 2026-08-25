package de.raindancer.core.world.geometry;

import de.raindancer.core.world.geometry.ColumnPolygon.Column;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An open path on the ground — the shape of a road, a border run or a route, before anything decides
 * how tall the ground under it is.
 *
 * <p>The counterpart to {@link ColumnPolygon}, and deliberately a separate type rather than a flag on
 * it: a closed ring and an open path answer different questions (a ring has an inside; a path has two
 * ends), and every method that tried to serve both would begin by asking which one it was.
 */
public record Polyline(List<Column> points) {

    /** How many corner-cutting passes {@link #smoothed(int)} will do at most. */
    private static final int MAX_SMOOTHING = 5;

    public Polyline {
        points = List.copyOf(points);
        if (points.size() < 2) {
            throw new IllegalArgumentException("a path needs at least two points, got " + points.size());
        }
    }

    /** Every column the path runs through, in order, once each. */
    public List<Column> orderedColumns() {
        Set<Column> ordered = new LinkedHashSet<>();
        for (int i = 0; i < points.size() - 1; i++) {
            ordered.addAll(ColumnPolygon.line(points.get(i), points.get(i + 1)));
        }
        return List.copyOf(ordered);
    }

    /**
     * Every column within {@code width} of the path, measured across it and centred on it.
     *
     * <p>Width 1 is the bare line. An even width cannot be centred on whole blocks exactly, and is
     * rounded outward rather than shifted to one side — a road that is a block wider than asked for
     * looks like a road, while one whose centre is half a block off its own markers looks broken.
     */
    public Set<Column> footprint(double width) {
        double radius = Math.max(0, (width - 1) / 2.0);
        int reach = (int) Math.ceil(radius);
        double reachSquared = radius * radius + 1e-9;

        Set<Column> footprint = new LinkedHashSet<>();
        for (Column column : orderedColumns()) {
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    if (dx * dx + dz * dz <= reachSquared) {
                        footprint.add(column.offset(dx, dz));
                    }
                }
            }
        }
        return footprint;
    }

    /** How long the path is, measured along its corners. */
    public double length() {
        double total = 0;
        for (int i = 0; i < points.size() - 1; i++) {
            Column from = points.get(i);
            Column to = points.get(i + 1);
            double dx = to.x() - from.x();
            double dz = to.z() - from.z();
            total += Math.sqrt(dx * dx + dz * dz);
        }
        return total;
    }

    /**
     * The same route with its corners cut — Chaikin's algorithm, one pass per unit of
     * {@code strength}, capped at {@value #MAX_SMOOTHING}.
     *
     * <p>Why a road wants this at all: a path marked by clicking a handful of points is a run of
     * straight segments meeting at hard angles, and no road anybody ever built looks like that. Both
     * ends stay exactly where they were put — a road that decided for itself where it started would
     * miss the gate it was drawn to.
     *
     * @param strength passes; {@code 0} or less returns this path unchanged
     */
    public Polyline smoothed(int strength) {
        int passes = Math.min(MAX_SMOOTHING, strength);
        if (passes <= 0) {
            return this;
        }
        List<Column> current = points;
        for (int pass = 0; pass < passes; pass++) {
            current = cutCorners(current);
        }
        return new Polyline(current);
    }

    private static List<Column> cutCorners(List<Column> from) {
        if (from.size() < 3) {
            return from;
        }
        List<Column> cut = new ArrayList<>();
        addUnlessRepeated(cut, from.get(0));
        for (int i = 0; i < from.size() - 1; i++) {
            Column start = from.get(i);
            Column end = from.get(i + 1);
            addUnlessRepeated(cut, between(start, end, 0.25));
            addUnlessRepeated(cut, between(start, end, 0.75));
        }
        addUnlessRepeated(cut, from.get(from.size() - 1));
        return cut;
    }

    private static Column between(Column start, Column end, double fraction) {
        double x = start.x() + (end.x() - start.x()) * fraction;
        double z = start.z() + (end.z() - start.z()) * fraction;
        return new Column((int) Math.round(x), (int) Math.round(z));
    }

    private static void addUnlessRepeated(List<Column> columns, Column column) {
        if (columns.isEmpty() || !columns.get(columns.size() - 1).equals(column)) {
            columns.add(column);
        }
    }
}
