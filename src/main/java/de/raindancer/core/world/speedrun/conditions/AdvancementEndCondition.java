package de.raindancer.core.world.speedrun.conditions;

import de.raindancer.core.world.speedrun.SpeedrunEndCondition;
import de.raindancer.core.world.speedrun.SpeedrunSession;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

/**
 * Ends a run when a participant completes a chosen advancement — {@code minecraft:end/kill_dragon}
 * for a vanilla dragon-kill speedrun, or any other advancement key a caller wants to race for.
 *
 * <p>Not just any player's advancement: only a {@linkplain SpeedrunSession#participants() participant's}
 * counts, so a spectator or a staff member wandering the map cannot end somebody else's run.
 */
public final class AdvancementEndCondition implements SpeedrunEndCondition, Listener {

    private final Plugin plugin;
    private final NamespacedKey advancement;
    private SpeedrunSession session;

    public AdvancementEndCondition(Plugin plugin, NamespacedKey advancement) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.advancement = Objects.requireNonNull(advancement, "advancement");
    }

    @Override
    public void arm(SpeedrunSession session) {
        this.session = Objects.requireNonNull(session, "session");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disarm() {
        HandlerList.unregisterAll(this);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!advancement.equals(event.getAdvancement().getKey())) {
            return;
        }
        if (!session.participants().contains(event.getPlayer().getUniqueId())) {
            return;
        }
        session.finish("advancement:" + advancement);
    }

    @Override
    public String describe() {
        return "advancement:" + advancement;
    }
}
