package de.raindancer.core.world.selection;

import de.raindancer.core.world.geometry.ColumnPolygon.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Who is marking something out right now. */
class MarkingSessionsTest {

    private final MarkingSessions sessions = new MarkingSessions();
    private final UUID player = UUID.randomUUID();

    @Test
    @DisplayName("nobody is marking anything until they begin")
    void startsWithNobody() {
        assertThat(sessions.sessionOf(player)).isEmpty();
        assertThat(sessions.isMarking(player)).isFalse();
    }

    @Test
    @DisplayName("beginning twice replaces the first, rather than adding corners to it")
    void beginningAgainStartsOver() {
        sessions.begin(player, "world", MarkingSession.Mode.POLYGON);
        sessions.sessionOf(player).orElseThrow().add(new Column(1, 1));

        sessions.begin(player, "world", MarkingSession.Mode.PATH);

        assertThat(sessions.sessionOf(player).orElseThrow().pointCount()).isZero();
        assertThat(sessions.sessionOf(player).orElseThrow().mode()).isEqualTo(MarkingSession.Mode.PATH);
    }

    @Test
    @DisplayName("clearing ends it, and clearing again is not an error")
    void clears() {
        sessions.begin(player, "world", MarkingSession.Mode.POLYGON);

        assertThat(sessions.clear(player)).isTrue();
        assertThat(sessions.clear(player)).isFalse();
        assertThat(sessions.isMarking(player)).isFalse();
    }

    @Test
    @DisplayName("two players mark independently")
    void keepsPlayersApart() {
        UUID other = UUID.randomUUID();
        sessions.begin(player, "world", MarkingSession.Mode.POLYGON);
        sessions.begin(other, "nether", MarkingSession.Mode.PATH);

        sessions.sessionOf(player).orElseThrow().add(new Column(1, 1));

        assertThat(sessions.sessionOf(other).orElseThrow().pointCount()).isZero();
        assertThat(sessions.sessionOf(other).orElseThrow().world()).isEqualTo("nether");
    }
}
