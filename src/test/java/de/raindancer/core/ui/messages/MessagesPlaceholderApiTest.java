package de.raindancer.core.ui.messages;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlaceholderAPI's own {@code %placeholder%} syntax, resolved for a player if that plugin is
 * installed — and left alone entirely when it is not, or when nobody is a player to resolve it for.
 *
 * <h2>Why this is soft, not a real dependency</h2>
 * A server with no PlaceholderAPI installed must boot and send messages exactly as it did before this
 * existed. So every path here is guarded by {@code Bukkit.getPluginManager().isPluginEnabled(...)},
 * and the class is never touched unless that answers true.
 */
@DisplayName("PlaceholderAPI")
class MessagesPlaceholderApiTest {

    @TempDir
    Path directory;

    private static InputStream bundled(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }

    private Messages messages(String yaml) {
        Messages messages = new Messages(directory.resolve("messages.yml"));
        messages.load(bundled(yaml));
        return messages;
    }

    @Test
    @DisplayName("resolved for a player when the plugin is installed and enabled")
    void resolvedWhenInstalled() {
        Player player = mock(Player.class);
        PluginManager pluginManager = mock(PluginManager.class);
        when(pluginManager.isPluginEnabled("PlaceholderAPI")).thenReturn(true);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<PlaceholderAPI> papi = mockStatic(PlaceholderAPI.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
            papi.when(() -> PlaceholderAPI.setPlaceholders(any(Player.class), anyString()))
                    .thenReturn("Hello, Steve.");

            Messages messages = messages("greeting: \"<gray>Hello, %player_name%.\"");
            messages.send(player, "greeting");

            papi.verify(() -> PlaceholderAPI.setPlaceholders(player, "<gray>Hello, %player_name%."));
            verify(player).sendMessage(net.kyori.adventure.text.Component.text("Hello, Steve."));
        }
    }

    @Test
    @DisplayName("left as it is when PlaceholderAPI is not installed")
    void notInstalled() {
        Player player = mock(Player.class);
        PluginManager pluginManager = mock(PluginManager.class);
        when(pluginManager.isPluginEnabled("PlaceholderAPI")).thenReturn(false);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<PlaceholderAPI> papi = mockStatic(PlaceholderAPI.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

            Messages messages = messages("greeting: \"<gray>Hello, %player_name%.\"");
            messages.send(player, "greeting");

            papi.verifyNoInteractions();
        }
    }

    @Test
    @DisplayName("never asked for on behalf of somebody who is not a player")
    void notAPlayer() {
        Audience console = mock(Audience.class);
        PluginManager pluginManager = mock(PluginManager.class);
        when(pluginManager.isPluginEnabled("PlaceholderAPI")).thenReturn(true);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<PlaceholderAPI> papi = mockStatic(PlaceholderAPI.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

            Messages messages = messages("greeting: \"<gray>Hello, %player_name%.\"");
            messages.send(console, "greeting");

            papi.verifyNoInteractions();
        }
    }

    @Test
    @DisplayName("a broken PlaceholderAPI costs the placeholders, never the message")
    void resolutionFailureIsNotFatal() {
        Player player = mock(Player.class);
        PluginManager pluginManager = mock(PluginManager.class);
        when(pluginManager.isPluginEnabled("PlaceholderAPI")).thenReturn(true);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<PlaceholderAPI> papi = mockStatic(PlaceholderAPI.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
            papi.when(() -> PlaceholderAPI.setPlaceholders(any(Player.class), anyString()))
                    .thenThrow(new RuntimeException("a badly written expansion"));

            Messages messages = messages("greeting: \"<gray>Hello there.\"");

            messages.send(player, "greeting");

            verify(player).sendMessage(net.kyori.adventure.text.Component.text("Hello there.")
                    .color(net.kyori.adventure.text.format.NamedTextColor.GRAY));
        }
    }

    @Test
    @DisplayName("sendPlain resolves placeholders too")
    void sendPlainResolvesToo() {
        Player player = mock(Player.class);
        PluginManager pluginManager = mock(PluginManager.class);
        when(pluginManager.isPluginEnabled("PlaceholderAPI")).thenReturn(true);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<PlaceholderAPI> papi = mockStatic(PlaceholderAPI.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
            papi.when(() -> PlaceholderAPI.setPlaceholders(any(Player.class), anyString()))
                    .thenReturn("resolved");

            Messages messages = messages("line: \"<gray>%some_placeholder%\"");
            messages.sendPlain(player, "line");

            papi.verify(() -> PlaceholderAPI.setPlaceholders(player, "<gray>%some_placeholder%"));
        }
    }
}
