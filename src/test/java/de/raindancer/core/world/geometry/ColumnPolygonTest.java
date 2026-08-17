package de.raindancer.core.world.geometry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ColumnPolygonTest {

    @Test
    void rectangleContainsEveryColumnInside() {
        ColumnPolygon rectangle = ColumnPolygon.rectangle(0, 0, 9, 9);
        assertThat(rectangle.contains(0, 0)).isTrue();
        assertThat(rectangle.contains(9, 9)).isTrue();
        assertThat(rectangle.contains(5, 5)).isTrue();
        assertThat(rectangle.contains(10, 5)).isFalse();
        assertThat(rectangle.contains(-1, 5)).isFalse();
    }

    @Test
    void rectangleAreaIsWidthTimesDepth() {
        ColumnPolygon rectangle = ColumnPolygon.rectangle(0, 0, 9, 4);
        assertThat(rectangle.areaBlocks()).isEqualTo(10L * 5L);
    }

    @Test
    void triangleExcludesFarCorner() {
        ColumnPolygon triangle = new ColumnPolygon(List.of(
                new ColumnPolygon.Column(0, 0),
                new ColumnPolygon.Column(20, 0),
                new ColumnPolygon.Column(0, 20)));
        assertThat(triangle.contains(1, 1)).isTrue();
        assertThat(triangle.contains(18, 18)).isFalse();
    }

    @Test
    void outlineColumnsFormAClosedRing() {
        ColumnPolygon rectangle = ColumnPolygon.rectangle(0, 0, 4, 4);
        List<ColumnPolygon.Column> outline = rectangle.outlineColumns();
        assertThat(outline).contains(new ColumnPolygon.Column(0, 0), new ColumnPolygon.Column(4, 4),
                new ColumnPolygon.Column(2, 0), new ColumnPolygon.Column(0, 2));
    }

    @Test
    void roundedCornersStayInsideTheOriginalBoundingBox() {
        ColumnPolygon rectangle = ColumnPolygon.rectangle(0, 0, 40, 40);
        ColumnPolygon rounded = rectangle.rounded(8);
        assertThat(rounded.vertices()).isNotEmpty();
        for (ColumnPolygon.Column column : rounded.vertices()) {
            assertThat(column.x()).isBetween(0, 40);
            assertThat(column.z()).isBetween(0, 40);
        }
        // A rounded rectangle has strictly more distinct outline vertices than the sharp one it came from.
        assertThat(rounded.vertices().size()).isGreaterThan(rectangle.vertices().size());
    }

    @Test
    void zeroRadiusRoundingIsANoOp() {
        ColumnPolygon rectangle = ColumnPolygon.rectangle(0, 0, 10, 10);
        assertThat(rectangle.rounded(0)).isSameAs(rectangle);
    }

    @Test
    void serializationRoundTrips() {
        ColumnPolygon.Column column = new ColumnPolygon.Column(-12, 34);
        assertThat(ColumnPolygon.Column.deserialize(column.serialize())).isEqualTo(column);
    }
}
