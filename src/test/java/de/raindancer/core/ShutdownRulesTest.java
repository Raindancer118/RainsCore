package de.raindancer.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order onDisable does things in, read out of the source because it cannot be run without a
 * server: Paper disables plugins before it kicks anybody, and a plugin's listeners are unregistered
 * only after its onDisable returns.
 */
class ShutdownRulesTest {

    private static String onDisable() throws IOException {
        String plugin = Files.readString(Path.of("src/main/java/de/raindancer/core/RainsCorePlugin.java"))
                .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        int at = plugin.indexOf("public void onDisable()");
        assertThat(at).isNotNegative();
        return plugin.substring(at, plugin.indexOf("\n    }\n", at));
    }

    @Test
    @DisplayName("open inventory windows are closed while the invsee listener can still write them")
    void windowsAreClosedFirst() throws IOException {
        String body = onDisable();
        int close = body.indexOf("inventoryViews.closeEverything()");

        assertThat(close).as("a window left open is closed after the listener is gone: the offline "
                + "edit is never written and what the moderator added is destroyed").isNotNegative();
        assertThat(close).isLessThan(body.indexOf("audit.flush()"));
        assertThat(close).isLessThan(body.indexOf("databases.close()"));
    }

    @Test
    @DisplayName("the chunk release and the reveal come before anything that could throw")
    void persistentWorldStateFirst() throws IOException {
        String body = onDisable();

        assertThat(body.indexOf("chunks.releaseAll()")).isLessThan(body.indexOf("databases.close()"));
        assertThat(body.indexOf("seclusion.revealEverybody()")).isLessThan(body.indexOf("chunks.releaseAll()"));
    }

    @Test
    @DisplayName("a plugin disabled without tidying up has everything it registered let go")
    void anotherPluginsLeftoversAreDropped() throws IOException {
        String plugin = Files.readString(Path.of("src/main/java/de/raindancer/core/RainsCorePlugin.java"));
        int at = plugin.indexOf("public void onPluginDisable(PluginDisableEvent event)");
        assertThat(at).as("the handler this is about has moved or gone").isNotNegative();
        String handler = plugin.substring(at, plugin.indexOf("\n    }\n", at));

        for (String registry : java.util.List.of("worldEntryRules", "registry", "ProfileExtensions",
                "ChatChannels", "combat", "grants", "achievements", "itemAbilities", "land")) {
            assertThat(handler).as(registry).contains(registry + ".forgetFrom(loader)");
        }
    }

    @Test
    @DisplayName("what a crash must not undo is written as it changes, not two minutes later")
    void durableAtOnce() throws IOException {
        String plugin = Files.readString(Path.of("src/main/java/de/raindancer/core/RainsCorePlugin.java"));

        assertThat(plugin).contains("punishments.writeSoon(").contains("places.writeSoon(")
                .contains("Scheduling.async(this, grants::flush)");
    }
}
