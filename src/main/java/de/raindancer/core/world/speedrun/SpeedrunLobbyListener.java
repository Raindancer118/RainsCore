package de.raindancer.core.world.speedrun;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.messages.Messages;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Joins, clicks and quits, for the one speedrun lobby.
 *
 * <h2>What a join does, and when</h2>
 * Only while {@link SpeedrunLobby#state()} is {@link SpeedrunLobbyState#READY}: a player arriving
 * mid-run, mid-countdown, mid-pause, or into a finished round waiting to reset keeps whatever they
 * were carrying — clearing a spectator's inventory to hand them a compass and a block that does
 * nothing useful yet would be a worse surprise than leaving them alone.
 *
 * <h2>Why the start block does not fix its own participant list</h2>
 * "Everybody currently in the lobby world" is read at the moment of the click, not kept as a
 * roster somebody joins and leaves — the same reasoning {@code FarmWorlds} scatters arrivals rather
 * than pre-registering them. A player who wandered into the lobby world without ever taking the
 * compass off it (dropped, given away) still races if they are standing there when somebody else
 * presses start; that is the whole point of a shared lobby.
 *
 * <h2>Why the inventory is cleared as soon as the countdown begins, not once the run starts</h2>
 * {@link SpeedrunLobby#beginCountdown} freezes participants for a few seconds first — see
 * {@link SpeedrunCountdown} — and there is nothing useful either item can still do once that starts:
 * the menu would only offer to reconfigure a run already under way, and the block cannot be pressed
 * twice into one run. Clearing it now rather than at zero also means the items are gone for the
 * whole countdown, not just visible props for it.
 */
public final class SpeedrunLobbyListener implements Listener {

    private final SpeedrunLobby lobby;
    private final SpeedrunLobbyItems items;
    private final Brand brand;
    private final Messages messages;

    public SpeedrunLobbyListener(SpeedrunLobby lobby, SpeedrunLobbyItems items, Brand brand,
                                 Messages messages) {
        this.lobby = lobby;
        this.items = items;
        this.brand = brand;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (lobby.state() != SpeedrunLobbyState.READY) {
            return;
        }
        items.give(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lobby.resetIfAbandoned(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;   // one click, one action — the off hand fires a second event for the same click
        }
        if (!event.getAction().isRightClick()) {
            return;
        }
        Player player = event.getPlayer();
        var held = event.getItem();
        if (items.isMenu(held)) {
            event.setCancelled(true);
            new SpeedrunLobbyMenu(lobby, messages, brand, player, null).open();
        } else if (items.isStart(held)) {
            event.setCancelled(true);
            startFromLobby(player);
        }
    }

    private void startFromLobby(Player clicker) {
        World lobbyWorld = clicker.getWorld();
        if (!lobbyWorld.getName().equals(lobby.config().worldName())) {
            messages.send(clicker, "speedrun.start.wrong-world", "world", lobby.config().worldName());
            return;
        }
        Set<UUID> present = lobbyWorld.getPlayers().stream()
                .map(Player::getUniqueId)
                .collect(Collectors.toUnmodifiableSet());
        SpeedrunLobby.StartOutcome outcome = lobby.beginCountdown(present);
        if (outcome == SpeedrunLobby.StartOutcome.STARTED) {
            for (Player racer : lobbyWorld.getPlayers()) {
                racer.getInventory().clear();
            }
            return;
        }
        messages.send(clicker, refusalKey(outcome));
    }

    private static String refusalKey(SpeedrunLobby.StartOutcome outcome) {
        return switch (outcome) {
            case NOT_READY -> "speedrun.start.not-ready";
            case NO_END_CONDITION -> "speedrun.start.no-end-condition";
            case NO_PARTICIPANTS -> "speedrun.start.no-participants";
            case WORLD_MISSING -> "speedrun.start.world-missing";
            case STARTED -> throw new IllegalStateException("STARTED is not a refusal");
        };
    }
}
