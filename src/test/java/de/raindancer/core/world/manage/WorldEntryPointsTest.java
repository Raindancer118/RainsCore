package de.raindancer.core.world.manage;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Where a player was, right before the last time their world actually changed — the memory
 * {@link WorldRegenerator} uses to send somebody back to where they came from, rather than a generic
 * spawn, when the world they are standing in is deleted out from under them.
 */
class WorldEntryPointsTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());

    private final WorldEntryPoints tracker = new WorldEntryPoints();

    private static Player playerWithId(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    @Test
    @DisplayName("nothing is remembered before any teleport")
    void nothingRememberedByDefault() {
        assertThat(tracker.before(ALICE)).isEmpty();
    }

    @Test
    @DisplayName("a cross-world teleport is remembered as where they came from")
    void crossWorldTeleportIsRemembered() {
        World fromWorld = mock(World.class);
        World toWorld = mock(World.class);
        Location from = new Location(fromWorld, 10, 64, 10);
        Location to = new Location(toWorld, 0, 70, 0);
        Player alice = playerWithId(ALICE);

        tracker.onTeleport(new PlayerTeleportEvent(alice, from, to));

        assertThat(tracker.before(ALICE)).contains(from);
    }

    @Test
    @DisplayName("a teleport within the same world is not remembered")
    void sameWorldTeleportIsIgnored() {
        World world = mock(World.class);
        Location from = new Location(world, 10, 64, 10);
        Location to = new Location(world, 20, 64, 20);
        Player alice = playerWithId(ALICE);

        tracker.onTeleport(new PlayerTeleportEvent(alice, from, to));

        assertThat(tracker.before(ALICE)).isEmpty();
    }

    @Test
    @DisplayName("a cancelled teleport that never lands is not remembered")
    void cancelledTeleportNotRemembered() {
        World fromWorld = mock(World.class);
        World toWorld = mock(World.class);
        Location from = new Location(fromWorld, 10, 64, 10);
        Location to = new Location(toWorld, 0, 70, 0);
        Player alice = playerWithId(ALICE);
        PlayerTeleportEvent event = new PlayerTeleportEvent(alice, from, to);
        event.setCancelled(true);

        tracker.onTeleport(event);

        assertThat(tracker.before(ALICE)).isEmpty();
    }

    @Test
    @DisplayName("forget() removes whatever was remembered")
    void forgetRemovesTheRecord() {
        World fromWorld = mock(World.class);
        World toWorld = mock(World.class);
        Location from = new Location(fromWorld, 10, 64, 10);
        Location to = new Location(toWorld, 0, 70, 0);
        Player alice = playerWithId(ALICE);
        tracker.onTeleport(new PlayerTeleportEvent(alice, from, to));

        tracker.forget(ALICE);

        assertThat(tracker.before(ALICE)).isEmpty();
    }
}
