package de.raindancer.core.world.selection;

import de.raindancer.core.world.geometry.ColumnPolygon.Column;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

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

    /**
     * The height each corner was clicked at, for showing the marking back to whoever is making it.
     *
     * <p>Deliberately <em>not</em> part of the shape — a shape marked while standing on a hill has to
     * be the same shape as one marked from the valley, which is the whole reason {@link Column} has no
     * height. This is only so a preview can put a marker where the click actually landed rather than
     * guessing at the ground, and nothing about what gets built may read it.
     */
    private final List<Integer> clickedY = new ArrayList<>();

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
        return add(column, null);
    }

    /** The same, remembering where the click landed so a preview can mark it. */
    public boolean add(Column column, Integer y) {
        if (!vertices.isEmpty() && vertices.get(vertices.size() - 1).equals(column)) {
            return false;
        }
        vertices.add(column);
        clickedY.add(y);
        return true;
    }

    /** The height this corner was clicked at, if it was clicked rather than typed. */
    public Optional<Integer> clickedYAt(int index) {
        if (index < 0 || index >= clickedY.size()) {
            return Optional.empty();
        }
        return Optional.ofNullable(clickedY.get(index));
    }

    /** @return whether there was a corner to take back */
    public boolean undo() {
        if (vertices.isEmpty()) {
            return false;
        }
        vertices.remove(vertices.size() - 1);
        clickedY.remove(clickedY.size() - 1);
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
