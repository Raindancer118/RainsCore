package de.raindancer.core.moderation;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

/**
 * Where a punishment stops being a record and starts stopping somebody.
 *
 * <p>Three lines each, deliberately: every decision is {@link PunishmentGuard}'s, which is tested
 * without a server. This only asks and applies the answer.
 *
 * <h2>Why the login check is the async pre-login one</h2>
 * {@code AsyncPlayerPreLoginEvent} fires before the player object exists, which is early enough that
 * a banned player never joins at all — no join message, no chunks loaded for them, nothing else's
 * join handler run. {@code PlayerLoginEvent} would work too and is later; this is cheaper and
 * quieter, and a ban should be as though they had never knocked.
 */
public final class PunishmentListener implements Listener {

    private final PunishmentGuard guard;

    public PunishmentListener(PunishmentGuard guard) {
        this.guard = guard;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        guard.joinRefusal(event.getUniqueId()).ifPresent(screen ->
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, screen));
    }

    /**
     * A muted player's message never reaches anybody.
     *
     * <p>{@code ignoreCancelled} so a message another plugin has already stopped is not answered
     * twice, and early so this is decided before anything spends work formatting a message that is
     * not going to be sent.
     *
     * <p>{@code LOW} rather than {@code LOWEST} on purpose, and the one tick lower matters:
     * {@code PromptListener} sits at {@code LOWEST} and consumes lines that are answers to a
     * question rather than chat. If this ran first a muted player could not answer a settings
     * prompt or name a claim — and a mute means "you may not talk to people", not "you may not use
     * the menus". A prompt answer never reaches anybody either way.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        guard.speakRefusal(event.getPlayer().getUniqueId()).ifPresent(reason -> {
            event.setCancelled(true);
            event.getPlayer().sendMessage(reason);
        });
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        guard.buildRefusal(event.getPlayer().getUniqueId()).ifPresent(reason -> {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(reason);
        });
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        guard.buildRefusal(event.getPlayer().getUniqueId()).ifPresent(reason -> {
            event.setCancelled(true);
            // The action bar rather than chat: somebody frozen who keeps trying would otherwise
            // fill their own chat with the same line.
            event.getPlayer().sendActionBar(reason);
        });
    }
}
