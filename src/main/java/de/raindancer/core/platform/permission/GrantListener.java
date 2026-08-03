package de.raindancer.core.platform.permission;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a granted permission stops being a line in a file and starts being a permission.
 *
 * <h2>Why an attachment per player</h2>
 * Because it is the mechanism Bukkit already has for "this one person may do this one thing", and it
 * layers: a server that also runs LuckPerms keeps everything LuckPerms says and gets these on top,
 * rather than one of the two winning silently.
 *
 * <h2>Why it is removed on quit</h2>
 * An attachment holds a reference to the player. Left behind, that is a leak of one player object per
 * join for the life of the server — and the player object holds a world, a connection and an inventory.
 * The version of this mistake that does not leak memory instead leaks <em>permissions</em>: a recycled
 * entry can hand somebody else's grants to the next person with the same id, which is a security bug
 * rather than a performance one.
 *
 * <h2>{@code LOWEST}</h2>
 * So that everything else which fires on join — a module telling a moderator how many reports are
 * waiting, a menu greying its buttons — already sees the permissions this grants. A listener at
 * {@code MONITOR} would apply them after everybody had asked.
 */
public final class GrantListener implements Listener {

    private final Plugin plugin;
    private final Grants grants;

    /** One per online player. Keyed by id rather than by Player so quitting cannot miss one. */
    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();

    public GrantListener(Plugin plugin, Grants grants) {
        this.plugin = plugin;
        this.grants = grants;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        apply(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        remove(event.getPlayer().getUniqueId());
    }

    /**
     * Gives somebody everything they have been granted, replacing whatever they had before.
     *
     * <p>Public because a promotion has to take effect <em>now</em>: a moderator told they are a
     * moderator and then having to relog before any command works is a moderator who reports the
     * promotion as broken.
     */
    public void apply(Player player) {
        if (player == null) {
            return;
        }
        UUID who = player.getUniqueId();
        // The old attachment goes first. Setting the new nodes over the top of it would leave anything
        // that was revoked still attached, so a demotion would not take effect until the next relog.
        remove(who);
        if (grants.countFor(who) == 0) {
            return;
        }
        PermissionAttachment attachment = player.addAttachment(plugin);
        grants.applyTo(who, attachment::setPermission);
        attachments.put(who, attachment);
        // Bukkit caches the answers; without this the player keeps the permissions they had a moment
        // ago until something else happens to invalidate it.
        player.recalculatePermissions();
    }

    /** Takes the attachment away again — on quit, and before re-applying. */
    public void remove(UUID who) {
        PermissionAttachment attachment = attachments.remove(who);
        if (attachment == null) {
            return;
        }
        try {
            attachment.remove();
        } catch (IllegalStateException alreadyGone) {
            // Bukkit throws when the attachment is no longer attached — which happens if the player
            // has already disconnected. Nothing to do and nothing to report: it is gone either way.
        }
    }

    /** How many players currently have one. For a diagnostic, and to notice a leak. */
    public int attached() {
        return attachments.size();
    }

    /** Drops every attachment. For a reload or a shutdown. */
    public void removeEverything() {
        // Over a copy: remove() writes to the same map, and iterating it while it is being written is
        // the ConcurrentModificationException this whole class would otherwise throw on shutdown.
        for (UUID who : Set.copyOf(attachments.keySet())) {
            remove(who);
        }
    }
}
