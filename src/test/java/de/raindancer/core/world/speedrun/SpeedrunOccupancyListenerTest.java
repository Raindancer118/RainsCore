package de.raindancer.core.world.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Mirrors {@code TravelListener}'s onQuit idiom: {@code MONITOR} priority, and only reacting —
 * deciding nothing about whether the quit or join itself proceeds.
 */
class SpeedrunOccupancyListenerTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private static Player playerWithId(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    @Test
    void pausesWhenTheLastParticipantQuits() {
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE, BOB));
        session.start();
        SpeedrunOccupancyListener listener = new SpeedrunOccupancyListener(session);
        Player alice = playerWithId(ALICE);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            // Bob is already offline; Alice is the one quitting right now and must be excluded by id
            // rather than trusted via getOnlinePlayers() timing — see the listener's javadoc.
            bukkit.when(() -> Bukkit.getPlayer(BOB)).thenReturn(null);
            bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(alice);

            listener.onQuit(new PlayerQuitEvent(alice, "bye"));
        }

        assertThat(session.state()).isEqualTo(SpeedrunState.PAUSED);
    }

    @Test
    void doesNotPauseWhileSomebodyElseIsStillOnline() {
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE, BOB));
        session.start();
        SpeedrunOccupancyListener listener = new SpeedrunOccupancyListener(session);
        Player alice = playerWithId(ALICE);
        Player bob = playerWithId(BOB);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(BOB)).thenReturn(bob);
            bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(alice);

            listener.onQuit(new PlayerQuitEvent(alice, "bye"));
        }

        assertThat(session.state()).isEqualTo(SpeedrunState.RUNNING);
    }

    @Test
    void resumesOnTheFirstParticipantJoin() {
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE, BOB));
        session.start();
        session.pauseForEmptyRoster();
        SpeedrunOccupancyListener listener = new SpeedrunOccupancyListener(session);

        listener.onJoin(new PlayerJoinEvent(playerWithId(ALICE), "hi"));

        assertThat(session.state()).isEqualTo(SpeedrunState.RUNNING);
    }

    @Test
    void aNonParticipantJoiningDoesNotResume() {
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
        session.start();
        session.pauseForEmptyRoster();
        SpeedrunOccupancyListener listener = new SpeedrunOccupancyListener(session);

        listener.onJoin(new PlayerJoinEvent(playerWithId(UUID.randomUUID()), "hi"));

        assertThat(session.state()).isEqualTo(SpeedrunState.PAUSED);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("a FINISHED session is not touched by a quit")
    void finishedSessionIsNotPausedByQuit() {
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
        session.start();
        session.finish("done");
        SpeedrunOccupancyListener listener = new SpeedrunOccupancyListener(session);
        Player alice = playerWithId(ALICE);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(alice);
            listener.onQuit(new PlayerQuitEvent(alice, "bye"));
        }

        assertThat(session.state()).isEqualTo(SpeedrunState.FINISHED);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("a NOT_STARTED session is not touched by a join")
    void notStartedSessionIsNotResumedByJoin() {
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
        SpeedrunOccupancyListener listener = new SpeedrunOccupancyListener(session);

        listener.onJoin(new PlayerJoinEvent(playerWithId(ALICE), "hi"));

        assertThat(session.state()).isEqualTo(SpeedrunState.NOT_STARTED);
    }
}
