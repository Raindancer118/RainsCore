package de.raindancer.core.content.items;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** "Bound" means it never leaves its holder's own inventory, by any of the ways an item can. */
@DisplayName("a bound item never leaves its holder")
class BoundItemListenerTest {

    private final BoundItemListener listener = new BoundItemListener();
    private final Player player = mock(Player.class);
    private final PlayerInventory own = mock(PlayerInventory.class);
    private final ItemStack bound = stack(Material.STONE, true);
    private final ItemStack plain = stack(Material.STONE, false);

    private static ItemStack stack(Material type, boolean bound) {
        ItemStack stack = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(stack.getType()).thenReturn(type);
        when(stack.hasItemMeta()).thenReturn(true);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.has(BoundItems.KEY, PersistentDataType.BYTE)).thenReturn(bound);
        return stack;
    }

    @BeforeEach
    void setUp() {
        when(player.getInventory()).thenReturn(own);
    }

    private static final String CRAFTING = "own";

    private final java.util.function.Predicate<Inventory> realOwnTop = BoundItemListener.ownTop;

    @BeforeEach
    void ownTopByName() {
        // An inventory's type cannot be read without a server; the test names its windows instead.
        BoundItemListener.ownTop = top -> CRAFTING.equals(top.toString());
    }

    @org.junit.jupiter.api.AfterEach
    void restore() {
        BoundItemListener.ownTop = realOwnTop;
    }

    private InventoryClickEvent click(String top, boolean clickedTop, InventoryAction action,
                                      ClickType type, ItemStack cursor, ItemStack current, int hotbar) {
        Inventory topInventory = mock(Inventory.class);
        when(topInventory.toString()).thenReturn(top);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(topInventory);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getClickedInventory()).thenReturn(clickedTop ? topInventory : own);
        when(event.getAction()).thenReturn(action);
        when(event.getClick()).thenReturn(type);
        when(event.getCursor()).thenReturn(cursor);
        when(event.getCurrentItem()).thenReturn(current);
        when(event.getHotbarButton()).thenReturn(hotbar);
        return event;
    }

    @Nested
    @DisplayName("clicking")
    class Clicking {

        @Test
        @DisplayName("placed from the cursor into a chest, ender chest or shulker box: refused")
        void cursorIntoAContainer() {
            for (String type : List.of("chest", "ender chest", "shulker box", "hopper", "furnace")) {
                assertThat(BoundItemListener.leaves(click(type, true, InventoryAction.PLACE_ALL,
                        ClickType.LEFT, bound, null, -1))).as(type).isTrue();
            }
        }

        @Test
        @DisplayName("shift-clicked up from the player's inventory into a container: refused")
        void shiftClickedUp() {
            assertThat(BoundItemListener.leaves(click("chest", false,
                    InventoryAction.MOVE_TO_OTHER_INVENTORY, ClickType.SHIFT_LEFT, null, bound, -1)))
                    .isTrue();
        }

        @Test
        @DisplayName("number-keyed or off-hand-swapped into a container slot: refused")
        void swappedIn() {
            when(own.getItem(3)).thenReturn(bound);
            when(own.getItemInOffHand()).thenReturn(bound);

            assertThat(BoundItemListener.leaves(click("chest", true,
                    InventoryAction.HOTBAR_SWAP, ClickType.NUMBER_KEY, null, plain, 3))).isTrue();
            assertThat(BoundItemListener.leaves(click("chest", true,
                    InventoryAction.HOTBAR_SWAP, ClickType.SWAP_OFFHAND, null, plain, -1))).isTrue();
        }

        @Test
        @DisplayName("thrown out of the window by Q or by clicking outside: refused")
        void droppedFromTheWindow() {
            assertThat(BoundItemListener.leaves(click(CRAFTING, false,
                    InventoryAction.DROP_ALL_SLOT, ClickType.CONTROL_DROP, null, bound, -1))).isTrue();
            assertThat(BoundItemListener.leaves(click(CRAFTING, false,
                    InventoryAction.DROP_ALL_CURSOR, ClickType.LEFT, bound, null, -1))).isTrue();
        }

        @Test
        @DisplayName("put into a bundle, or a bundle clicked onto it: refused")
        void bundles() {
            ItemStack bundle = mock(ItemStack.class);
            when(bundle.hasItemMeta()).thenReturn(true);
            org.bukkit.inventory.meta.BundleMeta meta = mock(org.bukkit.inventory.meta.BundleMeta.class);
            when(meta.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
            when(bundle.getItemMeta()).thenReturn(meta);

            assertThat(BoundItemListener.leaves(click(CRAFTING, false,
                    InventoryAction.SWAP_WITH_CURSOR, ClickType.RIGHT, bound, bundle, -1))).isTrue();
            assertThat(BoundItemListener.leaves(click(CRAFTING, false,
                    InventoryAction.SWAP_WITH_CURSOR, ClickType.RIGHT, bundle, bound, -1))).isTrue();
        }

        @Test
        @DisplayName("moved about inside the player's own inventory: allowed")
        void ownInventoryIsFine() {
            assertThat(BoundItemListener.leaves(click(CRAFTING, false,
                    InventoryAction.PLACE_ALL, ClickType.LEFT, bound, null, -1))).isFalse();
            assertThat(BoundItemListener.leaves(click("chest", false,
                    InventoryAction.PLACE_ALL, ClickType.LEFT, bound, null, -1)))
                    .as("into their own half of a chest window").isFalse();
        }

        @Test
        @DisplayName("ordinary items are not this listener's business")
        void plainItems() {
            assertThat(BoundItemListener.leaves(click("chest", true,
                    InventoryAction.PLACE_ALL, ClickType.LEFT, plain, null, -1))).isFalse();
            assertThat(BoundItemListener.leaves(click("chest", false,
                    InventoryAction.MOVE_TO_OTHER_INVENTORY, ClickType.SHIFT_LEFT, null, plain, -1)))
                    .isFalse();
        }
    }

    @Test
    @DisplayName("dragged across a container's slots: refused; across the player's own: allowed")
    void dragging() {
        Inventory top = mock(Inventory.class);
        when(top.toString()).thenReturn("chest");
        when(top.getSize()).thenReturn(27);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(top);

        InventoryDragEvent intoChest = mock(InventoryDragEvent.class);
        when(intoChest.getView()).thenReturn(view);
        when(intoChest.getWhoClicked()).thenReturn(player);
        when(intoChest.getOldCursor()).thenReturn(bound);
        when(intoChest.getRawSlots()).thenReturn(Set.of(5, 40));
        listener.onDrag(intoChest);
        verify(intoChest).setCancelled(true);

        InventoryDragEvent below = mock(InventoryDragEvent.class);
        when(below.getView()).thenReturn(view);
        when(below.getWhoClicked()).thenReturn(player);
        when(below.getOldCursor()).thenReturn(bound);
        when(below.getRawSlots()).thenReturn(Set.of(30, 40));
        listener.onDrag(below);
        verify(below, never()).setCancelled(true);
    }

    @Test
    @DisplayName("on death it is kept, not dropped for somebody else to pick up")
    void death() {
        List<ItemStack> drops = new ArrayList<>(List.of(bound, plain));
        List<ItemStack> kept = new ArrayList<>();
        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        when(event.getDrops()).thenReturn(drops);
        when(event.getItemsToKeep()).thenReturn(kept);

        listener.onDeath(event);

        assertThat(drops).containsExactly(plain);
        assertThat(kept).containsExactly(bound);
    }

    @Nested
    @DisplayName("closing a window with it on the cursor")
    class Closing {

        private InventoryCloseEvent closing() {
            InventoryCloseEvent event = mock(InventoryCloseEvent.class);
            when(event.getPlayer()).thenReturn(player);
            when(player.getItemOnCursor()).thenReturn(bound);
            when(player.getWorld()).thenReturn(mock(World.class));
            when(player.getLocation()).thenReturn(mock(Location.class));
            return event;
        }

        @Test
        @DisplayName("it goes back into the inventory")
        void backIn() {
            InventoryCloseEvent event = closing();
            when(own.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());

            listener.onClose(event);

            verify(own).addItem(bound);
            verify(player).setItemOnCursor(null);
        }

        @Test
        @DisplayName("with a full inventory, something unbound is dropped to make room instead")
        void somethingElseGivesWay() {
            InventoryCloseEvent event = closing();
            HashMap<Integer, ItemStack> left = new HashMap<>(Map.of(0, bound));
            when(own.addItem(any(ItemStack[].class))).thenReturn(left);
            when(own.getStorageContents()).thenReturn(new ItemStack[36]);
            when(own.getItem(0)).thenReturn(bound);
            when(own.getItem(1)).thenReturn(plain);

            listener.onClose(event);

            verify(own).setItem(1, bound);
            verify(player.getWorld()).dropItemNaturally(any(), any());
        }
    }

    @Test
    @DisplayName("hung in an item frame, given to an armour stand or placed as a block: refused")
    void intoTheWorld() {
        when(own.getItem(EquipmentSlot.HAND)).thenReturn(bound);
        PlayerInteractEntityEvent frame = new PlayerInteractEntityEvent(player, mock(ItemFrame.class),
                EquipmentSlot.HAND);
        listener.onEntityInteract(frame);
        assertThat(frame.isCancelled()).isTrue();

        PlayerArmorStandManipulateEvent stand = mock(PlayerArmorStandManipulateEvent.class);
        when(stand.getPlayerItem()).thenReturn(bound);
        listener.onArmorStand(stand);
        verify(stand).setCancelled(true);

        BlockPlaceEvent place = mock(BlockPlaceEvent.class);
        when(place.getItemInHand()).thenReturn(bound);
        listener.onPlace(place);
        verify(place).setCancelled(true);
    }

    @Test
    @DisplayName("put into a block that keeps items — a jukebox, a lectern, a pot: refused")
    void intoABlock() {
        Block jukebox = mock(Block.class);
        when(jukebox.getType()).thenReturn(Material.JUKEBOX);
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getClickedBlock()).thenReturn(jukebox);
        when(event.getItem()).thenReturn(bound);

        listener.onInteract(event);

        verify(event).setUseInteractedBlock(Event.Result.DENY);
        verify(event).setUseItemInHand(Event.Result.DENY);
    }

    @Test
    @DisplayName("an armour stand handed something ordinary is left alone")
    void ordinaryStand() {
        PlayerArmorStandManipulateEvent stand = mock(PlayerArmorStandManipulateEvent.class);
        when(stand.getPlayerItem()).thenReturn(plain);
        when(stand.getRightClicked()).thenReturn(mock(ArmorStand.class));

        listener.onArmorStand(stand);

        verify(stand, never()).setCancelled(true);
    }
}
