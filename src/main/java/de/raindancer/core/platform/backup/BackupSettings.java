package de.raindancer.core.platform.backup;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/**
 * How many shutdown backups {@link Backups} keeps — the one thing about them a server owner needs to
 * tune, reachable both in-game (through Core's own {@code /settings}) and on disk, exactly like every
 * other setting in this codebase. There is deliberately nothing here to switch the backup off
 * entirely: a safety net a server owner can disable from a menu is one that is off the one time it
 * mattered.
 */
@Settings(id = "backups", topics = {
        @Topic(path = "config/backups", title = "Backups", icon = Material.CHEST_MINECART,
                description = "Safety copies made every time RainsCore shuts down."),
})
public record BackupSettings(

        @In("config/backups") @Title("Kept backups")
        @Describe("How many shutdown backups to keep. The oldest is deleted once a new one is made.")
        @Range(min = 1, max = 100)
        int maxBackups

) {
    public static final BackupSettings DEFAULTS = new BackupSettings(10);
}
