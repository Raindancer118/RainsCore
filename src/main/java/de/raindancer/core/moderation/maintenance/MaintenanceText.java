package de.raindancer.core.moderation.maintenance;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/** What somebody turned away during maintenance reads. */
public final class MaintenanceText {

    /** The reason that reads as Tom's update message rather than as typed. */
    public static final String UPDATE = "update";

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private MaintenanceText() {
    }

    /**
     * @param backAt when it should be over (0 for not said); counted from {@code now}, not shown as a clock time,
     *               because players are in other time zones than the server
     */
    public static Component closed(String reason, long backAt, long now) {
        if (UPDATE.equalsIgnoreCase(reason == null ? "" : reason.strip())) {
            return MINI.deserialize("<gold>Hey you!</gold> <white>We're updating the server and expect to be back "
                    + whenBack(backAt, now) + ". Please try again then!");
        }
        String why = reason == null || reason.isBlank() ? "" : "\n\n<white>" + MINI.escapeTags(reason);
        return MINI.deserialize("<gold><bold>The server is under maintenance</bold></gold>" + why
                + "\n\n<gray>Please come back a little later.");
    }

    /** "in about 3 minutes", "any moment now", "soon". */
    public static String whenBack(long backAt, long now) {
        if (backAt <= 0) {
            return "soon";
        }
        long minutes = (backAt - now + 59_999) / 60_000;
        return minutes <= 0 ? "any moment now" : minutes == 1 ? "in about a minute" : "in about " + minutes + " minutes";
    }
}
