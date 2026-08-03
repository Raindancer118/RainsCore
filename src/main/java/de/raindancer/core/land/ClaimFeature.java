package de.raindancer.core.land;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Optional;

/**
 * Everything a claim can do, as one list a server admin governs the way they govern {@link ClaimFlag}.
 * <p>
 * A flag answers "what happens inside this claim"; a feature answers "may a claim do this at all". Both
 * carry a server policy so an admin never has to reach for config.yml to take something away, and both
 * show up in the GUI when they are locked rather than quietly vanishing.
 * <p>
 * Two distinctions matter:
 * <ul>
 *   <li>{@link #ownerSwitchable()} — whether the claim has an on/off switch of its own that a
 *       {@link FeaturePolicy#FORCED_ON} could override. Where there is none, forcing it on would mean
 *       nothing, so those features offer only "available" and "forced off".</li>
 *   <li>{@link #audienceAware()} — whether the owner may choose which {@link ClaimAudience} the feature
 *       serves. A pantry can sensibly feed the household but not strangers; a claim's fence cannot exist
 *       for some people and not others.</li>
 * </ul>
 */
public enum ClaimFeature {

    // ---------------------------------------------------------------- perks the owner switches on

    EFFECTS("Claim effects", "Potion effects granted to people standing inside",
            Material.BREWING_STAND, FeaturePolicy.AVAILABLE, true, true,
            "effects.policy", "effects.enabled"),
    PANTRY("Pantry", "A shared larder that feeds hungry players inside",
            Material.BREAD, FeaturePolicy.AVAILABLE, true, true,
            "pantry.policy", "pantry.enabled"),
    AUTO_EQUIP("Auto-equip", "The claim keeps people supplied from its own stock",
            Material.ARMOR_STAND, FeaturePolicy.AVAILABLE, true, true,
            "equipment.policy", "equipment.enabled"),
    CLAIM_WEATHER("Own weather", "The claim shows its own weather, client side",
            Material.WATER_BUCKET, FeaturePolicy.FORCED_OFF, true, true,
            null, "atmosphere.weather"),
    CLAIM_TIME("Own time", "The claim shows its own time of day, client side",
            Material.CLOCK, FeaturePolicy.FORCED_OFF, true, true,
            null, "atmosphere.time"),
    ENTRY_FEE("Entry fee", "Owners may charge a toll at the border",
            Material.GOLD_NUGGET, FeaturePolicy.FORCED_OFF, true, false,
            null, "entry-fee.enabled"),
    FENCE("Claim fence", "A physical fence built along the border",
            Material.OAK_FENCE, FeaturePolicy.AVAILABLE, true, false,
            null, "fence.enabled"),

    // ---------------------------------------------------------------- capabilities with no own switch

    BANK("Claim bank", "A shared store of items and experience",
            Material.ENDER_CHEST, FeaturePolicy.AVAILABLE, false, false, null, null),
    CO_OWNERS("Co-owners", "Several people owning one claim as equals",
            Material.GOLDEN_HELMET, FeaturePolicy.AVAILABLE, false, false, null, null),
    KICK("Kick", "Escorting somebody out to the nearest safe spot",
            Material.LEATHER_BOOTS, FeaturePolicy.AVAILABLE, false, false, null, null),
    BANS("Bans and timeouts", "Keeping somebody from coming back",
            Material.IRON_BARS, FeaturePolicy.AVAILABLE, false, false, null, null),
    BROADCASTS("Announcements", "Chat lines when somebody is thrown out or let back in",
            Material.BELL, FeaturePolicy.AVAILABLE, false, false, null, null),
    CLAIM_RENAME("Custom names", "Owners naming their claims themselves",
            Material.WRITABLE_BOOK, FeaturePolicy.AVAILABLE, false, false, null, null),
    CLAIM_ICON("Custom icons", "Owners picking the item their claim shows in lists",
            Material.ITEM_FRAME, FeaturePolicy.AVAILABLE, false, false, null, null),
    RESIZE("Resizing", "Redrawing an existing claim's footprint",
            Material.SHEARS, FeaturePolicy.AVAILABLE, false, false, null, null),
    HEIGHT("Height changes", "Changing how deep and how high a claim reaches",
            Material.LADDER, FeaturePolicy.AVAILABLE, false, false, null, null),
    BORDER_PREVIEW("Border preview", "Showing the border on demand with /claim show",
            Material.GLOWSTONE_DUST, FeaturePolicy.AVAILABLE, false, false, null, null),

