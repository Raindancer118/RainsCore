package de.raindancer.core.moderation.maintenance;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/** What somebody turned away during maintenance reads. */
public final class MaintenanceText {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private MaintenanceText() {
    }

    public static Component closed(String reason) {
        String why = reason == null || reason.isBlank() ? "" : "\n\n<white>" + MINI.escapeTags(reason);
        return MINI.deserialize("<gold><bold>The server is under maintenance</bold></gold>" + why
                + "\n\n<gray>Please come back a little later.");
    }
}
