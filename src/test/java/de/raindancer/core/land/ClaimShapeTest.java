package de.raindancer.core.land;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The geometry is the part of the plugin that every protection check depends on, so it is tested without
 * a server.
 */
class ClaimShapeTest {

    @Test
    @DisplayName("a rectangle covers every column between its corners, inclusive")
    void rectangleCoversItsCorners() {
        ClaimShape shape = ClaimShape.rectangle(0, 0, 4, 4, 60, 70);

        assertThat(shape.isRectangle()).isTrue();
        assertThat(shape.containsColumn(0, 0)).isTrue();
        assertThat(shape.containsColumn(4, 4)).isTrue();
        assertThat(shape.containsColumn(2, 3)).isTrue();
        assertThat(shape.containsColumn(-1, 0)).isFalse();
        assertThat(shape.containsColumn(5, 4)).isFalse();
        assertThat(shape.areaBlocks()).isEqualTo(25);
        assertThat(shape.height()).isEqualTo(11);
        assertThat(shape.volumeBlocks()).isEqualTo(25L * 11L);
    }

    @Test
    @DisplayName("the vertical range is inclusive on both ends")
    void verticalRangeIsInclusive() {
        ClaimShape shape = ClaimShape.rectangle(0, 0, 1, 1, 10, 12);

        assertThat(shape.containsY(9)).isFalse();
        assertThat(shape.containsY(10)).isTrue();
        assertThat(shape.containsY(12)).isTrue();
        assertThat(shape.containsY(13)).isFalse();
        assertThat(shape.containsBlock(0, 11, 1)).isTrue();
        assertThat(shape.containsBlock(0, 13, 1)).isFalse();
    }

    @Test
    @DisplayName("an L-shaped polygon excludes the notch but keeps both arms")
    void polygonExcludesTheNotch() {
        // An L: 10x10 square with the top-right 5x5 quadrant cut away.
        ClaimShape shape = new ClaimShape(List.of(
                new ClaimPoint(0, 0),
                new ClaimPoint(9, 0),
                new ClaimPoint(9, 4),
                new ClaimPoint(4, 4),
                new ClaimPoint(4, 9),
                new ClaimPoint(0, 9)), 0, 10);

        assertThat(shape.isRectangle()).isFalse();
        assertThat(shape.containsColumn(1, 1)).as("bottom-left arm").isTrue();
        assertThat(shape.containsColumn(8, 1)).as("bottom-right arm").isTrue();
        assertThat(shape.containsColumn(1, 8)).as("upper-left arm").isTrue();
        assertThat(shape.containsColumn(8, 8)).as("the removed notch").isFalse();
    }

    @Test
    @DisplayName("columns a polygon edge passes through count as inside")
    void edgeColumnsAreIncluded() {
        // A diagonal triangle: without the boundary correction, the hypotenuse would leak.
        ClaimShape shape = new ClaimShape(List.of(
                new ClaimPoint(0, 0),
                new ClaimPoint(8, 0),
                new ClaimPoint(0, 8)), 0, 5);

        assertThat(shape.containsColumn(0, 0)).isTrue();
        assertThat(shape.containsColumn(8, 0)).isTrue();
        assertThat(shape.containsColumn(0, 8)).isTrue();
        assertThat(shape.containsColumn(4, 4)).as("on the hypotenuse").isTrue();
        assertThat(shape.containsColumn(7, 7)).as("well outside the hypotenuse").isFalse();
    }

    @Test
    @DisplayName("shapes that share a footprint but not a Y range do not intersect")
    void stackedClaimsDoNotIntersect() {
        ClaimShape surface = ClaimShape.rectangle(0, 0, 10, 10, 60, 100);
        ClaimShape cellar = ClaimShape.rectangle(2, 2, 8, 8, 10, 40);
        ClaimShape overlapping = ClaimShape.rectangle(5, 5, 15, 15, 80, 120);

        assertThat(surface.intersects(cellar))
                .as("a hidden cellar beneath a surface claim")
                .isFalse();
        assertThat(surface.intersects(overlapping)).isTrue();
        assertThat(cellar.intersects(overlapping)).isFalse();
    }

    @Test
    @DisplayName("containment requires both the footprint and the Y range to fit")
    void containmentChecksBothAxes() {
        ClaimShape outer = ClaimShape.rectangle(0, 0, 20, 20, 0, 100);
        ClaimShape inner = ClaimShape.rectangle(5, 5, 10, 10, 20, 40);
        ClaimShape tooTall = ClaimShape.rectangle(5, 5, 10, 10, 20, 120);
        ClaimShape tooWide = ClaimShape.rectangle(5, 5, 30, 10, 20, 40);

        assertThat(inner.isContainedIn(outer)).isTrue();
        assertThat(tooTall.isContainedIn(outer)).isFalse();
        assertThat(tooWide.isContainedIn(outer)).isFalse();
    }

    @Test
    @DisplayName("the chunk index covers every chunk the bounding box touches")
    void chunkKeysCoverTheBoundingBox() {
        ClaimShape shape = ClaimShape.rectangle(0, 0, 31, 15, 0, 10);

        // X spans chunks 0..1, Z spans chunk 0 only.
        assertThat(shape.coveredChunkKeys())
                .containsExactlyInAnyOrder(ClaimShape.chunkKey(0, 0), ClaimShape.chunkKey(1, 0));
    }

    @Test
    @DisplayName("chunk keys survive negative coordinates")
    void chunkKeysHandleNegativeCoordinates() {
        assertThat(ClaimShape.chunkKey(-1, -1)).isNotEqualTo(ClaimShape.chunkKey(1, 1));
        assertThat(ClaimShape.chunkKey(-1, 5)).isNotEqualTo(ClaimShape.chunkKey(5, -1));

        ClaimShape shape = ClaimShape.rectangle(-20, -20, -5, -5, 0, 10);
        assertThat(shape.coveredChunkKeys()).contains(ClaimShape.chunkKey(-2, -2));
        assertThat(shape.containsColumn(-10, -10)).isTrue();
    }

    @Test
    @DisplayName("a degenerate outline is rejected instead of silently misbehaving")
    void rejectsDegenerateShapes() {
        assertThatThrownBy(() -> new ClaimShape(List.of(new ClaimPoint(0, 0), new ClaimPoint(1, 1)), 0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 3 vertices");
    }

    @Test
    @DisplayName("a swapped Y range is normalised rather than rejected")
    void verticalRangeIsNormalised() {
        ClaimShape shape = ClaimShape.rectangle(0, 0, 1, 1, 80, 20);

        assertThat(shape.minY()).isEqualTo(20);
        assertThat(shape.maxY()).isEqualTo(80);
    }

    @Test
    @DisplayName("points round-trip through their serialised form")
    void pointsRoundTrip() {
        ClaimPoint point = new ClaimPoint(-1234, 5678);

        assertThat(ClaimPoint.deserialize(point.serialize())).isEqualTo(point);
    }
}
