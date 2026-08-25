package de.raindancer.core.world.selection;

import de.raindancer.core.world.geometry.ColumnPolygon.Column;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One player marking a shape out by clicking blocks — the corners so far, and whether they are a
 * shape yet.
 *
 * <p>Columns rather than positions: what is being marked is a footprint on the ground, and the
 * height comes from whatever is built afterwards. Keeping a Y here would mean a shape marked while
 * standing on a hill quietly disagrees with the same shape marked from the valley.
 */
public final class MarkingSession {

    /** What is being marked: a closed ring, or an open path. */
    public enum Mode {
        POLYGON(3),
        PATH(2);

        private final int minimumPoints;

        Mode(int minimumPoints) {
            this.minimumPoints = minimumPoints;
        }

        public int minimumPoints() {
            return minimumPoints;
        }
    }

    private final String world;
    private final Mode mode;
    private final List<Column> vertices = new ArrayList<>();

    public MarkingSession(String world, Mode mode) {
        this.world = world;
        this.mode = mode;
    }

    public String world() {
        return world;
    }

    public Mode mode() {
        return mode;
    }

    /**
     * Adds a corner.
     *
     * <p>The same column twice <em>in a row</em> is dropped — a right-click held a moment too long
     * fires more than once, and two identical corners make a zero-length edge. The same column
     * clicked again after others is kept: a path is allowed to cross itself.
     *
     * @return whether it was added
     */
    public boolean add(Column column) {
        if (!vertices.isEmpty() && vertices.get(vertices.size() - 1).equals(column)) {
            return false;
        }
        vertices.add(column);
        return true;
    }

    /** @return whether there was a corner to take back */
    public boolean undo() {
        if (vertices.isEmpty()) {
            return false;
        }
        vertices.remove(vertices.size() - 1);
        return true;
    }

    public int pointCount() {
        return vertices.size();
    }

    public boolean isComplete() {
        return vertices.size() >= mode.minimumPoints();
    }

    public List<Column> vertices() {
        return Collections.unmodifiableList(new ArrayList<>(vertices));
    }
}
