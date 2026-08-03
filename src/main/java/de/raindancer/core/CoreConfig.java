package de.raindancer.core;

import de.raindancer.core.log.LogLevel;
import de.raindancer.core.settings.Describe;
import de.raindancer.core.settings.In;
import de.raindancer.core.settings.Range;
import de.raindancer.core.settings.Settings;
import de.raindancer.core.settings.Title;
import de.raindancer.core.settings.Topic;
import org.bukkit.Material;

/**
 * Rain's Core's own settings — and the first real use of the settings model, which is deliberate:
 * if declaring these is awkward then it will be awkward for every other plugin too.
 *
 * <p>The palette lives here rather than in each plugin because a player cannot tell which of nine
 * jars drew the window in front of them and should not be able to. What stays per plugin is its tag.
 */
@Settings(id = "core", topics = {
        @Topic(path = "appearance", title = "Appearance", icon = Material.PAINTING,
                description = "What every window and message from every one of Rain's plugins is "
                        + "drawn in."),
        @Topic(path = "appearance/colours", title = "Colours", icon = Material.CYAN_DYE,
                description = "Leave one empty to let the chosen theme decide it."),
        @Topic(path = "config/logging", title = "Logging", icon = Material.WRITABLE_BOOK,
                description = "What is written down, and for how long."),
        @Topic(path = "config/chat", title = "Chat", icon = Material.NAME_TAG,
                description = "How long a clickable button in chat stays good for."),
        @Topic(path = "appearance/tablist", title = "Tablist", icon = Material.PLAYER_HEAD,
                description = "The player list: what it says, and how it is sorted."),
})
public record CoreConfig(

        @In("appearance") @Title("Theme")
        @Describe("The whole look, chosen by name. A colour set below wins over it.")
        Theme theme,

        @In("appearance/colours") @Title("Window title")
        @Describe("The fixed part of a window title: 'Claim' in 'Claim > base'.")
        String titleLabel,

        @In("appearance/colours") @Title("Window subject")
        @Describe("The part that changes: 'base' in 'Claim > base'.")
        String titleValue,

        @In("appearance/colours") @Title("Item name")
        String itemName,

        @In("appearance/colours") @Title("Item lore")
        String itemLore,

        @In("appearance/colours") @Title("Yes")
        String ok,

        @In("appearance/colours") @Title("Careful")
        String warn,

        @In("appearance/colours") @Title("No")
        String bad,

        @In("appearance/colours") @Title("About to destroy something")
        String danger,

        @In("appearance/colours") @Title("Tag gradient, near end")
        String brandFrom,

        @In("appearance/colours") @Title("Tag gradient, far end")
        String brandTo,

        @In("appearance") @Title("Title separator")
        @Describe("What goes between the parts of a window title.")
        String titleSeparator,

        @In("config/logging") @Title("Console level")
        @Describe("The least important thing the console shows.")
        LogLevel consoleLevel,

        @In("config/logging") @Title("Logfile level")
        @Describe("The least important thing written to plugins/RainsCore/logs/. Failures that stop "
                + "a plugin working are always written, whatever this says.")
        LogLevel fileLevel,

        @In("config/logging") @Title("Days of logs to keep") @Range(min = 1, max = 365)
        int logRetentionDays,

        @In("config/chat") @Title("Button lifetime in minutes") @Range(min = 1, max = 1440)
        @Describe("How long a clickable button in chat stays good for, when the plugin offering it "
                + "does not say.")
        int buttonMinutes,

        @In("appearance/tablist") @Title("Custom tablist")
        @Describe("Whether the player list is ours at all. Off leaves it exactly as vanilla.")
        boolean tablistEnabled,

        @In("appearance/tablist") @Title("Group by world")
        @Describe("Sorts players so everybody in the same world is together. This is what makes "
                + "the list say who is where.")
        boolean tablistGroupByWorld,

        @In("appearance/tablist") @Title("World on every line")
        @Describe("Also writes the world beside each name. Off by default: the grouping already "
                + "says it, and saying it twice is noise.")
        boolean tablistWorldOnEachLine,

        @In("appearance/tablist") @Title("Header")
        @Describe("What sits above the list. Empty uses the server's name and how many are on.")
        String tablistHeader,

        @In("appearance/tablist") @Title("Footer")
        @Describe("What sits below it. Empty lists how many players are in each world.")
        String tablistFooter,

        @In("appearance/tablist") @Title("Refresh every (ticks)") @Range(min = 10, max = 200)
        @Describe("How often the list is rebuilt. A player's ping changes continuously, so an "
                + "event-only list would show a stale one for ever.")
        int tablistRefreshTicks

) {

    /** The named looks, kept as an enum so the setting completes and cycles on its own. */
    public enum Theme { DEFAULT, MIDNIGHT, EMBER, FOREST, FROST, MONO }

    /**
     * What Rain's Core ships with.
     *
     * <p>Every colour is empty on purpose: empty means "whatever the theme says", which is what
     * makes both halves useful at once. A server owner picks a theme and is done; the one who wants
     * that theme with a different lore colour fills in one line and leaves the other ten alone.
     */
    public static final CoreConfig DEFAULTS = new CoreConfig(
            Theme.DEFAULT,
            "", "", "", "", "", "", "", "", "", "",
            "▸",
            LogLevel.INFO, LogLevel.INFO, 14,
            15,
            true, true, false, "", "", 40);
}
