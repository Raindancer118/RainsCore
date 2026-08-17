package de.raindancer.core.world.geometry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PolylineTest {

    @Test
    void straightLineFootprintIsAsWideAsAsked() {
        Polyline line = new Polyline(List.of(
                new ColumnPolygon.Column(0, 0), new ColumnPolygon.Column(20, 0)));
        Set<ColumnPolygon.Column> footprint = line.footprint(5);
        assertThat(footprint).contains(new ColumnPolygon.Column(10, 0));
        assertThat(footprint).contains(new ColumnPolygon.Column(10, 2));
        assertThat(footprint).doesNotContain(new ColumnPolygon.Column(10, 5));
    }

    @Test
    void narrowestFootprintIsJustTheLine() {
        Polyline line = new Polyline(List.of(
                new ColumnPolygon.Column(0, 0), new ColumnPolygon.Column(5, 0)));
        Set<ColumnPolygon.Column> footprint = line.footprint(1);
        assertThat(footprint).contains(new ColumnPolygon.Column(0, 0), new ColumnPolygon.Column(5, 0));
        assertThat(footprint).doesNotContain(new ColumnPolygon.Column(0, 2));
    }

    @Test
    void lengthIsTheSumOfSegments() {
        Polyline line = new Polyline(List.of(
                new ColumnPolygon.Column(0, 0), new ColumnPolygon.Column(3, 0), new ColumnPolygon.Column(3, 4)));
        assertThat(line.length()).isEqualTo(7.0);
    }

    @Test
    void orderedColumnsWalkTheLineInPathOrder() {
        Polyline line = new Polyline(List.of(
                new ColumnPolygon.Column(0, 0), new ColumnPolygon.Column(5, 0)));
        List<ColumnPolygon.Column> ordered = line.orderedColumns();
        assertThat(ordered).containsExactly(
                new ColumnPolygon.Column(0, 0), new ColumnPolygon.Column(1, 0),
                new ColumnPolygon.Column(2, 0), new ColumnPolygon.Column(3, 0),
                new ColumnPolygon.Column(4, 0), new ColumnPolygon.Column(5, 0));
    }

    @Test
    void orderedColumnsDoNotRepeatAJointBetweenTwoSegments() {
        Polyline line = new Polyline(List.of(
                new ColumnPolygon.Column(0, 0), new ColumnPolygon.Column(2, 0), new ColumnPolygon.Column(4, 0)));
        List<ColumnPolygon.Column> ordered = line.orderedColumns();
        assertThat(ordered).containsExactly(
                new ColumnPolygon.Column(0, 0), new ColumnPolygon.Column(1, 0), new ColumnPolygon.Column(2, 0),
                new ColumnPolygon.Column(3, 0), new ColumnPolygon.Column(4, 0));
    }

    @Test
    void bentPathFootprintCoversBothSegments() {
        Polyline line = new Polyline(List.of(
                new ColumnPolygon.Column(0, 0), new ColumnPolygon.Column(10, 0), new ColumnPolygon.Column(10, 10)));
        Set<ColumnPolygon.Column> footprint = line.footprint(3);
        assertThat(footprint).contains(new ColumnPolygon.Column(5, 0));
        assertThat(footprint).contains(new ColumnPolygon.Column(10, 5));
    }
}
