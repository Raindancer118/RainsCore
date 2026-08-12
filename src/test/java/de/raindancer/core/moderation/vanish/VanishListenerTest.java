package de.raindancer.core.moderation.vanish;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The moments vanish would otherwise announce itself without meaning to — see the class's own note.
 */
class VanishListenerTest {

    private static final UUID MOD = UUID.randomUUID();

    private static Vanish vanishHiding(UUID who) {
        Vanish vanish = new Vanish(mock(VanishSink.class));
        vanish.vanish(who);
        return vanish;
    }

    private static Player playerWithId(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    @Nested
    @DisplayName("an advancement completed")
    class Advancements {

        @Test
        @DisplayName("a vanished player's own broadcast is silenced")
        void silencesTheVanishedPlayer() {
            Vanish vanish = vanishHiding(MOD);
            VanishListener listener = new VanishListener(mock(Plugin.class), vanish, null);
            Player player = playerWithId(MOD);
            PlayerAdvancementDoneEvent event = mock(PlayerAdvancementDoneEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onAdvancement(event);

            verify(event).message(null);
        }

        @Test
        @DisplayName("an ordinary player's broadcast is left exactly as it was")
        void leavesAnOrdinaryPlayerAlone() {
            Vanish vanish = new Vanish(mock(VanishSink.class));
            VanishListener listener = new VanishListener(mock(Plugin.class), vanish, null);
            Player player = playerWithId(MOD);
            PlayerAdvancementDoneEvent event = mock(PlayerAdvancementDoneEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onAdvancement(event);

            verify(event, never()).message(org.mockito.ArgumentMatchers.any());
        }
    }
}
