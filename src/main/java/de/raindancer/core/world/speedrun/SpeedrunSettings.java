package de.raindancer.core.world.speedrun;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/**
 * The speedrun lobby's own settings: which world it runs in and what ends a run. Its own
 * {@code SettingsStore}/{@code speedrun.yml} rather than a growing {@code CoreConfig}, the same way
 * {@code FarmWorldConfigMenu} keeps its module's settings apart from the rest of the server's.
 *
 * <p>The GUI writes through this record's own {@code SettingsStore} — {@code set}/{@code cycle} — so
 * a click and a hand-edited {@code speedrun.yml} can never disagree.
 */
@Settings(id = "speedrun", topics = {
        @Topic(path = "config/speedrun", title = "Speedrun", icon = Material.NETHER_STAR,
                description = "Which world races run in, and what ends one."),
})
public record SpeedrunSettings(

        @In("config/speedrun") @Title("Lobby world")
        @Describe("The Bukkit world the lobby, and every run, takes place in.")
        String worldName,

        @In("config/speedrun") @Title("Advancement goal")
        @Describe("The advancement that ends a run, as 'namespace:path'. Empty means none.")
        String advancementKey,

        @In("config/speedrun") @Title("Death policy")
        @Describe("Whether a death ends the run, and whether one death is enough.")
        SpeedrunDeathPolicy deathPolicy

) {

    /**
     * What a fresh install ships with: the vanilla dragon kill, nobody's death ends it, and the
     * server's own primary world — {@code "world"}, Paper's own {@code level-name} default.
     *
     * <p><b>Found the hard way:</b> shipping a placeholder like {@code "speedrun"} here means a
     * server that never renamed anything, and never will, has a lobby bound to a world that does not
     * exist — {@link SpeedrunLobby#start} then answers {@code WORLD_MISSING} for a reason nobody sees,
     * because the block was clicked in a world whose name does not even match, so
     * {@code SpeedrunLobbyListener} never gets that far. The compass and the block simply do nothing,
     * which is indistinguishable from broken.
     */
    public static final SpeedrunSettings DEFAULTS = new SpeedrunSettings(
            "world", "minecraft:end/kill_dragon", SpeedrunDeathPolicy.OFF);

    /** Whether an advancement goal is actually set — an empty key is "none", not a bad one. */
    public boolean hasAdvancementGoal() {
        return advancementKey != null && !advancementKey.isBlank();
    }

    /** Whether death ends the run at all. */
    public boolean hasDeathCondition() {
        return deathPolicy != null && deathPolicy != SpeedrunDeathPolicy.OFF;
    }

    /** Whether there is anything at all that could end a run started with this configuration. */
    public boolean hasEndCondition() {
        return hasAdvancementGoal() || hasDeathCondition();
    }
}
