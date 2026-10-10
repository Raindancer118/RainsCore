package de.raindancer.core.moderation.maintenance;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaintenanceTextTest {

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    @DisplayName("an update says when to come back, counted from now")
    void update() {
        assertThat(plain(MaintenanceText.closed("update", 1_000_000 + 3 * 60_000, 1_000_000)))
                .isEqualTo("Hey you! We're updating the server and expect to be back in about 3 minutes. Please try again then!");
        assertThat(plain(MaintenanceText.closed("Update", 1_000_000 + 150_000, 1_000_000)))
                .contains("back in about 3 minutes");
        assertThat(plain(MaintenanceText.closed("update", 1_000_000 + 40_000, 1_000_000)))
                .contains("back in about a minute");
    }

    @Test
    @DisplayName("an update running late says any moment now rather than a time in the past")
    void late() {
        assertThat(plain(MaintenanceText.closed("update", 1_000_000, 1_000_000 + 30_000)))
                .isEqualTo("Hey you! We're updating the server and expect to be back any moment now. Please try again then!");
    }

    @Test
    @DisplayName("any other reason is shown as it was typed")
    void otherReason() {
        String text = plain(MaintenanceText.closed("New spawn", 0, 1_000_000));
        assertThat(text).contains("The server is under maintenance").contains("New spawn");
    }
}
