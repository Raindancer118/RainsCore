package de.raindancer.core.platform.permission;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Which {@link GrantStore} {@link Grants} ends up holding, depending on what the server has installed.
 *
 * <h2>Why this stops at "LuckPerms is not there"</h2>
 * The other direction — LuckPerms genuinely installed — needs {@code LuckPermsProvider.get()} to answer,
 * which only a running LuckPerms plugin provides; there is no fake for that worth building for a unit
 * test, the same way {@code PermissionAttachment} itself is untested here and needs a real Bukkit. What
 * is tested is the one branch that is ordinary code: deciding, from the plugin manager alone, whether to
 * even try.
 */
@ExtendWith(MockitoExtension.class)
class GrantsPicksStoreTest {

    @Mock
    private Plugin plugin;
    @Mock
    private Server server;
    @Mock
    private PluginManager pluginManager;

    @Test
    @DisplayName("no LuckPerms plugin means the local store, and Grants says so")
    void fallsBackToLocal(@TempDir Path folder) {
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(pluginManager.getPlugin("LuckPerms")).thenReturn(null);

        Grants grants = new Grants(folder, plugin);

        assertThat(grants.usesLuckPerms()).isFalse();
        // The local store's file is grants.yml — proof this is really LocalGrantStore underneath and
        // not something that merely claims to be.
        assertThat(grants.file().getFileName().toString()).isEqualTo("grants.yml");
    }

    @Test
    @DisplayName("the plain constructor never looks for LuckPerms at all")
    void plainConstructorIsAlwaysLocal(@TempDir Path folder) {
        Grants grants = new Grants(folder);

        assertThat(grants.usesLuckPerms()).isFalse();
    }
}
