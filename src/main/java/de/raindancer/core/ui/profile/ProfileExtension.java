package de.raindancer.core.ui.profile;

import de.raindancer.core.ui.menu.Menu;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Lets a module put a button on {@link ProfileMenu} without this class ever knowing that module
 * exists — the same seam {@code claims-module}'s own {@code ClaimMenuExtension} already is, moved
 * here because a player's profile is not any one module's page the way a claim's is. Every module
 * already depends on Core, so unlike the claims/mannequin pairing there is no optional-linkage
 * dance to do: a module registers straight from its own {@code enable()} and unregisters from
 * {@code disable()}.
 *
 * <p>tpa-module offers "Request a teleport"; essentials-module offers "Message"; a server running
 * neither shows a profile with just a head and whatever else is installed. Adding an eleventh
 * contributor changes nothing here.
 *
 * @see ProfileExtensions
 */
public interface ProfileExtension {

    /**
     * What to draw for this subject and viewer, or {@code null} to draw nothing — looking at your
     * own profile is the obvious reason a contributor might have nothing to offer: essentials-module
     * is not going to draw "Message yourself".
     *
     * <p>Asked fresh on every render, the same reason every other button on this page is computed
     * rather than cached: whether a target is online, or has blocked messages, can change between
     * one open of this page and the next.
     *
     * @param parent this page itself, for a submenu to open with — so Back leads here rather than
     *               nowhere
     */
    ProfileButton contribute(Player viewer, OfflinePlayer subject, Menu parent);
}