    // ---------------------------------------------------------------- arrival and departure

    TITLES("Enter/leave titles", "Owner defined titles across the screen",
            Material.NAME_TAG, FeaturePolicy.AVAILABLE, false, false,
            null, "notifications.titles"),
    ENTER_MESSAGE("Enter notice", "Telling a player they walked onto a claim",
            Material.PAPER, FeaturePolicy.AVAILABLE, false, false,
            null, "notifications.enter-message"),
    LEAVE_MESSAGE("Leave notice", "The counterpart when they walk back out",
            Material.MAP, FeaturePolicy.FORCED_OFF, false, false,
            null, "notifications.leave-message"),
    BORDER_FLASH("Border flash", "Briefly outlining the border on entry",
            Material.SPYGLASS, FeaturePolicy.AVAILABLE, false, false,
            null, "notifications.show-border-on-enter");

    private final String displayName;
    private final String description;
    private final Material icon;
    private final FeaturePolicy builtInDefault;
    private final boolean ownerSwitchable;
    private final boolean audienceAware;
    private final String legacyPolicyPath;
    private final String legacyBooleanPath;

    ClaimFeature(String displayName, String description, Material icon, FeaturePolicy builtInDefault,
                 boolean ownerSwitchable, boolean audienceAware,
                 String legacyPolicyPath, String legacyBooleanPath) {
        this.displayName = displayName;
        this.description = description;
        this.icon = icon;
        this.builtInDefault = builtInDefault;
        this.ownerSwitchable = ownerSwitchable;
        this.audienceAware = audienceAware;
        this.legacyPolicyPath = legacyPolicyPath;
        this.legacyBooleanPath = legacyBooleanPath;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public Material icon() {
        return icon;
    }

    /** The policy a config that says nothing about this feature gets. */
    public FeaturePolicy builtInDefault() {
        return builtInDefault;
    }

    /**
     * Whether the claim has its own on/off switch for this, which is what {@code forced-on} overrides.
     * Features without one cycle between "available" and "forced off" only.
     */
    public boolean ownerSwitchable() {
        return ownerSwitchable;
    }

    /** Whether the owner may choose which {@link ClaimAudience} this serves. */
    public boolean audienceAware() {
        return audienceAware;
    }

    /** Where a config written before feature policies existed kept a three-state policy, if anywhere. */
    public String legacyPolicyPath() {
        return legacyPolicyPath;
    }

    /** Where such a config kept a plain on/off boolean, if anywhere. */
    public String legacyBooleanPath() {
        return legacyBooleanPath;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Optional<ClaimFeature> byKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (ClaimFeature feature : values()) {
            if (feature.name().equals(normalised)) {
                return Optional.of(feature);
            }
        }
        // The names these went by before they were one list.
        return switch (normalised) {
            case "EQUIPMENT", "EQUIP" -> Optional.of(AUTO_EQUIP);
            case "WEATHER" -> Optional.of(CLAIM_WEATHER);
            case "TIME" -> Optional.of(CLAIM_TIME);
            case "ICON" -> Optional.of(CLAIM_ICON);
            case "RENAME", "NAME", "NAMES" -> Optional.of(CLAIM_RENAME);
            case "BAN", "TIMEOUT" -> Optional.of(BANS);
            default -> Optional.empty();
        };
    }
}
