package de.raindancer.core.land;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The single authority on "may a claim do this at all?", the counterpart to {@link FlagService}.
 * <p>
 * Resolution order matches the flags: {@code FORCED_OFF} means the feature does not exist as far as the
 * plugin is concerned, {@code FORCED_ON} overrides the owner's own switch, and {@code AVAILABLE} leaves
 * the decision with them. Where a feature serves people rather than the claim, the owner's chosen
 * {@link ClaimAudience} set narrows it further.
 */
public final class Features {

    private final LandPolicy settings;

    public Features(LandPolicy settings) {
        this.settings = settings;
    }

    public FeaturePolicy policy(ClaimFeature feature) {
        return settings.featurePolicy(feature);
    }

    public void policy(ClaimFeature feature, FeaturePolicy policy) {
        settings.featurePolicy(feature, policy);
    }

    /** Whether the feature exists at all — false means it is gone from menus, commands and listeners. */
    public boolean isOffered(ClaimFeature feature) {
        return policy(feature).allowed();
    }

    /** Whether it runs in every claim regardless of what the owner chose. */
    public boolean isForced(ClaimFeature feature) {
        return policy(feature).forced();
    }

    /** Whether the owner still has a say — false when the server forced it either way. */
    public boolean isEditableByOwner(ClaimFeature feature) {
        return policy(feature) == FeaturePolicy.AVAILABLE;
    }

    /**
     * Whether this feature is running in this claim: the server policy first, then the claim's own
     * switch where it has one.
     */
    public boolean isEnabled(Claim claim, ClaimFeature feature) {
        FeaturePolicy policy = policy(feature);
        if (!policy.allowed()) {
            return false;
        }
        if (policy.forced() || claim == null) {
            return true;
        }
        return ownerSwitch(claim, feature);
    }

    /**
     * Whether it reaches this particular player: everything {@link #isEnabled} asks, plus whether the
     * owner included their group.
     */
    public boolean appliesTo(Claim claim, ClaimFeature feature, Player player) {
        if (!isEnabled(claim, feature)) {
            return false;
        }
        if (!feature.audienceAware() || claim == null) {
            return true;
        }
        return claim.featureServes(feature, ClaimAudience.of(claim, player.getUniqueId()));
    }

    /** Every feature that has not been taken away by the server. */
    public List<ClaimFeature> offeredFeatures() {
        List<ClaimFeature> offered = new ArrayList<>();
        for (ClaimFeature feature : ClaimFeature.values()) {
            if (isOffered(feature)) {
                offered.add(feature);
            }
        }
        return offered;
    }

    /**
     * The claim's own switch for a feature that has one.
     * <p>
     * Features without a switch of their own are simply on wherever they are offered — "may this claim
     * be renamed" has no third state between the server allowing it and the owner doing it.
     */
    private boolean ownerSwitch(Claim claim, ClaimFeature feature) {
        return switch (feature) {
            case EFFECTS -> claim.effectsEnabled();
            case PANTRY -> claim.pantry().enabled();
            case AUTO_EQUIP -> claim.equipment().enabled();
            case CLAIM_WEATHER -> claim.atmosphere().overridesWeather();
            case CLAIM_TIME -> claim.atmosphere().overridesTime();
            case ENTRY_FEE -> claim.entryFee().rawEnabled();
            case FENCE -> claim.fence().enabled();
            default -> true;
        };
    }
}
