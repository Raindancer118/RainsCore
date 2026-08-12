package de.raindancer.core.world.speedrun.conditions;

import de.raindancer.core.world.speedrun.SpeedrunEndCondition;
import de.raindancer.core.world.speedrun.SpeedrunSession;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ends a run on a participant's death — either the first one ({@link DeathPolicy#ANY}, a hardcore
 * race) or once every participant has died ({@link DeathPolicy#ALL}, a co-op run where the others
 * get to keep going until the whole team is down).
 */
public final class DeathEndCondition implements SpeedrunEndCondition, Listener {

    /** Whether one death or every participant's ends the run. */
    public enum DeathPolicy {
        /** The first participant to die ends it for everybody. */
        ANY,
        /** Only once every participant has died. */
        ALL
    }

    private final Plugin plugin;
    private final DeathPolicy policy;
    /** Who has died so far this run — cleared on {@link #disarm}. */
    private final Set<UUID> dead = ConcurrentHashMap.newKeySet();
    private SpeedrunSession session;

    public DeathEndCondition(Plugin plugin, DeathPolicy policy) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public void arm(SpeedrunSession session) {
        this.session = Objects.requireNonNull(session, "session");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disarm() {
        HandlerList.unregisterAll(this);
        dead.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        UUID player = event.getEntity().getUniqueId();
        if (!session.participants().contains(player)) {
            return;
        }
        dead.add(player);
        switch (policy) {
            case ANY -> session.finish("death:" + player);
            case ALL -> {
                if (dead.containsAll(session.participants())) {
                    session.finish("death-all");
                }
            }
        }
    }

    @Override
    public String describe() {
        return "death:" + policy;
    }
}
