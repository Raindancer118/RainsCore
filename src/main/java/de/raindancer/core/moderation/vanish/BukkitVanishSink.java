package de.raindancer.core.moderation.vanish;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * The handful of calls that actually make somebody invisible.
 *
 * <p>Everything about who is hidden, who may see them and what should be restored afterwards is on
 * the other side of {@link VanishSink} and is tested without a server.
 *
 * <p>Uses {@code hidePlayer} rather than an invisibility effect on purpose. An invisible player is
 * still in the tablist, still in the player list, still bumps into things and still shows their
 * armour — which is not hidden, it is translucent.
 */
public final class BukkitVanishSink implements VanishSink {

    private static final LogChannel log = Log.of("vanish");

    private final Plugin plugin;

    public BukkitVanishSink(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void hide(UUID who, java.util.Set<UUID> mayStillSee) {
        Player target = Bukkit.getPlayer(who);
        if (target == null) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(target) && !mayStillSee.contains(viewer.getUniqueId())) {
                viewer.hidePlayer(plugin, target);
            }
        }
    }

    @Override
    public void show(UUID who) {
        Player target = Bukkit.getPlayer(who);
        if (target == null) {
            return;
        }
        // Shown to everybody, including those who could already see them: showPlayer on somebody
        // who was never hidden is harmless, and missing one leaves a player invisible to one person
        // for the rest of the session with nothing to explain it.
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            viewer.showPlayer(plugin, target);
        }
    }

    @Override
    public void allowFlight(UUID who, boolean allowed) {
        Player target = Bukkit.getPlayer(who);
        if (target == null) {
            return;
        }
        target.setAllowFlight(allowed);
        if (!allowed && target.isFlying()) {
            // Set to not flying first, or the client and server disagree about where they are and
            // the player is rubber-banded back into the air.
            target.setFlying(false);
        }
    }

    @Override
    public void collidable(UUID who, boolean collides) {
        Player target = Bukkit.getPlayer(who);
        if (target != null) {
            target.setCollidable(collides);
        }
    }

    /**
     * The vanilla leave line, to everybody who cannot see them.
     *
     * <p>{@code translatable("multiplayer.player.left")} rather than a string of our own: that is the
     * key the client itself renders, so the line is in the reader's own language and is byte-identical
     * to a real departure. A hand-written "X left the game" is in English on a German client, which is
     * precisely the tell this exists to remove.
     */
    @Override
    public void announceDeparture(UUID who, java.util.Set<UUID> exceptThem) {
        announce(who, exceptThem, "multiplayer.player.left");
    }

    @Override
    public void announceArrival(UUID who, java.util.Set<UUID> exceptThem) {
        announce(who, exceptThem, "multiplayer.player.joined");
    }

    private void announce(UUID who, java.util.Set<UUID> exceptThem, String key) {
        Player target = Bukkit.getPlayer(who);
        if (target == null) {
            return;
        }
        net.kyori.adventure.text.Component line = net.kyori.adventure.text.Component
                .translatable(key, net.kyori.adventure.text.format.NamedTextColor.YELLOW,
                        net.kyori.adventure.text.Component.text(target.getName()));
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            // Not to anybody who can see them anyway: telling the staff a moderator "left" while
            // they can still see them standing there is worse than saying nothing.
            //
            // But *always* to them. They are the one person who needs to know it happened, and
            // withholding it made the whole feature unverifiable: alone on a test server, or with
            // only staff online, the line went to nobody and looked exactly like a feature that had
            // never been written. Two evenings went on deciding which of those it was.
            boolean itIsThem = viewer.equals(target);
            if (itIsThem || !exceptThem.contains(viewer.getUniqueId())) {
                viewer.sendMessage(line);
            }
        }
    }

    @Override
    public void silentJoinLeave(UUID who, boolean silent) {
        // Nothing to do to the server here: whether a message goes out is decided when the event
        // fires, by asking Vanish. Kept on the interface because that is where the decision belongs
        // and because a sink that persisted it would be a second place to get it wrong.
        log.info("{} will {} join and leave quietly.", who, silent ? "now" : "no longer");
    }
}
