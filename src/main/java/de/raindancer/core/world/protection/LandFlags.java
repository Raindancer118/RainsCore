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
        ProtectedArea area = land.areaAt(location).orElse(null);
        return rules.isAllowed(area, flag, LandAudience.of(area, who), who);
    }

    /** The answer for a creature, resolved where it is standing. */
    public boolean isAllowedFor(Entity entity, LandFlag flag) {
        if (entity == null) {
            return true;
        }
        UUID who = entity instanceof Player player ? player.getUniqueId() : null;
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
        UUID who = player == null ? null : player.getUniqueId();
        return rules.isAllowed(tracked, flag, LandAudience.of(tracked, who), who);
    }

    /** The area-level resolver behind this, for the screens that show a flag rather than enforce it. */
    public FlagRules flags() {
        return rules;
    }
}
