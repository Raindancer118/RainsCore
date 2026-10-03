package de.raindancer.core.platform.health;

import de.raindancer.core.ui.checklist.Checklist;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What an owner is shown about Core itself, and that every red line says what to do. */
class CoreHealthTest {

    private static CoreHealth.Facts healthy() {
        return new CoreHealth.Facts(true, true, List.of(), List.of(), true, true, true, false, false);
    }

    @Test
    @DisplayName("a healthy server is all green")
    void allGreen() {
        Checklist list = CoreHealth.checklist(healthy());

        assertThat(list.ready()).isTrue();
        assertThat(list.problems()).isEmpty();
    }

    @Test
    @DisplayName("a database that will not open stops everything, and says where to look")
    void database() {
        Checklist list = CoreHealth.checklist(new CoreHealth.Facts(false, true, List.of(), List.of(), true,
                false, true, false, false));

        assertThat(list.ready()).isFalse();
        assertThat(list.byId("database")).hasValueSatisfying(check ->
                assertThat(check.detail()).contains("server log").contains("nothing on disk is touched"));
    }

    @Test
    @DisplayName("a broken config names the first problem and both ways to fix it")
    void config() {
        Checklist list = CoreHealth.checklist(new CoreHealth.Facts(true, true,
                List.of("tablist-period is not a number", "x"), List.of(), true, false, true, false, false));

        assertThat(list.byId("config")).hasValueSatisfying(check -> {
            assertThat(check.ok()).isFalse();
            assertThat(check.detail()).startsWith("tablist-period is not a number (and 1 more)")
                    .contains("config.yml").contains("/settings");
        });
    }

    @Test
    @DisplayName("an unreachable pack address is a warning naming the setting")
    void packs() {
        Checklist list = CoreHealth.checklist(new CoreHealth.Facts(true, true, List.of(), List.of(), true,
                true, false, false, false));

        assertThat(list.ready()).as("a warning only").isTrue();
        assertThat(list.byId("packs")).hasValueSatisfying(check ->
                assertThat(check.detail()).contains("packs-public-address"));
    }

    @Test
    @DisplayName("Core offers owners the checklist on joining")
    void ownersAreOffered() throws IOException {
        String plugin = Files.readString(Path.of("src/main/java/de/raindancer/core/RainsCorePlugin.java"));
        int join = plugin.indexOf("public void onJoin(PlayerJoinEvent event)");

        assertThat(plugin.substring(join, join + 200)).contains("offerOwnerHint(");
    }
}
