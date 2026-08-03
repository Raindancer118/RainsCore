package de.raindancer.core.world.protection;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Flags resolved by where something is, rather than for an area somebody already holds.
 *
 * <p>{@link FlagRules} answers for an area that has been looked up. This does the looking up, which is the
 * whole difference: a listener holds a {@link Location} or an {@link Entity}, not an area, and fourteen
 * listeners each writing their own two-line lookup is how the tolerance rules drift apart between them.
 */
public final class LandFlags {

    private final Land land;
    private final FlagRules rules;

    LandFlags(Land land, FlagRules rules) {
        this.land = land;
        this.rules = rules;
    }

    /**
     * Whether this server enforces the flag anywhere at all.
     *
     * <p>Listeners check this once and return early, which is the difference between a disabled flag costing
     * nothing and costing a lookup on every block change on the server.
     */
    public boolean isEnforced(LandFlag flag) {
        return rules.isEnforced(flag);
    }

    /**
     * The answer for a block, with nobody in particular on the receiving end.
     *
     * <p>For the environmental listeners — fire, decay, pistons — where the thing being protected is the world
     * rather than a person. Resolved at the owner's tier, which is the strictest reading and the right one for
     * "may this block change".
     */
    public boolean isAllowedAt(Location location, LandFlag flag) {
        return isAllowedAt(location, flag, null);
    }

    /** The same, for a particular person. */
    public boolean isAllowedAt(Location location, LandFlag flag, UUID who) {
        if (location == null || location.getWorld() == null) {
            return true;
        }
        if (bypassed(who)) {
            return true;
        }
        ProtectedArea area = land.areaAt(location).orElse(null);
        // The actor-less half of the bypass. This overload is what every world event comes through — a redstone
        // torch, a pressure plate, a block completing a circuit — and none of them carry a player, so the
        // per-player check above can never fire for them. See Land.isSuspendedIn.
        if (land.isSuspendedIn(area)) {
            return true;
        }
        return rules.isAllowed(area, flag, LandAudience.of(area, who), who);
    }

    /**
     * Whether this player's bypass answers the question before any flag does.
     *
     * <p><b>Here, and only here.</b> The bypass used to be remembered by each listener for itself: the teleport
     * gate checked it, the potion thrower checked it, and the flag questions that came straight through this
     * class did not. So whether an admin's bypass held depended on which listener happened to be enforcing the
     * flag — reported as "an admin could not ender-pearl inside a claim once the owner switched pearls off, and
     * toggling the bypass fixed it", and true of far more than pearls. A bypass that has to be remembered in
     * fourteen places is a bypass that works in thirteen.
     *
     * <p>About the <em>asker</em>, never about the area. Short-circuiting the resolver itself would switch the
     * flag off for everybody on the server the moment one admin turned their bypass on.
     *
     * <p>It costs a set lookup and a permission check per question. Kept behind the null test on {@code who}
     * because the block-level questions — fire spread, decay, pistons — have nobody to bypass on behalf of and
     * are the ones asked thousands of times a second.
     */
    private boolean bypassed(UUID who) {
        if (who == null) {
            return false;
        }
        Player player = org.bukkit.Bukkit.getPlayer(who);
        return player != null && land.isBypassing(player);
    }

    /** The answer for a creature, resolved where it is standing. */
    public boolean isAllowedFor(Entity entity, LandFlag flag) {
        if (entity == null) {
            return true;
        }
        UUID who = entity instanceof Player player ? player.getUniqueId() : null;
        // The bypass is applied by isAllowedAt below rather than twice here.
        return isAllowedAt(entity.getLocation(), flag, who);
    }

    /**
     * The answer for a player standing on ground the provider has already resolved.
     *
     * <p>Separate from {@link #isAllowedFor} on purpose: a provider that tracks presence includes the vertical
     * grace a raw lookup does not, so somebody on their own roof keeps the protection their flag promises.
     * Looking the area up again here would quietly take it away from them.
     */
    public boolean isAllowedForTracked(ProtectedArea tracked, Location location, LandFlag flag,
                                       Player player) {
        if (player != null && land.isBypassing(player)) {
            return true;
        }
        UUID who = player == null ? null : player.getUniqueId();
        return rules.isAllowed(tracked, flag, LandAudience.of(tracked, who), who);
    }

    /** The area-level resolver behind this, for the screens that show a flag rather than enforce it. */
    public FlagRules flags() {
        return rules;
    }
}
