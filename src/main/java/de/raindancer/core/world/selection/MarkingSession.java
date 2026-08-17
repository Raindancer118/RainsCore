package de.raindancer.core.world.selection;

import de.raindancer.core.world.geometry.ColumnPolygon;

import java.util.ArrayList;
import java.util.List;

/**
 * One in-progress polygon a player is marking out with a {@link MarkingTool} stick — nothing but the
 * vertices and the mode, so what they mean (a claim, a wall, a road) is entirely up to whichever
 * module started the session.
 */
public final class MarkingSession {

    public enum Mode {
        /** Two corners define an axis-aligned rectangle. */
        RECTANGLE,
        /** Every click adds one more vertex to an open polygon. */
        POLYGON
    }

    private final String worldName;
    private final Mode mode;
    private final List<ColumnPolygon.Column> vertices = new ArrayList<>();

    public MarkingSession(String worldName, Mode mode) {
        this.worldName = worldName;
        this.mode = mode;
    }

    public String worldName() {
        return worldName;
    }

    public Mode mode() {
        return mode;
    }

    public List<ColumnPolygon.Column> vertices() {
        return List.copyOf(vertices);
    }

    public int pointCount() {
        return vertices.size();
    }

    /** False beyond a rectangle's two corners — a third click on a rectangle session does nothing new. */
    public boolean accepts() {
        return mode != Mode.RECTANGLE || vertices.size() < 2;
    }

    public void add(ColumnPolygon.Column column) {
        if (accepts()) {
            vertices.add(column);
        }
    }

    /** True once undone, false when there was nothing to undo. */
    public boolean undoLast() {
        if (vertices.isEmpty()) {
            return false;
        }
        vertices.removeLast();
        return true;
    }

    public boolean isComplete() {
        return mode == Mode.RECTANGLE ? vertices.size() == 2 : vertices.size() >= 3;
    }

    /** The rectangle/polygon this session describes so far, or empty while it is still incomplete. */
    public java.util.Optional<ColumnPolygon> toPolygon() {
        if (!isComplete()) {
            return java.util.Optional.empty();
        }
        if (mode == Mode.RECTANGLE) {
            ColumnPolygon.Column a = vertices.get(0);
            ColumnPolygon.Column b = vertices.get(1);
            return java.util.Optional.of(ColumnPolygon.rectangle(a.x(), a.z(), b.x(), b.z()));
        }
        return java.util.Optional.of(new ColumnPolygon(vertices));
    }
}
