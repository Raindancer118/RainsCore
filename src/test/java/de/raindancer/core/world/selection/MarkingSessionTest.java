package de.raindancer.core.world.selection;

import de.raindancer.core.world.geometry.ColumnPolygon.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Marking a shape out by clicking blocks — what has been clicked so far, and whether it is a shape yet. */
class MarkingSessionTest {

    private static MarkingSession polygon() {
        return new MarkingSession("world", MarkingSession.Mode.POLYGON);
    }

    @Test
    @DisplayName("a fresh session has nothing in it")
    void startsEmpty() {
        assertThat(polygon().pointCount()).isZero();
        assertThat(polygon().vertices()).isEmpty();
        assertThat(polygon().isComplete()).isFalse();
    }

    @Test
    @DisplayName("points are kept in the order they were clicked")
    void keepsClickOrder() {
        MarkingSession session = polygon();

        session.add(new Column(5, 5));
        session.add(new Column(1, 1));

        assertThat(session.vertices()).containsExactly(new Column(5, 5), new Column(1, 1));
    }

    @Test
    @DisplayName("clicking the same block twice in a row adds nothing — a held click is one corner")
    void ignoresAnImmediateRepeat() {
        MarkingSession session = polygon();

        session.add(new Column(5, 5));
        boolean added = session.add(new Column(5, 5));

        assertThat(added).isFalse();
        assertThat(session.pointCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("the same block clicked again later is a real corner — a path may cross itself")
    void allowsRevisitingAPositionLater() {
        MarkingSession session = polygon();

        session.add(new Column(0, 0));
        session.add(new Column(5, 0));
        boolean added = session.add(new Column(0, 0));

        assertThat(added).isTrue();
        assertThat(session.pointCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("undo removes the last corner and says whether there was one")
    void undoesTheLastPoint() {
        MarkingSession session = polygon();
        session.add(new Column(1, 1));

        assertThat(session.undo()).isTrue();
        assertThat(session.pointCount()).isZero();
        assertThat(session.undo()).isFalse();
    }

    @Test
    @DisplayName("a polygon is a shape at three corners, a path at two")
    void completenessDependsOnTheMode() {
        MarkingSession polygon = polygon();
        MarkingSession path = new MarkingSession("world", MarkingSession.Mode.PATH);

        polygon.add(new Column(0, 0));
        polygon.add(new Column(1, 0));
        path.add(new Column(0, 0));
        path.add(new Column(1, 0));

        assertThat(polygon.isComplete()).isFalse();
        assertThat(path.isComplete()).isTrue();

        polygon.add(new Column(1, 1));
        assertThat(polygon.isComplete()).isTrue();
    }

    @Test
    @DisplayName("the height a corner was clicked at is remembered, for showing it back")
    void remembersWhereTheClickLanded() {
        MarkingSession session = polygon();

        session.add(new Column(4, 4), 71);

        assertThat(session.clickedYAt(0)).contains(71);
    }

    @Test
    @DisplayName("a corner added without a height has none — the shape never depended on one")
    void heightIsOptional() {
        MarkingSession session = polygon();

        session.add(new Column(4, 4));

        assertThat(session.clickedYAt(0)).isEmpty();
    }

    @Test
    @DisplayName("undoing forgets that corner's height with it")
    void undoDropsTheHeightToo() {
        MarkingSession session = polygon();
        session.add(new Column(1, 1), 64);
        session.add(new Column(2, 2), 70);

        session.undo();

        assertThat(session.clickedYAt(1)).isEmpty();
        assertThat(session.clickedYAt(0)).contains(64);
    }

    @Test
    @DisplayName("asking about a corner that is not there is empty, not an error")
    void toleratesAnIndexPastTheEnd() {
        assertThat(polygon().clickedYAt(7)).isEmpty();
        assertThat(polygon().clickedYAt(-1)).isEmpty();
    }

    @Test
    @DisplayName("the world is remembered, so a marking cannot be finished somewhere else")
    void remembersItsWorld() {
        assertThat(polygon().world()).isEqualTo("world");
    }

    @Test
    @DisplayName("the vertex list handed out cannot be changed from under the session")
    void handsOutACopy() {
        MarkingSession session = polygon();
        session.add(new Column(0, 0));

        assertThat(session.vertices()).isUnmodifiable();
    }
}
