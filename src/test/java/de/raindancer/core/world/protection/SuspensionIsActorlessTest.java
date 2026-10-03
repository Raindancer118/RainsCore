package de.raindancer.core.world.protection;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A bypassing admin standing in a claim suspends its rules for the questions nobody is behind — a
 * redstone tick, a piston. A question about a particular player still gets that player's answer:
 * otherwise one admin walking in switched PvP, pickup and every other flag off for everybody there.
 */
class SuspensionIsActorlessTest {

    @Test
    @DisplayName("an admin's presence answers for the world, not for the other players in the claim")
    void onlyTheActorlessQuestionIsSuspended() {
        Land land = mock(Land.class);
        FlagRules rules = mock(FlagRules.class);
        ProtectedArea area = mock(ProtectedArea.class);
        Location at = mock(Location.class);
        when(at.getWorld()).thenReturn(mock(World.class));
        when(land.areaAt(at)).thenReturn(Optional.of(area));
        when(land.isSuspendedIn(area)).thenReturn(true);
        when(rules.isAllowed(eq(area), eq(LandFlag.PVP), any(), any())).thenReturn(false);
        LandFlags flags = new LandFlags(land, rules);
        UUID somebody = UUID.randomUUID();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            assertThat(flags.isAllowedAt(at, LandFlag.PVP)).isTrue();
            assertThat(flags.isAllowedAt(at, LandFlag.PVP, somebody))
                    .as("somebody who is not bypassing gets the claim's own answer")
                    .isFalse();
        }
    }
}
