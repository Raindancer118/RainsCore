package de.raindancer.core.moderation.vanish;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one place {@link Vanish}'s bookkeeping actually touches a real Bukkit player.
 *
 * <h2>Why {@link #allowFlight} needs its own gamemode logic</h2>
 * {@link Vanish} only ever asks for a flag it remembered — "did they already have this before I
 * touched it" — and has no idea what gamemode somebody is standing in right now. That is exactly
 * the gap: Creative and Spectator each own flight as a fact about the gamemode itself, and a call
 * that came from vanish alone, remembering nothing about either gamemode, has no business
 * overruling that. See {@link BukkitVanishSink#allowFlight} for the two different ways this goes
 * wrong if it does.
 */
class BukkitVanishSinkTest {

    private static final UUID MOD = UUID.randomUUID();

    private static Player playerIn(GameMode mode) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(MOD);
        when(player.getGameMode()).thenReturn(mode);
        return player;
    }

    @Nested
    @DisplayName("granting or taking back flight")
    class AllowFlight {

        @Test
        @DisplayName("an ordinary gamemode gets the flag set as asked")
        void survivalIsTouchedNormally() {
            Player target = playerIn(GameMode.SURVIVAL);
            BukkitVanishSink sink = new BukkitVanishSink(mock(Plugin.class));

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayer(MOD)).thenReturn(target);
                sink.allowFlight(MOD, true);
            }

            verify(target).setAllowFlight(true);
        }

        @Test
        @DisplayName("spectator is left alone entirely — the client flies regardless of the flag")
        void spectatorIsUntouched() {
            // The bug this guards: the client hard-codes free-fly no-clip in spectator no matter
            // what the ability flag says, so setAllowFlight(false) only makes the *server* think
            // they stopped flying — the client keeps moving through walls, and the next tick
            // applies gravity to a position the server no longer believes is airborne. That
            // mismatch is what dropped a revealed moderator straight through the world.
            Player target = playerIn(GameMode.SPECTATOR);
            when(target.isFlying()).thenReturn(true);
            BukkitVanishSink sink = new BukkitVanishSink(mock(Plugin.class));

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayer(MOD)).thenReturn(target);
                sink.allowFlight(MOD, false);
            }

            verify(target, never()).setAllowFlight(anyBoolean());
            verify(target, never()).setFlying(anyBoolean());
        }

        @Test
        @DisplayName("creative is left alone too — its flight is the gamemode's, not vanish's")
        void creativeIsUntouched() {
            // The other half of the same mistake, the opposite way round: unlike spectator,
            // setAllowFlight(false) in creative *works* — which is exactly the danger. Somebody who
            // switches to creative mid-vanish to check a build gets flight as a fact about that
            // gamemode, nothing vanish granted and nothing it remembered. A later reveal that came
            // from vanish alone has no business taking it back — it was never vanish's to give.
            Player target = playerIn(GameMode.CREATIVE);
            BukkitVanishSink sink = new BukkitVanishSink(mock(Plugin.class));

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayer(MOD)).thenReturn(target);
                sink.allowFlight(MOD, false);
            }

            verify(target, never()).setAllowFlight(anyBoolean());
            verify(target, never()).setFlying(anyBoolean());
        }

        @Test
        @DisplayName("taking flight away also stops the flying flag, for an ordinary gamemode")
        void takingItAwayStopsFlyingToo() {
            Player target = playerIn(GameMode.SURVIVAL);
            when(target.isFlying()).thenReturn(true);
            BukkitVanishSink sink = new BukkitVanishSink(mock(Plugin.class));

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayer(MOD)).thenReturn(target);
                sink.allowFlight(MOD, false);
            }

            verify(target).setAllowFlight(false);
            verify(target).setFlying(false);
        }

        @Test
        @DisplayName("nobody online is not an error")
        void offlineIsHarmless() {
            BukkitVanishSink sink = new BukkitVanishSink(mock(Plugin.class));

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayer(MOD)).thenReturn(null);
                sink.allowFlight(MOD, true);
            }
            // Reaching here without throwing is the assertion.
        }
    }
}
