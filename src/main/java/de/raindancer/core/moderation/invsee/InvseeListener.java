package de.raindancer.core.moderation.invsee;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

import java.util.Set;
import java.util.UUID;

/**
 * Everything the server has to tell an inventory window about.
 *
 * <h2>The login half, and what it deliberately does not do</h2>
 * A player joining while their save file is open in somebody's window is the failure this whole
 * feature is built around: the server reads their file, keeps them in memory, and writes them back
 * on the way out, so a moderator's edit would be discarded without a word.
 *
 * <p>The obvious fix is to hold that one login for a few seconds. This does not do that, and will
 * not: a player turned away by a server they have done nothing wrong on concludes the server is
 * broken, and no moderator's convenience is worth that. <b>The arriving player always wins.</b>
 * Their join goes through untouched; the window shuts and the edit is dropped unwritten. Nothing
 * real is lost, because nothing had been written yet.
 *
 * <h2>And why clicks are read rather than interpreted</h2>
 * Minecraft has about a dozen ways to move an item, and a handler that works out what each of them
 * meant gets one of them wrong. This one decides only whether a click is <em>allowed</em>, and then
 * reads what the window actually holds a tick later.
 */
public final class InvseeListener implements Listener {

    private static final LogChannel log = Log.of("invsee");

    private final Inventories inventories;

    public InvseeListener(Inventories inventories) {
        this.inventories = inventories;
    }

    // -------------------------------------------------------------------------- the windows

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryWindow window = windowOf(event.getInventory());
        if (window == null) {
            return;
        }
        inventories.stillLooking(window);

        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            // Double-clicking gathers every matching stack the player can see into the cursor —
            // including the ones in the window above, and without a single click event ever naming
            // those slots. A read-only moderator could empty somebody's backpack into their hand
            // with two clicks in their own inventory, and the window would never know.
            //
            // Cancelled outright rather than worked out slot by slot: the gesture has no way to be
            // told "these slots but not those", and losing one convenience is not a price worth
            // arguing about against duplicating items.
            event.setCancelled(true);
            return;
        }

        boolean clickedOwnInventory = event.getClickedInventory() != null
                && event.getClickedInventory().getHolder() != window;
        if (clickedOwnInventory) {
            // Their own inventory, below the window. Taking an item out of it is fine; shift-clicking
            // it upwards is not, because the game chooses where it lands and its choice includes the
            // chrome row and the armour slots a read-only moderator may not touch.
            if (event.isShiftClick()) {
                event.setCancelled(true);
            }
            return;
        }
        if (window.isPageButton(event.getRawSlot())) {
            event.setCancelled(true);
            window.turnPage(event.getRawSlot());
            return;
        }
        if (window.shouldCancel(event.getRawSlot())) {
            event.setCancelled(true);
            return;
        }
        // Allowed. What it actually did is read back a tick later, when the window holds the result
        // rather than what it held before.
        window.syncSoon();
    }

    /**
     * A drag can cross both inventories at once, which is how an item reaches a slot without a
     * single click event ever naming it.
     */
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryWindow window = windowOf(event.getInventory());
        if (window == null) {
            return;
        }
        inventories.stillLooking(window);
        for (int slot : event.getRawSlots()) {
            if (slot < event.getInventory().getSize() && window.shouldCancel(slot)) {
                event.setCancelled(true);
                return;
            }
        }
        window.syncSoon();
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        InventoryWindow window = windowOf(event.getInventory());
        if (window == null) {
            return;
        }
        // Read once more first: a drag that ended with no click, or an item dropped in and the
        // window shut in the same breath, has not been taken yet.
        window.sync();
        // Whether the write succeeded is answered later and on the moderator's own thread — it is a
        // file, and this handler is on the thread running the world.
        inventories.closed(window);
    }

    // ------------------------------------------------------------------------- coming and going

    /**
     * Somebody is logging in: any edit of their save file yields to them.
     *
     * <p>At {@code LOWEST} because this has to be decided before the server reads their file, not
     * because it might refuse — it never refuses. The login is not touched at all. What happens is
     * that the pending write is cancelled and the moderator's window is shut, so the file the server
     * is about to read is the one its owner left.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(PlayerLoginEvent event) {
        inventories.somebodyJoined(event.getPlayer().getUniqueId());
    }

    /**
     * Somebody leaving closes every window onto them and lets go of everything they held.
     *
     * <p>Both halves matter. A window onto somebody who has logged out writes its changes to nobody;
     * and a moderator who logs out still holding a lock would keep it until the server restarted.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID who = event.getPlayer().getUniqueId();
        Set<UUID> watchers = inventories.views().ownerLeft(who);
        if (!watchers.isEmpty()) {
            log.debug("{} logged out; {} window(s) onto them were closed.",
                    event.getPlayer().getName(), watchers.size());
        }
        inventories.views().watcherLeft(who);
        inventories.offlineEdits().editorLeft(who);
    }

    private static InventoryWindow windowOf(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof InventoryWindow window
                ? window : null;
    }
}
