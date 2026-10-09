package de.raindancer.core.moderation.vanish;

import de.raindancer.core.platform.util.Scheduling;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.function.Predicate;

/**
 * Keeping the promise across joins, leaves and advancements.
 *
 * <p>Three moments vanish usually breaks. Somebody who joins has to be hidden from the person who
 * just arrived — the new player has never been told to hide them — and somebody hidden must not have
 * their arrival announced. All of it is one line each and all of it is always forgotten — including
 * the third: an advancement is broadcast to the whole server the moment it completes, "reached the
 * goal" and the rest, and a vanished player finishing one told everybody exactly what a fake
 * departure was trying to hide. Nobody thinks of that as a chat message vanish owns until a player
 * still "here" announces themselves by winning something.
 */
public final class VanishListener implements Listener {

    private final Plugin plugin;
    private final Vanish vanish;
    private final Predicate<Player> seesVanished;

    public VanishListener(Plugin plugin, Vanish vanish, String seeVanishedPermission) {
        this(plugin, vanish, (Predicate<Player>) player ->
                seeVanishedPermission != null && player.hasPermission(seeVanishedPermission));
    }

    /** @param seesVanished who may see hidden players, asked once per join — see {@link VanishSight} */
    public VanishListener(Plugin plugin, Vanish vanish, Predicate<Player> seesVanished) {
        this.plugin = plugin;
        this.vanish = vanish;
        this.seesVanished = seesVanished;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player joining = event.getPlayer();
        vanish.maySeeVanished(joining.getUniqueId(), seesVanished.test(joining));

        if (vanish.isVanished(joining.getUniqueId())) {
            // Quietly: their own arrival must not be announced, and they have to be hidden again
            // from everybody, since a fresh connection knows nothing about who was hidden. Staff who
            // can see them are told, or a colleague logging in is a tab entry that appeared unannounced.
            Component line = event.joinMessage();
            event.joinMessage(null);
            tellStaff(joining, line != null ? line
                    : Component.translatable("multiplayer.player.joined", Component.text(joining.getName())));
        }
        // Everybody already hidden has to be hidden from the person who just arrived. Without this
        // the newest player is the one person who can see every vanished moderator on the server.
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(joining) && vanish.isVanished(other.getUniqueId())
                    && !vanish.maySeeVanished(joining.getUniqueId())) {
                joining.hidePlayer(plugin, other);
            }
        }
        if (vanish.isVanished(joining.getUniqueId())) {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (!viewer.equals(joining) && !vanish.maySeeVanished(viewer.getUniqueId())) {
                    Scheduling.onOwner(plugin, viewer, () -> viewer.hidePlayer(plugin, joining));
                }
            }
        } else {
            // Somebody revealed while they were logged out could not be shown to anybody then, and
            // every viewer still online kept the hide this plugin put on them. Showing someone who
            // was never hidden costs nothing.
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (!viewer.equals(joining)) {
                    Scheduling.onOwner(plugin, viewer, () -> viewer.showPlayer(plugin, joining));
                }
            }
        }
    }

    /**
     * Silences the server-wide "so-and-so has reached the goal" line for a vanished player.
     *
     * <p>Nulling the message rather than cancelling the event: there is nothing to cancel — the
     * advancement is already granted by the time this fires, and only the broadcast is still
     * pending. A vanished player keeps the advancement itself, exactly as they should; the server
     * simply never finds out.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (vanish.isVanished(event.getPlayer().getUniqueId())) {
            event.message(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        if (vanish.isVanished(event.getPlayer().getUniqueId())) {
            Component line = event.quitMessage();
            event.quitMessage(null);
            tellStaff(event.getPlayer(), line != null ? line : Component.translatable("multiplayer.player.left",
                    Component.text(event.getPlayer().getName())));
        }
        // Whether they may see hidden players is a fact about this session, not about them. Being
        // hidden is not, and deliberately survives — see Vanish#forgetSession.
        vanish.forgetSession(event.getPlayer().getUniqueId());
    }

    /** The join or leave line, marked as vanished, to everybody online who can see vanished players. */
    private void tellStaff(Player who, Component line) {
        Component marked = Component.text("[vanished] ", NamedTextColor.GRAY)
                .append(line.colorIfAbsent(NamedTextColor.GRAY));
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(who) && vanish.maySeeVanished(viewer.getUniqueId())) {
                viewer.sendMessage(marked);
            }
        }
    }
}
