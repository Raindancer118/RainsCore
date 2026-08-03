package de.raindancer.core.land;

import org.bukkit.Material;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Claim wide protection switches. Unlike {@link ClaimPermission} these are not granted to named players —
 * they describe how the world behaves inside the claim.
 * <p>
 * The boolean carried by a flag always means "this thing is allowed". Server admins can force a flag
 * to a fixed value or remove it entirely; see {@link FlagPolicy}.
 * <p>
 * A flag is either <em>audience aware</em> or claim wide. An audience aware flag governs something that
 * happens <em>to a person</em> — fall damage, PvP, the enter message — and therefore carries one value per
 * {@link ClaimAudience}, so an owner can be spared what a stranger is not. Everything else describes the
 * world itself (fire, decay, pistons) and cannot sensibly differ per onlooker, so it carries a single
 * value that applies to everyone.
 */
public enum ClaimFlag {

    PVP("PvP", "Players may damage each other", Material.DIAMOND_SWORD, false, true),
    MONSTER_SPAWNING("Monster Spawning", "Hostile mobs may spawn naturally", Material.ZOMBIE_HEAD, false),
    ANIMAL_SPAWNING("Animal Spawning", "Passive mobs may spawn naturally", Material.WHEAT, true),
    SPAWNER_SPAWNING("Spawner Spawning", "Monster spawners keep working", Material.SPAWNER, true),
    MONSTER_ENTRY("Monster Entry", "Hostile mobs may walk into the claim", Material.IRON_DOOR, false),
    MONSTER_TARGETING("Monster Targeting", "Hostile mobs may target players inside", Material.GHAST_TEAR, true, true),
    EXPLOSIONS("Explosions", "Creepers, TNT and beds may damage blocks", Material.TNT, false),
    EXPLOSION_DAMAGE("Explosion Damage", "Explosions may hurt players and mobs — separate from block damage",
            Material.GUNPOWDER, false, true),
    MOB_DAMAGE("Mob Damage", "Mobs may hurt players and other mobs", Material.BONE, true, true),
    FIRE_SPREAD("Fire Spread", "Fire may spread and burn blocks", Material.CAMPFIRE, false),
    LEAF_DECAY("Leaf Decay", "Leaves decay when their tree is cut", Material.OAK_LEAVES, true),
    ENDERMAN_GRIEF("Enderman Grief", "Endermen may pick up blocks", Material.ENDER_PEARL, false),
    MOB_GRIEF("Mob Grief", "Mobs may change blocks (ravagers, silverfish, sheep …)", Material.ROTTEN_FLESH, false),
    PISTONS_FROM_OUTSIDE("Outside Pistons", "Pistons outside the claim may move blocks inside",
            Material.PISTON, false),
    FLUIDS_FROM_OUTSIDE("Outside Fluids", "Water and lava may flow in from outside", Material.BUCKET, false),
    SNOW_ICE_FORM("Snow & Ice", "Snow and ice may form or melt", Material.PACKED_ICE, true),
    FALL_DAMAGE("Fall Damage", "Players take fall damage", Material.FEATHER, true, true),
    HUNGER("Hunger", "Players lose food", Material.COOKED_BEEF, true, true),
    ITEM_DROP_ON_DEATH("Keep Inventory", "Players keep their items on death — off means vanilla",
            Material.TOTEM_OF_UNDYING, false, true),
    ELYTRA_FLIGHT("Elytra", "Players may fly with an elytra", Material.ELYTRA, true, true,
            EnumSet.of(ClaimAudience.OWNER)),
    ENDER_PEARL_IN("Ender Pearls", "Players may pearl into the claim", Material.ENDER_PEARL, true, true,
            EnumSet.of(ClaimAudience.OWNER, ClaimAudience.TRUSTED)),
    TELEPORT_IN("Teleport In", "Players may teleport into the claim", Material.COMPASS, true, true,
            EnumSet.of(ClaimAudience.OWNER, ClaimAudience.TRUSTED)),
    SHOW_ENTER_MESSAGE("Enter Message", "Send the claim notification on entry", Material.PAPER, true, true),
    SHOW_BORDER_ON_ENTER("Border Flash", "Briefly outline the border on entry", Material.SPYGLASS, true, true),
    SHOW_TITLES("Titles", "Show the enter and leave titles", Material.NAME_TAG, true, true);

    private final String displayName;
    private final String description;
    private final Material icon;
    private final boolean builtInDefault;
    private final boolean audienceAware;
    private final Set<ClaimAudience> legacyExempt;

    ClaimFlag(String displayName, String description, Material icon, boolean builtInDefault) {
        this(displayName, description, icon, builtInDefault, false, EnumSet.noneOf(ClaimAudience.class));
    }

    ClaimFlag(String displayName, String description, Material icon, boolean builtInDefault,
              boolean audienceAware) {
        this(displayName, description, icon, builtInDefault, audienceAware, EnumSet.noneOf(ClaimAudience.class));
    }

    ClaimFlag(String displayName, String description, Material icon, boolean builtInDefault,
              boolean audienceAware, Set<ClaimAudience> legacyExempt) {
        this.displayName = displayName;
        this.description = description;
        this.icon = icon;
        this.builtInDefault = builtInDefault;
        this.audienceAware = audienceAware;
        this.legacyExempt = legacyExempt;
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

    /** Fallback used when config.yml has no default for this flag. */
    public boolean builtInDefault() {
        return builtInDefault;
    }

    /**
     * Whether owners may set this flag separately per {@link ClaimAudience}.
     * <p>
     * False for everything that describes the world rather than a person: one patch of leaves cannot decay
     * for visitors and stay put for the owner.
     */
    public boolean audienceAware() {
        return audienceAware;
    }

    /**
     * The groups that were hard coded as exempt from this flag before it became audience aware.
     * <p>
     * Used only when reading a claim written by an older version: back then "teleport in: denied" meant
     * "denied for strangers", because owners and trusted players were waved through in the listener. That
     * exemption now lives in the per-audience values instead, so a claim keeps behaving the way its owner
     * set it up rather than silently locking them out of their own home.
     */
    public Set<ClaimAudience> legacyExemptAudiences() {
        return legacyExempt;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Optional<ClaimFlag> byKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (ClaimFlag flag : values()) {
            if (flag.name().equals(normalised)) {
                return Optional.of(flag);
            }
        }
        return Optional.empty();
    }
}
