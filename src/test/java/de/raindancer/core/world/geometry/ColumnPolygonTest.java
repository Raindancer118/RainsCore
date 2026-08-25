package de.raindancer.core.world.geometry;

import de.raindancer.core.world.geometry.ColumnPolygon.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A closed shape on the ground, as columns. Everything here is arithmetic — no server, by design,
 * which is the whole reason the geometry lives in Core rather than in whatever module draws with it.
 */
class ColumnPolygonTest {

    private static ColumnPolygon square(int size) {
        return new ColumnPolygon(List.of(
                new Column(0, 0), new Column(size, 0), new Column(size, size), new Column(0, size)));
    }

    @Test
    @DisplayName("fewer than three corners is not a shape")
    void refusesTooFewVertices() {
        assertThatThrownBy(() -> new ColumnPolygon(List.of(new Column(0, 0), new Column(1, 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the outline closes back to the first corner without repeating it")
    void outlineIsAClosedRing() {
        List<Column> ring = square(4).outlineColumns();

        assertThat(ring).doesNotHaveDuplicates();
        // A 5x5 ring of columns: 25 minus the 9 interior ones.
        assertThat(ring).hasSize(16);
        assertThat(ring).contains(new Column(0, 0), new Column(4, 0), new Column(4, 4), new Column(0, 4));
        assertThat(ring).doesNotContain(new Column(2, 2));
    }

    @Test
    @DisplayName("a corner counts as inside, a column beyond the edge does not")
    void containsAnswersForEdgesAndOutside() {
        ColumnPolygon square = square(4);

        assertThat(square.contains(new Column(2, 2))).isTrue();
        assertThat(square.contains(new Column(0, 0))).isTrue();
        assertThat(square.contains(new Column(4, 2))).isTrue();
        assertThat(square.contains(new Column(5, 2))).isFalse();
        assertThat(square.contains(new Column(-1, -1))).isFalse();
    }

    @Test
    @DisplayName("the interior is every column the outline encloses, the ring included")
    void interiorFillsTheShape() {
        assertThat(square(4).interiorColumns()).hasSize(25);
    }

    @Test
    @DisplayName("rounding a corner replaces it with an arc that stays inside the original shape")
    void roundedCutsTheCorners() {
        ColumnPolygon sharp = square(20);
        ColumnPolygon rounded = sharp.rounded(6);

        assertThat(rounded.vertices()).hasSizeGreaterThan(sharp.vertices().size());
        // The sharp corner itself is gone, and nothing new lies outside what was there before.
        assertThat(rounded.vertices()).doesNotContain(new Column(0, 0));
        assertThat(rounded.vertices()).allSatisfy(corner -> {
            assertThat(corner.x()).isBetween(0, 20);
            assertThat(corner.z()).isBetween(0, 20);
        });
    }

    @Test
    @DisplayName("rounding by nothing is the same shape")
    void roundingByZeroChangesNothing() {
        ColumnPolygon sharp = square(10);

        assertThat(sharp.rounded(0)).isEqualTo(sharp);
    }

    @Test
    @DisplayName("a radius wider than the shortest edge is clamped rather than folding the shape inside out")
    void clampsAnOversizedRadius() {
        ColumnPolygon rounded = square(6).rounded(40);

        assertThat(rounded.vertices()).allSatisfy(corner -> {
            assertThat(corner.x()).isBetween(0, 6);
            assertThat(corner.z()).isBetween(0, 6);
        });
    }

    @Test
    @DisplayName("a rectangle is the same four corners whichever two it was given")
    void rectangleNormalisesItsCorners() {
        ColumnPolygon fromTopLeft = ColumnPolygon.rectangle(0, 0, 10, 6);
        ColumnPolygon fromBottomRight = ColumnPolygon.rectangle(10, 6, 0, 0);

        assertThat(fromTopLeft).isEqualTo(fromBottomRight);
        assertThat(fromTopLeft.vertices()).containsExactly(
                new Column(0, 0), new Column(10, 0), new Column(10, 6), new Column(0, 6));
    }

    @Test
    @DisplayName("a rectangle with no width is still a shape one column wide, not an error")
    void rectangleToleratesADegenerateSide() {
        assertThat(ColumnPolygon.rectangle(4, 4, 4, 9).interiorColumns()).hasSize(6);
    }

    @Test
    @DisplayName("a column survives being written down and read back")
    void roundTripsThroughText() {
        Column column = new Column(-1234, 5678);

        assertThat(Column.deserialize(column.serialize())).contains(column);
    }

    @Test
    @DisplayName("text that is not a column reads as nothing rather than as 0,0")
    void refusesRubbish() {
        assertThat(Column.deserialize("not a column")).isEmpty();
        assertThat(Column.deserialize("1")).isEmpty();
        assertThat(Column.deserialize("1,2,3")).isEmpty();
        assertThat(Column.deserialize("x,2")).isEmpty();
        assertThat(Column.deserialize(null)).isEmpty();
        assertThat(Column.deserialize("")).isEmpty();
    }

    @Test
    @DisplayName("stray whitespace around a written column is tolerated — a hand-edited file still loads")
    void toleratesWhitespace() {
        assertThat(Column.deserialize(" 4 , -7 ")).contains(new Column(4, -7));
    }

    @Test
    @DisplayName("the bounds are the corners' own extremes")
    void boundsAreTheExtremes() {
        ColumnPolygon polygon = new ColumnPolygon(List.of(
                new Column(-3, 7), new Column(11, 2), new Column(4, -6)));

        assertThat(polygon.minX()).isEqualTo(-3);
        assertThat(polygon.maxX()).isEqualTo(11);
        assertThat(polygon.minZ()).isEqualTo(-6);
        assertThat(polygon.maxZ()).isEqualTo(7);
    }
}
