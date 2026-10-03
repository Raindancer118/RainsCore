package de.raindancer.core.world.teleport;

import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The boat a traveller sits in may come with them; one with somebody else in it may not. */
class OwnVehicleTest {

    @Test
    @DisplayName("the traveller does not count as somebody being carried off")
    void theTravellerIsNotAStranger() {
        UUID traveller = UUID.randomUUID();
        Player them = mock(Player.class);
        when(them.getUniqueId()).thenReturn(traveller);
        Player stranger = mock(Player.class);
        when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());
        Boat theirs = mock(Boat.class);
        when(theirs.getPassengers()).thenReturn(List.of(them));
        Boat shared = mock(Boat.class);
        when(shared.getPassengers()).thenReturn(List.of(them, stranger));

        assertThat(Travel.carriesAPlayer(theirs, traveller)).isFalse();
        assertThat(Travel.carriesAPlayer(shared, traveller)).isTrue();
    }
}
