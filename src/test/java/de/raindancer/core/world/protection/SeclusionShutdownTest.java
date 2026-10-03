package de.raindancer.core.world.protection;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Whoever was hidden is shown again on the way out, when nothing may be scheduled any more. */
class SeclusionShutdownTest {

    @Test
    @DisplayName("revealing everybody during onDisable shows them there and then")
    @SuppressWarnings("unchecked")
    void revealsWhileDisabled() throws Exception {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.isEnabled()).thenReturn(false);
        Player watcher = mock(Player.class);
        Player subject = mock(Player.class);
        UUID watcherId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        when(server.getPlayer(watcherId)).thenReturn(watcher);
        when(server.getPlayer(subjectId)).thenReturn(subject);

        Seclusion seclusion = new Seclusion(plugin, null);
        Field field = Seclusion.class.getDeclaredField("hiddenFrom");
        field.setAccessible(true);
        ((Map<UUID, Set<UUID>>) field.get(seclusion)).put(watcherId, new HashSet<>(Set.of(subjectId)));

        seclusion.revealEverybody();

        verify(watcher).showPlayer(plugin, subject);
        verify(watcher, never()).getScheduler();
    }
}
