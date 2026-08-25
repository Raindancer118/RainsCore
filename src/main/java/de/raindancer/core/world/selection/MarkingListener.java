package de.raindancer.core.world.selection;

import de.raindancer.core.world.geometry.ColumnPolygon.Column;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.Event;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * The clicks that mark a shape out: right-click a block to add a corner, left-click to take the last
 * one back, shift-right to finish, shift-left to give up.
 *
 * <p>What each of those <em>means</em> is the caller's, through {@link Callback} — Core knows how a
 * shape is marked, never what is built from it.
 */
public final class MarkingListener implements Listener {

    /** What the host does with a marking as it happens. */
    public interface Callback {

        void onVertexAdded(Player player, MarkingSession session);

        void onVertexRemoved(Player player, MarkingSession session);

        /** Undo with nothing to undo, or finishing with too few corners to be a shape. */
        void onEmpty(Player player);

        void onCancel(Player player, MarkingSession session);

        void onFinish(Player player, MarkingSession session);
    }

    private final Predicate<ItemStack> isMarkingTool;
    private final MarkingSessions sessions;
    private final Callback callback;

    public MarkingListener(MarkingTool tool, MarkingSessions sessions, Callback callback) {
        this(tool::isMarkingTool, sessions, callback);
    }

    /**
     * The same, told only how to recognise the tool.
     *
     * <p>What this listener actually needs from a {@link MarkingTool} is one question, and taking
     * the question rather than the tool is what lets the clicks be tested without a server to write
     * persistent data with.
     */
    public MarkingListener(Predicate<ItemStack> isMarkingTool, MarkingSessions sessions, Callback callback) {
        this.isMarkingTool = isMarkingTool;
        this.sessions = sessions;
        this.callback = callback;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        // Off-hand fires a second event for the same physical click, and acting on both adds every
        // corner twice.
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!isMarkingTool.test(event.getItem())) {
            return;
        }
        Optional<MarkingSession> maybeSession = sessions.sessionOf(player.getUniqueId());
        if (maybeSession.isEmpty()) {
            return;
        }
        MarkingSession session = maybeSession.get();

        // Denied regardless of which click it was: the tool is a tool, and letting the click through
        // means marking a shape out also breaks the blocks it is marked on. Both halves — the block
        // and the item — because denying only the block still lets the item be used on it.
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);

        boolean crouching = player.isSneaking();
        Action action = event.getAction();
        boolean rightClick = action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR;
        boolean leftClick = action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR;

        if (crouching && rightClick) {
            finish(player, session);
            return;
        }
        if (crouching && leftClick) {
            callback.onCancel(player, session);
            return;
        }
        if (leftClick) {
            if (session.undo()) {
                callback.onVertexRemoved(player, session);
            } else {
                callback.onEmpty(player);
            }
            return;
        }
        if (rightClick) {
            Block block = event.getClickedBlock();
            if (block == null) {
                return;
            }
            // A shape marked half in one world and half in another is not a shape. The session
            // remembers where it began, and a click somewhere else is simply not part of it.
            if (!block.getWorld().getName().equals(session.world())) {
                return;
            }
            if (session.add(new Column(block.getX(), block.getZ()))) {
                callback.onVertexAdded(player, session);
            }
        }
    }

    /** A player who logs out mid-marking is not still marking when they come back. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.clear(event.getPlayer().getUniqueId());
    }

    private void finish(Player player, MarkingSession session) {
        if (!session.isComplete()) {
            callback.onEmpty(player);
            return;
        }
        callback.onFinish(player, session);
    }
}
