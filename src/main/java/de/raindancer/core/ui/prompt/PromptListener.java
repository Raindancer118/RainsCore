package de.raindancer.core.ui.prompt;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * The one chat listener that answers prompts, for every plugin on the server.
 *
 * <h2>Why exactly one</h2>
 * Because the thing being shared is "the next line this player types", and two listeners both
 * claiming it is the whole problem. Every plugin goes through {@link ChatPrompts} instead of
 * registering its own, and this is the only place a chat event is read for that purpose.
 *
 * <p>{@code LOWEST} priority so a prompt answer never reaches the plugins that format and broadcast
 * chat: a player typing their new claim's name should not see it appear in public chat first.
 */
public final class PromptListener implements Listener {

    private final ChatPrompts prompts;

    public PromptListener(ChatPrompts prompts) {
        this.prompts = prompts;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        String line = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (prompts.offer(event.getPlayer().getUniqueId(), line).wasConsumed()) {
            // It was an answer, so it is not also a chat message. Nobody meant to say "my base" to
            // the whole server.
            event.setCancelled(true);
        }
    }

    /** Somebody who logs out mid-question is not still being asked when they come back. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        prompts.forget(event.getPlayer().getUniqueId());
    }
}
