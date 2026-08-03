package de.raindancer.core.world.protection;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What a {@link LandFlag} actually resolves to, for whom.
 *
 * <p>Merges the server's policy with whatever the person responsible for the ground chose, for the tier the
 * affected player falls into. The order is the design:
 *
 * <ol>
 *   <li>{@code DISABLED} — this server does not enforce the flag at all, so vanilla behaviour applies.
 *       <b>Which means allowed.</b> It is not a synonym for "off", and reading it as one turns an admin
 *       switching a flag off into the opposite of what they asked for.</li>
 *   <li>{@code FORCED_ON} / {@code FORCED_OFF} — the server has decided, and the owner has no say.</li>
 *   <li>{@code AVAILABLE} — the owner's own value for that tier, and the server default where they never
 *       set one.</li>
 * </ol>
 *
 * <p>Knows nothing about claims. It is handed a {@link ProtectedArea}, which may be a claim, an arena, a
 * plot or a spawn region, and asks it two questions.
 */
public final class FlagRules {

    private final LandPolicy policy;

    public FlagRules(LandPolicy policy) {
        this.policy = policy;
    }

    /**
     * True when the thing the flag guards is allowed to happen for this tier.
     *
     * @param area     null for unprotected ground, which reads the server default
     * @param audience null reads the area-wide value, which is the only value a flag that is not
     *                 {@link LandFlag#audienceAware()} ever has
     */
    public boolean isAllowed(ProtectedArea area, LandFlag flag, LandAudience audience) {
        return isAllowed(area, flag, audience, null);
    }

    /**
     * The same, for a named person — which is what lets an area exempt somebody from its own rules.
     *
     * <p>The exemption is checked before the area's own value and after the server's policy, and that order is
     * the design: a server that has forced a flag has taken the decision away from the ground entirely, so an
     * area cannot hand it back by exempting people. Whoever is responsible for a piece of ground may excuse
     * somebody from their own rules, never from the server's.
     */
    public boolean isAllowed(ProtectedArea area, LandFlag flag, LandAudience audience, UUID who) {
        FlagPolicy decided = policy.policy(flag);
        if (decided == FlagPolicy.AVAILABLE && area != null && who != null
                && area.isExemptFromFlags(who)) {
            // Exempt means the rule does not apply to them, which is "allowed" — the same thing DISABLED
            // means at the server level, and for the same reason: nothing is interfering.
            return true;
        }
        return switch (decided) {
            // Not enforced means not interfered with, and not interfering with fire spread means fire
            // spreads. See the class comment — this line is the one people misread.
            case DISABLED -> true;
            case FORCED_ON -> true;
            case FORCED_OFF -> false;
            // No area is open ground, and open ground is allowed. The default is what a NEW CLAIM starts
            // with — explosions off so a new claim does not blow up, PvP off so it is safe, fire spread off so
            // it does not burn — and returning it here read those three as the rule for the whole world. So TNT,
            // PvP and fire were all switched off everywhere, inside claims and out, with nothing in any log.
            //
            // Same principle as LandVerdict.UNKNOWN one level down: with no provider Core will not claim ground
            // is unprotected, and with no area it will not claim ground is protected. A server that means
            // "never, anywhere" says so with FORCED_OFF above, which is a decision somebody made.
            case AVAILABLE -> area == null
                    || area.flagOverride(flag, tierFor(flag, audience))
                            .orElseGet(() -> policy.flagDefault(flag));
        };
    }

    /**
     * Which tier a flag is actually read at.
     *
     * <p>An area-wide flag is read at {@link LandAudience#OWNER} whatever was asked, and that is Core's promise
     * rather than the provider's. One patch of leaves cannot decay for visitors and stay put for the owner, so
     * asking about fire spread "for a visitor" is a question with one answer — and if the collapsing happened
     * in the provider instead, every provider would have to remember to do it and the first one to forget would
     * have fire spreading for some onlookers and not others.
     *
     * <p>Found by a test: with the claim model out of Core, the class that used to keep this promise had gone
     * and nothing had taken it over.
     */
    private static LandAudience tierFor(LandFlag flag, LandAudience audience) {
        if (!flag.audienceAware()) {
            return LandAudience.OWNER;
        }
        return audience == null ? LandAudience.OWNER : audience;
    }

    /**
     * The area-wide value. Correct for every flag that is not audience aware; for one that is, this is the
     * owner's own value, and a caller holding a player should use {@link #isAllowedFor} instead.
     */
    public boolean isAllowed(ProtectedArea area, LandFlag flag) {
        return isAllowed(area, flag, null);
    }

    /** The value that applies to this player, based on where they stand on this ground. */
    public boolean isAllowedFor(ProtectedArea area, LandFlag flag, Player player) {
        return isAllowed(area, flag, audienceOf(area, player),
                player == null ? null : player.getUniqueId());
    }

    /**
     * The value that applies to whatever is on the receiving end.
     *
     * <p>Anything that is not a player — a cow in a pen, a villager, a tamed wolf — is treated as the
     * owner's own: livestock inside somebody's fence belongs to them, so a flag they switched off to protect
     * themselves protects their animals too.
     */
    public boolean isAllowedFor(ProtectedArea area, LandFlag flag, Entity entity) {
        if (entity instanceof Player player) {
            return isAllowedFor(area, flag, player);
        }
        return isAllowed(area, flag, LandAudience.OWNER);
    }

    /** Where the player stands here, for flags and for display. */
    public LandAudience audienceOf(ProtectedArea area, Player player) {
        return LandAudience.of(area, player == null ? null : player.getUniqueId());
    }

    /** Whether this server enforces the flag at all — a disabled flag skips its listener entirely. */
    public boolean isEnforced(LandFlag flag) {
        return policy.policy(flag) != FlagPolicy.DISABLED;
    }

    public boolean isEditableByOwner(LandFlag flag) {
        return policy.policy(flag) == FlagPolicy.AVAILABLE;
    }

    public FlagPolicy policy(LandFlag flag) {
        return policy.policy(flag);
    }

    public boolean serverDefault(LandFlag flag) {
        return policy.flagDefault(flag);
    }

    /** The flags somebody responsible for ground is allowed to see and toggle. */
    public List<LandFlag> editableFlags() {
        List<LandFlag> flags = new ArrayList<>();
        for (LandFlag flag : LandFlag.values()) {
            if (policy.policy(flag) != FlagPolicy.DISABLED) {
                flags.add(flag);
            }
        }
        return flags;
    }

    /**
     * How a flag reads when it is described without naming a tier: allowed, denied, or split across the
     * three.
     */
    public Summary summarise(ProtectedArea area, LandFlag flag) {
        if (!flag.audienceAware() || policy.policy(flag) != FlagPolicy.AVAILABLE) {
            return isAllowed(area, flag) ? Summary.ALLOWED : Summary.DENIED;
        }
        boolean first = isAllowed(area, flag, LandAudience.OWNER);
        for (LandAudience audience : LandAudience.values()) {
            if (isAllowed(area, flag, audience) != first) {
                return Summary.MIXED;
            }
        }
        return first ? Summary.ALLOWED : Summary.DENIED;
    }

    /** The three states a flag can be in once all tiers are taken together. */
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
