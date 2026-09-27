package de.raindancer.core.content.items;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;

/** Refuses dropping a {@link BoundItems bound} item — by key, from the inventory screen, or by dragging it out. */
public final class BoundItemListener implements Listener {

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (BoundItems.isBound(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }
}
