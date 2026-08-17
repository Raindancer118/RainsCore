package de.raindancer.core.world.teleport;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one bit of {@link Travel} worth pinning down with a mocked server: that a companion which
 * was genuinely on a lead gets put back on one after a warp, rather than trusting Paper to have
 * kept it attached. See the long comment on {@code Travel.bring} for why that trust is misplaced —
 * a plain teleport of a leashed entity snaps the lead, it does not carry it.
 *
 * <p>Everything not under test is stubbed to the emptiest answer that lets a warp with no warm-up
 * and no safety check run start to finish synchronously: no vehicle, no passengers, nothing else
 * nearby, and both schedulers run whatever they are given at once — there is no real tick to wait
 * for in a test.
 */
class TravelTest {

    private final Plugin plugin = mock(Plugin.class);
    private final Travel travel = new Travel(plugin, null);

    private final UUID travellerId = UUID.randomUUID();
    private final Player traveller = mock(Player.class);
    private final World world = mock(World.class);
    private final Location destination = mock(Location.class);
    private final Location standingAt = mock(Location.class);

    TravelTest() {
        when(traveller.getUniqueId()).thenReturn(travellerId);
        when(traveller.isOnline()).thenReturn(true);
        when(traveller.getLocation()).thenReturn(standingAt);
        when(traveller.getVehicle()).thenReturn(null);
        when(traveller.getPassengers()).thenReturn(List.of());
        when(traveller.getNearbyEntities(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of());
        when(traveller.teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class)))
                .thenReturn(CompletableFuture.completedFuture(Boolean.TRUE));
        EntityScheduler travellerScheduler = synchronous();
        when(traveller.getScheduler()).thenReturn(travellerScheduler);

        when(standingAt.clone()).thenReturn(standingAt);
        when(standingAt.getWorld()).thenReturn(world);

        when(destination.getWorld()).thenReturn(world);
        when(destination.isWorldLoaded()).thenReturn(true);
    }

    /**
     * A dog on the traveller's lead, set up the way {@link Travel#companionsOf} would find it:
     * leashed to the traveller, standing nearby, nothing riding it and riding nothing.
     */
    private Wolf dogOnALead() {
        Wolf dog = mock(Wolf.class);
        when(dog.getUniqueId()).thenReturn(UUID.randomUUID());
        when(dog.getVehicle()).thenReturn(null);
        when(dog.getPassengers()).thenReturn(List.of());
        when(dog.isTamed()).thenReturn(false);
        when(dog.getLocation()).thenReturn(standingAt);
        when(dog.isValid()).thenReturn(true);
        EntityScheduler dogScheduler = synchronous();
        when(dog.getScheduler()).thenReturn(dogScheduler);
        when(dog.teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class)))
                .thenReturn(CompletableFuture.completedFuture(Boolean.TRUE));
        when(dog.getLeashHolder()).thenReturn(traveller);
        when(traveller.getNearbyEntities(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(dog));
        return dog;
    }

    private Trip aWarpBringing(Companions policy) {
        return Trip.to("home").bringing(policy).exactly();
    }

    @Test
    @DisplayName("a companion the engine unleashed on arrival is put back on its lead")
    void reLeashesAfterArrival() {
        Wolf dog = dogOnALead();
        // Leashed when the warp gathers who is coming; the engine has snapped it by the time the
        // re-leash task looks again — the exact sequence a plain teleport produces.
        when(dog.isLeashed()).thenReturn(true, false);

        travel.go(traveller, destination, aWarpBringing(Companions.WHAT_YOU_LEAD),
                mock(TravelWatcher.class));

        verify(dog).setLeashHolder(traveller);
    }

    @Test
    @DisplayName("a companion still on its lead afterwards is left alone")
    void doesNotReLeashWhatNeverCameOff() {
        Wolf dog = dogOnALead();
        when(dog.isLeashed()).thenReturn(true);

        travel.go(traveller, destination, aWarpBringing(Companions.WHAT_YOU_LEAD),
                mock(TravelWatcher.class));

        verify(dog, never()).setLeashHolder(any());
    }

    @Test
    @DisplayName("nobody comes along, and nothing is re-leashed, when the policy says NOBODY")
    void bringsNobody() {
        Wolf dog = dogOnALead();
        when(dog.isLeashed()).thenReturn(true, false);

        travel.go(traveller, destination, aWarpBringing(Companions.NOBODY),
                mock(TravelWatcher.class));

        verify(dog, never()).teleportAsync(any(Location.class),
                any(PlayerTeleportEvent.TeleportCause.class));
        verify(dog, never()).setLeashHolder(any());
    }

    /**
     * An {@link EntityScheduler} that runs whatever it is given at once, in place — there is no
     * real tick to wait for in a test, and the delay itself is not what is being checked.
     */
    private static EntityScheduler synchronous() {
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(scheduler.run(any(), any(), any())).thenAnswer(TravelTest::runNow);
        when(scheduler.runDelayed(any(), any(), any(), anyLong())).thenAnswer(TravelTest::runNow);
        return scheduler;
    }

    @SuppressWarnings("unchecked")
    private static Object runNow(InvocationOnMock invocation) {
        Consumer<Object> task = invocation.getArgument(1);
        task.accept(null);
        return null;
    }
}
