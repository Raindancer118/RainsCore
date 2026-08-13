package de.raindancer.core.world.speedrun;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.bossbar.BarPriority;
import de.raindancer.core.ui.bossbar.BarStyle;
import de.raindancer.core.ui.bossbar.BossBars;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;

/**
 * The seconds between pressing the start block and a run actually beginning: a shared boss bar, a
 * tick and a "go" cue, and every participant frozen in place until it reaches zero.
 *
 * <h2>Why participants cannot move</h2>
 * Asked for explicitly: a countdown a runner can spend closing the distance to the goal is not a
 * countdown, it is a five-second head start. Frozen by block-quantised comparison — the same trick
 * {@code TravelListener.onMove} uses to tell "actually moved" from "turned on the spot" — rather than
 * cancelling every {@link PlayerMoveEvent} outright, so looking around while waiting still works.
 *
 * <h2>Why this is its own {@link Listener}, armed and disarmed like an end condition</h2>
 * Same shape as {@link de.raindancer.core.world.speedrun.conditions.AdvancementEndCondition}: registered
 * the moment the countdown begins, unregistered the moment it ends, so a freeze from one countdown can
 * never linger and catch a player in a later one.
 */
final class SpeedrunCountdown implements Listener {

    private static final String OWNER = "core";
    private static final String BAR_ID = "speedrun-countdown";
    private static final int SECONDS = 5;

    private final Plugin plugin;
    private final BossBars bossBars;
    private final Effects effects;
    private final Set<UUID> participants;
    private final Runnable onComplete;

    private int secondsLeft;

    SpeedrunCountdown(Plugin plugin, BossBars bossBars, Effects effects, Set<UUID> participants,
                      Runnable onComplete) {
        this.plugin = plugin;
        this.bossBars = bossBars;
        this.effects = effects;
        this.participants = participants;
        this.onComplete = onComplete;
    }

    /** Starts the countdown. Called exactly once, by {@link SpeedrunLobby#beginCountdown}. */
    void begin() {
        secondsLeft = SECONDS;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        announce();
        Scheduling.globalTimer(plugin, 20L, 20L, task -> {
            secondsLeft--;
            if (secondsLeft <= 0) {
                task.cancel();
                finish();
            } else {
                announce();
            }
        });
    }

    private void announce() {
        Component text = Component.text(secondsLeft, NamedTextColor.YELLOW);
        bossBars.showShared(OWNER, BAR_ID, participants,
                BarStyle.of(text).progress(secondsLeft / (float) SECONDS).colour(BossBar.Color.GREEN),
                BarPriority.HIGH);
        effects.playForAll(participants, Cues.COUNTDOWN);
    }

    private void finish() {
        HandlerList.unregisterAll(this);
        bossBars.clearShared(OWNER, BAR_ID);
        effects.playForAll(participants, Cues.COUNTDOWN_DONE);
        onComplete.run();
    }

    /**
     * Blocks an actual step, not a look around. {@code MONITOR} would be too late — vanilla has
     * already moved the player by the time a monitor-priority handler sees the event — so this runs
     * at {@code HIGHEST}, the last priority that can still refuse the move itself.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!participants.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        if (event.getTo() == null || sameBlock(event.getFrom(), event.getTo())) {
            return;
        }
        event.setCancelled(true);
    }

    private static boolean sameBlock(org.bukkit.Location from, org.bukkit.Location to) {
        return from.getWorld() == to.getWorld()
                && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ();
    }
}
