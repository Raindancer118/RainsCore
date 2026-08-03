package de.raindancer.core.land;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Resolves the effective value of a {@link ClaimFlag}, merging the server policy with the claim owner's
 * choice for the audience the affected player belongs to.
 * <p>
 * Resolution order: a {@code DISABLED} flag is never enforced at all, {@code FORCED_*} overrides the
 * owner, otherwise the owner's override for that audience wins and the server default is the fallback.
 */
public final class Flags {

    private final LandPolicy settings;

    public Flags(LandPolicy settings) {
        this.settings = settings;
    }

    /**
     * True when the thing the flag guards is allowed to happen for this audience.
     *
     * @param audience {@code null} reads the claim wide value, which is the only value a flag that is not
     *                 {@link ClaimFlag#audienceAware()} ever has
     */
    public boolean isAllowed(Claim claim, ClaimFlag flag, ClaimAudience audience) {
        FlagPolicy policy = settings.policy(flag);
        return switch (policy) {
            // A disabled flag means the plugin does not interfere, so the vanilla behaviour applies.
            case DISABLED -> true;
            case FORCED_ON -> true;
            case FORCED_OFF -> false;
            case AVAILABLE -> claim == null
                    ? settings.flagDefault(flag)
                    : claim.flagOverride(flag, audience).orElseGet(() -> settings.flagDefault(flag));
        };
    }

    /**
     * The claim wide value. Correct for every flag that is not audience aware; for one that is, this is
     * the owner's own value and callers that have a player should use {@link #isAllowedFor} instead.
     */
    public boolean isAllowed(Claim claim, ClaimFlag flag) {
        return isAllowed(claim, flag, null);
    }

    /** The value that applies to this player, based on their standing in the claim. */
    public boolean isAllowedFor(Claim claim, ClaimFlag flag, Player player) {
        return isAllowed(claim, flag, audienceOf(claim, player));
    }

    /**
     * The value that applies to whatever is on the receiving end.
     * <p>
     * Anything that is not a player — a cow in a pen, a villager, a tamed wolf — is treated as the owner's
     * own: the livestock inside a claim belongs to the claim, so a flag the owner switched off to protect
     * themselves protects their animals too.
     */
    public boolean isAllowedFor(Claim claim, ClaimFlag flag, Entity entity) {
        if (entity instanceof Player player) {
            return isAllowedFor(claim, flag, player);
        }
        return isAllowed(claim, flag, ClaimAudience.OWNER);
    }

    /** Where the player stands in the claim, for flags and for display. */
    public ClaimAudience audienceOf(Claim claim, Player player) {
        UUID uuid = player == null ? null : player.getUniqueId();
        return ClaimAudience.of(claim, uuid);
    }

    /** Whether this plugin enforces the flag at all — a disabled flag skips its listener entirely. */
    public boolean isEnforced(ClaimFlag flag) {
        return settings.policy(flag) != FlagPolicy.DISABLED;
    }

    public boolean isEditableByOwner(ClaimFlag flag) {
        return settings.policy(flag) == FlagPolicy.AVAILABLE;
    }

    public FlagPolicy policy(ClaimFlag flag) {
        return settings.policy(flag);
    }

    public boolean serverDefault(ClaimFlag flag) {
        return settings.flagDefault(flag);
    }

    /** Flags a claim owner is allowed to see and toggle. */
    public List<ClaimFlag> editableFlags() {
        List<ClaimFlag> flags = new ArrayList<>();
        for (ClaimFlag flag : ClaimFlag.values()) {
            if (settings.policy(flag) != FlagPolicy.DISABLED) {
                flags.add(flag);
            }
        }
        return flags;
    }

    /**
     * Applies an owner toggle for one audience, or for all three when {@code audience} is {@code null}.
     * Returns {@code false} when the server policy forbids the change, so the caller can tell the player
     * why nothing happened.
     */
    public boolean setOwnerValue(Claim claim, ClaimFlag flag, ClaimAudience audience, Boolean value) {
        if (!isEditableByOwner(flag)) {
            return false;
        }
        claim.setFlagOverride(flag, audience, value);
        return true;
    }

    /**
     * How a flag reads when it is described without naming an audience: allowed, denied, or split across
     * the three groups.
     */
    public Summary summarise(Claim claim, ClaimFlag flag) {
        if (!flag.audienceAware() || settings.policy(flag) != FlagPolicy.AVAILABLE) {
            return isAllowed(claim, flag) ? Summary.ALLOWED : Summary.DENIED;
        }
        boolean first = isAllowed(claim, flag, ClaimAudience.OWNER);
        for (ClaimAudience audience : ClaimAudience.values()) {
            if (isAllowed(claim, flag, audience) != first) {
                return Summary.MIXED;
            }
        }
        return first ? Summary.ALLOWED : Summary.DENIED;
    }

    /** The three states a flag can be in once all audiences are taken together. */
    public enum Summary {
        ALLOWED("<green>", "✔"),
        DENIED("<red>", "✘"),
        MIXED("<yellow>", "◐");

        private final String colour;
        private final String mark;

        Summary(String colour, String mark) {
            this.colour = colour;
            this.mark = mark;
        }

        public String colour() {
            return colour;
        }

        public String mark() {
            return mark;
        }
    }
}
