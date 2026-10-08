package de.raindancer.core.content.items;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.function.Consumer;

/** Refuses an egg marked {@link NotForSpawners} on a spawner or a trial spawner. */
public final class NotForSpawnersListener implements Listener {

    private final Consumer<Player> tell;

    /** @param tell says to the player why nothing happened */
    public NotForSpawnersListener(Consumer<Player> tell) {
        this.tell = tell;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || !NotForSpawners.isMarked(event.getItem())) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || (block.getType() != Material.SPAWNER && block.getType() != Material.TRIAL_SPAWNER)) {
            return;
        }
        event.setCancelled(true);
        tell.accept(event.getPlayer());
    }
}
