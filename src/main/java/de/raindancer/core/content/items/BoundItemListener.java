package de.raindancer.core.content.items;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BundleMeta;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Keeps a bound item in its holder's own inventory — the one rule {@link BoundItems} promises.
 *
 * <p>Every way an item leaves a player is a different event, so each has its own handler: thrown,
 * clicked or shift-clicked or number-keyed or dragged into a container (a chest, an ender chest, a
 * shulker box, a furnace, anything that is not the player's own inventory), slipped into a bundle,
 * hung in an item frame, handed to an armour stand, placed as a block or into a block that keeps
 * items, dropped on death, and dropped because the inventory was full when a window closed with it on
 * the cursor. What is refused here is a <em>transfer</em>: using the item — eating it, throwing it as
 * a projectile — is the owning plugin's business.
 */
public final class BoundItemListener implements Listener {

    /** Blocks a right-click puts the held item into, and keeps. */
    private static final Set<Material> TAKES_ITEMS = Set.of(Material.ITEM_FRAME,
            Material.GLOW_ITEM_FRAME, Material.JUKEBOX, Material.LECTERN, Material.DECORATED_POT,
            Material.CHISELED_BOOKSHELF, Material.FLOWER_POT, Material.COMPOSTER, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE, Material.VAULT, Material.CRAFTER);

    private static boolean bound(ItemStack stack) {
        return BoundItems.isBound(stack);
    }

    /** Any bundle, of any colour: what makes one is its meta, not its name. */
    private static boolean isBundle(ItemStack stack) {
        return stack != null && stack.hasItemMeta() && stack.getItemMeta() instanceof BundleMeta;
    }

    /**
     * Whether a window's top half is the player's own — their inventory screen's crafting grid, or
     * creative's. Everything else, an ender chest included, is somewhere an item could be left.
     * A seam, so a test does not need a server to know an inventory's type.
     */
    static Predicate<Inventory> ownTop = top -> top.getType() == InventoryType.CRAFTING
            || top.getType() == InventoryType.CREATIVE;

    // ------------------------------------------------------------------ throwing it away

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (BoundItems.isBound(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ into somebody else's inventory

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (leaves(event)) {
            event.setCancelled(true);
        }
    }

    /** Whether this click would take a bound item out of the clicker's own inventory. */
    static boolean leaves(InventoryClickEvent event) {
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        InventoryAction action = event.getAction();

        // Out of the window altogether: thrown on the floor.
        switch (action) {
            case DROP_ALL_CURSOR, DROP_ONE_CURSOR -> {
                return bound(cursor);
            }
            case DROP_ALL_SLOT, DROP_ONE_SLOT -> {
                return bound(current);
            }
            default -> {
            }
        }
        // A bundle swallows whatever it is clicked with, or whatever it is clicked onto.
        if ((bound(cursor) && isBundle(current)) || (isBundle(cursor) && bound(current))) {
            return true;
        }

        Inventory top = event.getView().getTopInventory();
        if (isOwn(top, event.getWhoClicked())) {
            // Only the player's own inventory and crafting grid are on screen: nothing can leave.
            return false;
        }
        boolean intoTop = event.getClickedInventory() != null
                && event.getClickedInventory().equals(top);
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            // Shift-click from below sends it up into the container.
            return !intoTop && bound(current);
        }
        if (!intoTop) {
            return false;
        }
        if (bound(cursor)) {
            return true;
        }
        // F over a container slot swaps it with the off-hand; a number key with that hotbar slot.
        PlayerInventory own = event.getWhoClicked().getInventory();
        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            return bound(own.getItemInOffHand());
        }
        int button = event.getHotbarButton();
        return button >= 0 && bound(own.getItem(button));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!bound(event.getOldCursor())) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (isOwn(top, event.getWhoClicked())) {
            return;
        }
        int topSize = top.getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** The player's own inventory view: its top half is their 2x2 crafting grid. */
    private static boolean isOwn(Inventory top, HumanEntity who) {
        return top == null || top.equals(who.getInventory()) || ownTop.test(top);
    }

    /**
     * Machines moving items between containers. A bound item should never be in one — this is only
     * for one that got there before it was bound, or by a route nothing above knows about.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (bound(event.getItem()) && event.getSource() instanceof PlayerInventory) {
            event.setCancelled(true);
        }
    }

    /**
     * Closing a window with a bound item on the cursor puts it back into the inventory — or, when
     * that is full, drops it. Room is made first by dropping something that is not bound instead.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClose(InventoryCloseEvent event) {
        HumanEntity who = event.getPlayer();
        ItemStack cursor = who.getItemOnCursor();
        if (!bound(cursor)) {
            return;
        }
        who.setItemOnCursor(null);
        Map<Integer, ItemStack> left = who.getInventory().addItem(cursor);
        for (ItemStack leftover : left.values()) {
            placeOverSomethingUnbound(who, leftover);
        }
    }

    private static void placeOverSomethingUnbound(HumanEntity who, ItemStack keeping) {
        PlayerInventory inventory = who.getInventory();
        for (int slot = 0; slot < inventory.getStorageContents().length; slot++) {
            ItemStack there = inventory.getItem(slot);
            if (there != null && !bound(there)) {
                inventory.setItem(slot, keeping);
                who.getWorld().dropItemNaturally(who.getLocation(), there);
                return;
            }
        }
        // Every slot holds a bound item: nothing can give way, so it goes back on the cursor
        // rather than on the floor.
        who.setItemOnCursor(keeping);
    }

    // ------------------------------------------------------------------ into the world

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof ItemFrame
                && bound(event.getPlayer().getInventory().getItem(event.getHand()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (bound(event.getPlayerItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (bound(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && block != null
                && TAKES_ITEMS.contains(block.getType()) && bound(event.getItem())) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    // ------------------------------------------------------------------ dying

    /** Kept rather than dropped: a bound item that could be dropped by dying could be handed over. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Iterator<ItemStack> drops = event.getDrops().iterator();
        while (drops.hasNext()) {
            ItemStack drop = drops.next();
            if (bound(drop)) {
                drops.remove();
                event.getItemsToKeep().add(drop);
            }
        }
    }
}
