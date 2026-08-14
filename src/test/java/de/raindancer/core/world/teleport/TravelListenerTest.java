package de.raindancer.core.world.teleport;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Whether moving and being hurt cancel a warm-up — both switchable per host, and both live: a
 * server that reloads its settings must not need to rebuild this listener for the new answer to
 * take effect on the very next move or hit.
 *
 * <h2>Why a supplier and not a constructor boolean, for either flag</h2>
 * {@code hurtCancels} used to be exactly that — fixed the moment the listener was built — which is
 * fine for a plugin with no settings, and wrong for one like {@code RainsHomes} whose
 * {@code cancel-on-move} and {@code cancel-on-damage} are both reloadable. A module wires
 * {@code () -> options.get().cancelOnMove()} once; the answer is read fresh on every event rather
 * than baked in at registration time.
 */
class TravelListenerTest {

    private final Travel travel = mock(Travel.class);
    private final Returns returns = mock(Returns.class);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);

    TravelListenerTest() {
        when(travel.cameFrom()).thenReturn(returns);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(travel.isTravelling(id)).thenReturn(true);
    }

    private Location at(int x, int y, int z) {
        Location location = mock(Location.class);
        when(location.getWorld()).thenReturn(world);
        when(location.getBlockX()).thenReturn(x);
        when(location.getBlockY()).thenReturn(y);
        when(location.getBlockZ()).thenReturn(z);
        return location;
    }

    @Nested
    @DisplayName("moving")
    class Moving {

        @Test
        @DisplayName("cancels by default, the same as before this had a flag")
        void cancelsByDefault() {
            TravelListener listener = new TravelListener(travel);
            Departures pending = mock(Departures.class);
            when(travel.pending()).thenReturn(pending);
            when(pending.hasMoved(any(), any())).thenReturn(true);

            listener.onMove(moveEvent(at(0, 64, 0), at(1, 64, 0)));

            verify(travel).cancel(player, TravelReason.MOVED);
        }

        @Test
        @DisplayName("a host that switched it off is not cancelled")
        void doesNotCancelWhenSwitchedOff() {
            TravelListener listener = new TravelListener(travel, () -> false, () -> true);
            Departures pending = mock(Departures.class);
            when(travel.pending()).thenReturn(pending);
            when(pending.hasMoved(any(), any())).thenReturn(true);

            listener.onMove(moveEvent(at(0, 64, 0), at(1, 64, 0)));

            verify(travel, never()).cancel(any(), any());
        }

        @Test
        @DisplayName("the answer is read fresh every time, not fixed when the listener was built")
        void isReadLive() {
            AtomicBoolean cancelsOnMove = new AtomicBoolean(false);
            TravelListener listener = new TravelListener(travel, cancelsOnMove::get, () -> true);
            Departures pending = mock(Departures.class);
            when(travel.pending()).thenReturn(pending);
            when(pending.hasMoved(any(), any())).thenReturn(true);

            listener.onMove(moveEvent(at(0, 64, 0), at(1, 64, 0)));
            verify(travel, never()).cancel(any(), any());

            cancelsOnMove.set(true);
            listener.onMove(moveEvent(at(0, 64, 0), at(2, 64, 0)));
            verify(travel).cancel(player, TravelReason.MOVED);
        }

        private PlayerMoveEvent moveEvent(Location from, Location to) {
            return new PlayerMoveEvent(player, from, to);
        }
    }

    @Nested
    @DisplayName("being hurt")
    class Hurting {

        @Test
        @DisplayName("cancels by default, unchanged from before this had a live flag")
        void cancelsByDefault() {
            TravelListener listener = new TravelListener(travel);
            listener.onHurt(hurtEvent());

            verify(travel).cancel(player, TravelReason.HURT);
        }

        @Test
        @DisplayName("a host that switched it off is not cancelled")
        void doesNotCancelWhenSwitchedOff() {
            TravelListener listener = new TravelListener(travel, false);
            listener.onHurt(hurtEvent());

            verify(travel, never()).cancel(any(), any());
        }

        @Test
        @DisplayName("the answer is read fresh every time")
        void isReadLive() {
            AtomicBoolean hurtCancels = new AtomicBoolean(false);
            TravelListener listener = new TravelListener(travel, () -> true, hurtCancels::get);

            listener.onHurt(hurtEvent());
            verify(travel, never()).cancel(any(), any());

            hurtCancels.set(true);
            listener.onHurt(hurtEvent());
            verify(travel).cancel(player, TravelReason.HURT);
        }

        private EntityDamageEvent hurtEvent() {
            EntityDamageEvent event = mock(EntityDamageEvent.class);
            when(event.getEntity()).thenReturn(player);
            return event;
        }
    }
}
