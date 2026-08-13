package de.raindancer.core.world.speedrun;

import de.raindancer.core.ui.messages.Messages;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpeedrunLobbyListenerTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());

    private SpeedrunLobby lobby;
    private SpeedrunLobbyItems items;
    private Messages messages;
    private SpeedrunLobbyListener listener;

    @BeforeEach
    void setUp() {
        lobby = mock(SpeedrunLobby.class);
        items = mock(SpeedrunLobbyItems.class);
        messages = mock(Messages.class);
        listener = new SpeedrunLobbyListener(lobby, items, null, messages);
    }

    private Player playerWithId(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    @Nested
    @DisplayName("join")
    class Join {

        private Player playerInWorld(String worldName) {
            Player player = playerWithId(ALICE);
            World world = mock(World.class);
            when(world.getName()).thenReturn(worldName);
            when(player.getWorld()).thenReturn(world);
            return player;
        }

        @Test
        @DisplayName("gives the kit while the lobby is READY and the player is in the lobby world")
        void givesKitWhenReadyInLobbyWorld() {
            when(lobby.state()).thenReturn(SpeedrunLobbyState.READY);
            when(lobby.config()).thenReturn(
                    new SpeedrunSettings("world", "minecraft:end/kill_dragon", SpeedrunDeathPolicy.OFF));
            Player player = playerInWorld("world");

            listener.onJoin(new PlayerJoinEvent(player, "hi"));

            verify(items).give(player);
        }

        /**
         * The actual incident this guards: with no world check at all, the lobby being READY — which
         * is true almost all the time on a shared server — meant every join anywhere on the server was
         * cleared and handed the two lobby items, regardless of which world the player actually spawned
         * into. Real gear was lost this way before this check existed.
         */
        @Test
        @DisplayName("does NOT touch a player joining into a different world, even while READY")
        void leavesInventoryAloneOutsideTheLobbyWorld() {
            when(lobby.state()).thenReturn(SpeedrunLobbyState.READY);
            when(lobby.config()).thenReturn(
                    new SpeedrunSettings("speedrun-lobby", "minecraft:end/kill_dragon",
                            SpeedrunDeathPolicy.OFF));
            Player player = playerInWorld("world");   // the server's real, shared world — not the lobby

            listener.onJoin(new PlayerJoinEvent(player, "hi"));

            verify(items, never()).give(any());
        }

        @Test
        @DisplayName("leaves the inventory alone while a run is in progress")
        void leavesInventoryAloneWhileRunning() {
            when(lobby.state()).thenReturn(SpeedrunLobbyState.RUNNING);
            Player player = playerWithId(ALICE);

            listener.onJoin(new PlayerJoinEvent(player, "hi"));

            verify(items, never()).give(any());
        }

        @Test
        @DisplayName("leaves the inventory alone while a finished run waits to reset")
        void leavesInventoryAloneWhileFinished() {
            when(lobby.state()).thenReturn(SpeedrunLobbyState.FINISHED);
            Player player = playerWithId(ALICE);

            listener.onJoin(new PlayerJoinEvent(player, "hi"));

            verify(items, never()).give(any());
        }
    }

    @Test
    @DisplayName("quit always asks the lobby whether it should reset")
    void quitAsksLobby() {
        Player player = playerWithId(ALICE);

        listener.onQuit(new PlayerQuitEvent(player, "bye"));

        verify(lobby).resetIfAbandoned(ALICE);
    }

    @Nested
    @DisplayName("clicking the start block")
    class StartClick {

        private Player clicker;
        private World lobbyWorld;
        private ItemStack startBlock;

        @BeforeEach
        void setUp() {
            clicker = playerWithId(ALICE);
            lobbyWorld = mock(World.class);
            when(lobbyWorld.getName()).thenReturn("world");
            when(clicker.getWorld()).thenReturn(lobbyWorld);
            PlayerInventory inv = mock(PlayerInventory.class);
            when(clicker.getInventory()).thenReturn(inv);

            startBlock = mock(ItemStack.class);
            when(items.isStart(startBlock)).thenReturn(true);
            when(items.isMenu(startBlock)).thenReturn(false);

            SpeedrunSettings config = new SpeedrunSettings("world", "minecraft:end/kill_dragon",
                    SpeedrunDeathPolicy.OFF);
            when(lobby.config()).thenReturn(config);
        }

        @Test
        @DisplayName("does nothing when the block is used outside the lobby world, and says so")
        void ignoredOutsideLobbyWorld() {
            when(lobbyWorld.getName()).thenReturn("somewhere-else");
            PlayerInteractEvent event = new PlayerInteractEvent(clicker, Action.RIGHT_CLICK_BLOCK,
                    startBlock, null, null, EquipmentSlot.HAND);

            listener.onInteract(event);

            verify(lobby, never()).beginCountdown(any());
            verify(messages).send(clicker, "speedrun.start.wrong-world", "world", "world");
        }

        @Test
        @DisplayName("begins the countdown with everybody currently in the lobby world, and clears their inventories")
        void startsAndClearsInventoriesOnSuccess() {
            Player other = playerWithId(UUID.nameUUIDFromBytes("bob".getBytes()));
            PlayerInventory otherInv = mock(PlayerInventory.class);
            when(other.getInventory()).thenReturn(otherInv);
            when(lobbyWorld.getPlayers()).thenReturn(List.of(clicker, other));
            when(lobby.beginCountdown(any())).thenReturn(SpeedrunLobby.StartOutcome.STARTED);

            PlayerInteractEvent event = new PlayerInteractEvent(clicker, Action.RIGHT_CLICK_BLOCK,
                    startBlock, null, null, EquipmentSlot.HAND);
            listener.onInteract(event);

            assertThat(event.useItemInHand()).isEqualTo(org.bukkit.event.Event.Result.DENY);
            verify(lobby).beginCountdown(java.util.Set.of(ALICE, other.getUniqueId()));
            verify(clicker.getInventory()).clear();
            verify(other.getInventory()).clear();
        }

        @Test
        @DisplayName("a refused start leaves inventories untouched, and tells the clicker why")
        void refusedStartLeavesInventoriesAlone() {
            when(lobbyWorld.getPlayers()).thenReturn(List.of(clicker));
            when(lobby.beginCountdown(any())).thenReturn(SpeedrunLobby.StartOutcome.NO_END_CONDITION);

            PlayerInteractEvent event = new PlayerInteractEvent(clicker, Action.RIGHT_CLICK_BLOCK,
                    startBlock, null, null, EquipmentSlot.HAND);
            listener.onInteract(event);

            assertThat(event.useItemInHand()).isEqualTo(org.bukkit.event.Event.Result.DENY);
            verify(clicker.getInventory(), never()).clear();
            verify(messages).send(clicker, "speedrun.start.no-end-condition");
        }
    }

    @Nested
    @DisplayName("clicks that are not this listener's business")
    class Ignored {

        @Test
        @DisplayName("the off hand's copy of the same click is ignored")
        void offHandIgnored() {
            Player player = playerWithId(ALICE);
            ItemStack held = mock(ItemStack.class);
            PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, held,
                    null, null, EquipmentSlot.OFF_HAND);

            listener.onInteract(event);

            verify(items, never()).isMenu(any());
            verify(items, never()).isStart(any());
        }

        @Test
        @DisplayName("a left click is not a menu or start action")
        void leftClickIgnored() {
            Player player = playerWithId(ALICE);
            ItemStack held = mock(ItemStack.class);
            PlayerInteractEvent event = new PlayerInteractEvent(player, Action.LEFT_CLICK_AIR, held,
                    null, null, EquipmentSlot.HAND);

            listener.onInteract(event);

            verify(items, never()).isMenu(any());
        }

        @Test
        @DisplayName("holding neither tagged item does nothing")
        void unrelatedItemIgnored() {
            Player player = playerWithId(ALICE);
            ItemStack held = mock(ItemStack.class);
            PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, held,
                    null, null, EquipmentSlot.HAND);

            listener.onInteract(event);

            // isCancelled() itself is not a reliable signal here: with no clicked block the event
            // already reads as cancelled by Bukkit's own default (useInteractedBlock() == DENY for a
            // null block) before this listener ever runs. useItemInHand() is what setCancelled(true)
            // actually touches, so it is the precise check that this listener did nothing.
            assertThat(event.useItemInHand()).isEqualTo(org.bukkit.event.Event.Result.DEFAULT);
        }
    }
}
