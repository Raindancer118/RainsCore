package de.raindancer.core.world.geometry;

import de.raindancer.core.world.geometry.ColumnPolygon.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An open path on the ground: the shape of a road before anything decides how tall the ground is. */
class PolylineTest {

    private static Polyline straight() {
        return new Polyline(List.of(new Column(0, 0), new Column(10, 0)));
    }

    @Test
    @DisplayName("a path needs two points to go anywhere")
    void refusesASinglePoint() {
        assertThatThrownBy(() -> new Polyline(List.of(new Column(0, 0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the ordered columns run from one end to the other, once each")
    void ordersFromEndToEnd() {
        List<Column> columns = straight().orderedColumns();

        assertThat(columns).doesNotHaveDuplicates().hasSize(11);
        assertThat(columns.get(0)).isEqualTo(new Column(0, 0));
        assertThat(columns.get(columns.size() - 1)).isEqualTo(new Column(10, 0));
    }

    @Test
    @DisplayName("length is measured along the corners, not between the ends")
    void lengthFollowsTheCorners() {
        Polyline bent = new Polyline(List.of(new Column(0, 0), new Column(3, 4), new Column(3, 9)));

        assertThat(bent.length()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("a one-wide path paves exactly its own line")
    void widthOnePavesTheLine() {
        assertThat(straight().footprint(1)).containsExactlyInAnyOrderElementsOf(straight().orderedColumns());
    }

    @Test
    @DisplayName("width is measured across the path, centred on it")
    void widthSpreadsBothWays() {
        Set<Column> footprint = straight().footprint(3);

        assertThat(footprint).contains(new Column(5, -1), new Column(5, 0), new Column(5, 1));
        assertThat(footprint).doesNotContain(new Column(5, 2), new Column(5, -2));
    }

    @Test
    @DisplayName("an even width still centres, rounding out to the whole block")
    void evenWidthStillCentres() {
        Set<Column> footprint = straight().footprint(4);

        assertThat(footprint).contains(new Column(5, -1), new Column(5, 0), new Column(5, 1));
    }

    @Test
    @DisplayName("smoothing keeps both ends exactly where they were")
    void smoothingPinsTheEnds() {
        Polyline corner = new Polyline(List.of(new Column(0, 0), new Column(10, 0), new Column(10, 10)));

        Polyline smoothed = corner.smoothed(2);

        assertThat(smoothed.points().get(0)).isEqualTo(new Column(0, 0));
        assertThat(smoothed.points().get(smoothed.points().size() - 1)).isEqualTo(new Column(10, 10));
    }

    @Test
    @DisplayName("smoothing cuts the hard corner off and adds no point outside the original path's reach")
    void smoothingRoundsTheCorner() {
        Polyline corner = new Polyline(List.of(new Column(0, 0), new Column(10, 0), new Column(10, 10)));

        Polyline smoothed = corner.smoothed(3);

        assertThat(smoothed.points()).doesNotContain(new Column(10, 0));
        assertThat(smoothed.points()).hasSizeGreaterThan(corner.points().size());
        assertThat(smoothed.points()).allSatisfy(point -> {
            assertThat(point.x()).isBetween(0, 10);
            assertThat(point.z()).isBetween(0, 10);
        });
    }

    @Test
    @DisplayName("smoothing a straight run leaves it straight")
    void smoothingKeepsAStraightPathStraight() {
        Polyline smoothed = straight().smoothed(3);

        assertThat(smoothed.points()).allSatisfy(point -> assertThat(point.z()).isZero());
    }

    @Test
    @DisplayName("smoothing by nothing changes nothing")
    void smoothingByZeroChangesNothing() {
        assertThat(straight().smoothed(0)).isEqualTo(straight());
    }
}
