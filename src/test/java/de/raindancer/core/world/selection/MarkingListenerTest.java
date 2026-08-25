package de.raindancer.core.world.selection;

import de.raindancer.core.world.geometry.ColumnPolygon.Column;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The clicks, and the two mistakes that are only visible from a test: the off-hand event firing for
 * the same physical click, and a corner clicked in a different world than the marking began in.
 */
class MarkingListenerTest {

    private record Recorded(String what, int points) {
    }

    private final List<Recorded> recorded = new ArrayList<>();
    private final MarkingSessions sessions = new MarkingSessions();
    private final UUID playerId = UUID.randomUUID();
    private final ItemStack marker = mock(ItemStack.class);

    private Player player;
    private MarkingListener listener;

    private final MarkingListener.Callback callback = new MarkingListener.Callback() {
        @Override
        public void onVertexAdded(Player who, MarkingSession session) {
            recorded.add(new Recorded("added", session.pointCount()));
        }

        @Override
        public void onVertexRemoved(Player who, MarkingSession session) {
            recorded.add(new Recorded("removed", session.pointCount()));
        }

        @Override
        public void onEmpty(Player who) {
            recorded.add(new Recorded("empty", 0));
        }

        @Override
        public void onCancel(Player who, MarkingSession session) {
            recorded.add(new Recorded("cancelled", session.pointCount()));
        }

        @Override
        public void onFinish(Player who, MarkingSession session) {
            recorded.add(new Recorded("finished", session.pointCount()));
        }
    };

    @BeforeEach
    void setUp() {
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        listener = new MarkingListener(stack -> stack == marker, sessions, callback);
        sessions.begin(playerId, "world", MarkingSession.Mode.POLYGON);
    }

    private Block blockAt(String world, int x, int z) {
        Block block = mock(Block.class);
        World bukkitWorld = mock(World.class);
        when(bukkitWorld.getName()).thenReturn(world);
        when(block.getWorld()).thenReturn(bukkitWorld);
        when(block.getX()).thenReturn(x);
        when(block.getZ()).thenReturn(z);
        return block;
    }

    private PlayerInteractEvent click(Action action, Block block, EquipmentSlot hand) {
        return new PlayerInteractEvent(player, action, marker, block,
                org.bukkit.block.BlockFace.UP, hand);
    }

    @Test
    @DisplayName("right-clicking a block adds that column")
    void rightClickAddsACorner() {
        listener.onInteract(click(Action.RIGHT_CLICK_BLOCK, blockAt("world", 7, 9), EquipmentSlot.HAND));

        assertThat(recorded).containsExactly(new Recorded("added", 1));
        assertThat(sessions.sessionOf(playerId).orElseThrow().vertices()).containsExactly(new Column(7, 9));
    }

    @Test
    @DisplayName("the off-hand event for the same click is ignored, so a corner is not added twice")
    void ignoresTheOffHandEvent() {
        Block block = blockAt("world", 3, 3);

        listener.onInteract(click(Action.RIGHT_CLICK_BLOCK, block, EquipmentSlot.HAND));
        listener.onInteract(click(Action.RIGHT_CLICK_BLOCK, block, EquipmentSlot.OFF_HAND));

        assertThat(sessions.sessionOf(playerId).orElseThrow().pointCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a click in another world is not part of this marking")
    void ignoresAClickInAnotherWorld() {
        listener.onInteract(click(Action.RIGHT_CLICK_BLOCK, blockAt("nether", 1, 1), EquipmentSlot.HAND));

        assertThat(recorded).isEmpty();
        assertThat(sessions.sessionOf(playerId).orElseThrow().pointCount()).isZero();
    }

    @Test
    @DisplayName("the click never reaches the world — marking a wall out does not break the blocks")
    void cancelsTheInteraction() {
        PlayerInteractEvent event = click(Action.RIGHT_CLICK_BLOCK, blockAt("world", 1, 1), EquipmentSlot.HAND);

        listener.onInteract(event);

        assertThat(event.useInteractedBlock()).isEqualTo(org.bukkit.event.Event.Result.DENY);
        assertThat(event.useItemInHand()).isEqualTo(org.bukkit.event.Event.Result.DENY);
    }

    @Test
    @DisplayName("left-clicking takes the last corner back, and says so when there is none")
    void leftClickUndoes() {
        listener.onInteract(click(Action.RIGHT_CLICK_BLOCK, blockAt("world", 1, 1), EquipmentSlot.HAND));
        listener.onInteract(click(Action.LEFT_CLICK_BLOCK, blockAt("world", 1, 1), EquipmentSlot.HAND));
        listener.onInteract(click(Action.LEFT_CLICK_BLOCK, blockAt("world", 1, 1), EquipmentSlot.HAND));

        assertThat(recorded).containsExactly(
                new Recorded("added", 1), new Recorded("removed", 0), new Recorded("empty", 0));
    }

    @Test
    @DisplayName("crouch-right finishes, but only once it is actually a shape")
    void crouchRightFinishesAComplete() {
        when(player.isSneaking()).thenReturn(true);

        listener.onInteract(click(Action.RIGHT_CLICK_AIR, null, EquipmentSlot.HAND));
        assertThat(recorded).containsExactly(new Recorded("empty", 0));

        recorded.clear();
        when(player.isSneaking()).thenReturn(false);
        listener.onInteract(click(Action.RIGHT_CLICK_BLOCK, blockAt("world", 0, 0), EquipmentSlot.HAND));
        listener.onInteract(click(Action.RIGHT_CLICK_BLOCK, blockAt("world", 4, 0), EquipmentSlot.HAND));
        listener.onInteract(click(Action.RIGHT_CLICK_BLOCK, blockAt("world", 4, 4), EquipmentSlot.HAND));
        when(player.isSneaking()).thenReturn(true);
        listener.onInteract(click(Action.RIGHT_CLICK_AIR, null, EquipmentSlot.HAND));

        assertThat(recorded).last().isEqualTo(new Recorded("finished", 3));
    }

    @Test
    @DisplayName("crouch-left gives up")
    void crouchLeftCancels() {
        when(player.isSneaking()).thenReturn(true);

        listener.onInteract(click(Action.LEFT_CLICK_AIR, null, EquipmentSlot.HAND));

        assertThat(recorded).containsExactly(new Recorded("cancelled", 0));
    }

    @Test
    @DisplayName("an ordinary item does nothing at all, even mid-marking")
    void ignoresAnythingButTheTool() {
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                mock(ItemStack.class), blockAt("world", 1, 1), org.bukkit.block.BlockFace.UP,
                EquipmentSlot.HAND);

        listener.onInteract(event);

        assertThat(recorded).isEmpty();
        assertThat(event.useInteractedBlock()).isNotEqualTo(org.bukkit.event.Event.Result.DENY);
    }

    @Test
    @DisplayName("clicking with the tool while not marking does nothing")
    void ignoresTheToolWithoutASession() {
        sessions.clear(playerId);

        listener.onInteract(click(Action.RIGHT_CLICK_BLOCK, blockAt("world", 1, 1), EquipmentSlot.HAND));

        assertThat(recorded).isEmpty();
    }
}
