package de.raindancer.core.world.selection;

import de.raindancer.core.world.geometry.ColumnPolygon;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Drives a {@link MarkingSession} from clicks on a {@link MarkingTool} stick — right-click a block
 * to add a corner, left-click to undo the last one, shift-right to finish, shift-left (on a block or
 * in the air) to cancel. The exact interaction {@code claims-module}'s own {@code SelectionFlow}
 * already trained players on, generalised so a second module marking a different kind of shape does
 * not have to re-teach it.
 *
 * <p>One instance per owning plugin, registered by that plugin — this is mechanics, not a decision:
 * what "finish" builds is entirely {@link Callback#onFinish}'s business.
 */
public final class MarkingListener implements Listener {

    /** What a module wants to happen at each step. Every method may be a no-op. */
    public interface Callback {

        default void onVertexAdded(Player player, MarkingSession session) {
        }

        default void onVertexRemoved(Player player, MarkingSession session) {
        }

        /** Called when the player asks to finish. The session is still open until this returns. */
        void onFinish(Player player, MarkingSession session);

        void onCancel(Player player, MarkingSession session);

        /** Nothing has been marked yet and the player tried to finish or undo anyway. */
        default void onEmpty(Player player) {
        }
    }

    private final MarkingTool tool;
    private final MarkingSessions sessions;
    private final Callback callback;

    public MarkingListener(MarkingTool tool, MarkingSessions sessions, Callback callback) {
        this.tool = tool;
        this.sessions = sessions;
        this.callback = callback;
    }

    @EventHandler(ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!tool.isStick(player.getInventory().getItemInMainHand())) {
            return;
        }
        event.setCancelled(true);

        boolean shift = player.isSneaking();
        Action action = event.getAction();
        boolean rightClick = action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR;
        boolean leftClick = action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR;

        if (shift && rightClick) {
            finish(player);
            return;
        }
        if (shift && leftClick) {
            cancel(player);
            return;
        }
        if (rightClick && action == Action.RIGHT_CLICK_BLOCK) {
            addVertex(player, event.getClickedBlock());
            return;
        }
        if (leftClick && action == Action.LEFT_CLICK_BLOCK) {
            undo(player);
        }
    }

    private void addVertex(Player player, Block block) {
        MarkingSession session = sessions.sessionOf(player.getUniqueId()).orElse(null);
        if (session == null || block == null) {
            return;
        }
        if (!session.worldName().equals(block.getWorld().getName())) {
            return;
        }
        if (!session.accepts()) {
            return;
        }
        session.add(new ColumnPolygon.Column(block.getX(), block.getZ()));
        callback.onVertexAdded(player, session);
    }

    private void undo(Player player) {
        MarkingSession session = sessions.sessionOf(player.getUniqueId()).orElse(null);
        if (session == null) {
            return;
        }
        if (session.undoLast()) {
            callback.onVertexRemoved(player, session);
        } else {
            callback.onEmpty(player);
        }
    }

    private void finish(Player player) {
        MarkingSession session = sessions.sessionOf(player.getUniqueId()).orElse(null);
        if (session == null) {
            callback.onEmpty(player);
            return;
        }
        callback.onFinish(player, session);
    }

    private void cancel(Player player) {
        MarkingSession session = sessions.sessionOf(player.getUniqueId()).orElse(null);
        if (session == null) {
            callback.onEmpty(player);
            return;
        }
        callback.onCancel(player, session);
    }
}
