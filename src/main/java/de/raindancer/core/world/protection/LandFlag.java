package de.raindancer.core.world.protection;

import org.bukkit.Material;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Area-wide protection switches: how the world behaves on a piece of protected ground.
 *
 * <p>The boolean a flag carries always means "this thing is allowed". A server owner can force one to a
 * fixed value or stop enforcing it entirely — see {@link FlagPolicy}.
 *
 * <p>A flag is either <em>audience aware</em> or area-wide. An audience-aware flag governs something that
 * happens <em>to a person</em> — fall damage, PvP — and so carries one value per {@link LandAudience}, which
 * is what lets an owner be spared what a stranger is not. Everything else describes the world itself (fire,
 * decay, pistons) and cannot sensibly differ per onlooker, so it carries one value for everybody.
 *
 * <h2>No wording here</h2>
 * A flag has an identity, a default and an icon. What it is <em>called</em> is not its business, and used to
 * be: every name and description here read "… inside the claim", which is wrong in Core twice over — it
 * assumes the ground is a claim, and it hard-codes English into an enum.
 *
 * <p>So the label comes from {@link de.raindancer.core.ui.messages.Messages} under
 * {@code land.flag.<key>.name} and {@code land.flag.<key>.description}. Core ships neutral wording — "inside
 * the area" — and a plugin that knows better says so:
 *
 * <pre>{@code
 * // the claims module, on enable
 * messages.define("land.flag.pvp.description", "Players may damage each other inside this claim");
 * }</pre>
 *
 * <p>Which also means the server owner can reword any of them in {@code messages.yml}, and their edit beats
 * both defaults — the whole point of that four-layer arrangement.
 */
public enum LandFlag {

    PVP(Material.DIAMOND_SWORD, false, true),
    MONSTER_SPAWNING(Material.ZOMBIE_HEAD, false),
    ANIMAL_SPAWNING(Material.WHEAT, true),
    SPAWNER_SPAWNING(Material.SPAWNER, true),
    MONSTER_ENTRY(Material.IRON_DOOR, false),
    MONSTER_TARGETING(Material.GHAST_TEAR, true, true),
    EXPLOSIONS(Material.TNT, false),
    EXPLOSION_DAMAGE(Material.GUNPOWDER, false, true),
    MOB_DAMAGE(Material.BONE, true, true),
    FIRE_SPREAD(Material.CAMPFIRE, false),
    LEAF_DECAY(Material.OAK_LEAVES, true),
    ENDERMAN_GRIEF(Material.ENDER_PEARL, false),
    MOB_GRIEF(Material.ROTTEN_FLESH, false),
    PISTONS_FROM_OUTSIDE(Material.PISTON, false),
    FLUIDS_FROM_OUTSIDE(Material.BUCKET, false),
    SNOW_ICE_FORM(Material.PACKED_ICE, true),
    FALL_DAMAGE(Material.FEATHER, true, true),
    HUNGER(Material.COOKED_BEEF, true, true),
    ITEM_DROP_ON_DEATH(Material.TOTEM_OF_UNDYING, false, true),
    ELYTRA_FLIGHT(Material.ELYTRA, true, true, EnumSet.of(LandAudience.OWNER)),
    ENDER_PEARL_IN(Material.ENDER_PEARL, true, true,
            EnumSet.of(LandAudience.OWNER, LandAudience.TRUSTED)),
    TELEPORT_IN(Material.COMPASS, true, true,
            EnumSet.of(LandAudience.OWNER, LandAudience.TRUSTED));

    private final Material icon;
    private final boolean builtInDefault;
    private final boolean audienceAware;
    private final Set<LandAudience> legacyExempt;

    LandFlag(Material icon, boolean builtInDefault) {
        this(icon, builtInDefault, false, EnumSet.noneOf(LandAudience.class));
    }

    LandFlag(Material icon, boolean builtInDefault, boolean audienceAware) {
        this(icon, builtInDefault, audienceAware, EnumSet.noneOf(LandAudience.class));
    }

    LandFlag(Material icon, boolean builtInDefault, boolean audienceAware,
             Set<LandAudience> legacyExempt) {
        this.icon = icon;
        this.builtInDefault = builtInDefault;
        this.audienceAware = audienceAware;
        this.legacyExempt = legacyExempt;
    }

    /** The message key holding this flag's name. See the class comment. */
    public String nameKey() {
        return "land.flag." + key() + ".name";
    }

    /** The message key holding its one-line explanation. */
    public String descriptionKey() {
        return "land.flag." + key() + ".description";
    }

    public Material icon() {
        return icon;
    }

    /** What a server that has said nothing about this flag gets. */
    public boolean builtInDefault() {
        return builtInDefault;
    }

    /**
     * Whether this flag may be set separately per {@link LandAudience}.
     *
     * <p>False for everything that describes the world rather than a person: one patch of leaves cannot decay
     * for visitors and stay put for the owner.
     */
    public boolean audienceAware() {
        return audienceAware;
    }

    /**
     * The tiers that were hard-coded as exempt from this flag before it became audience aware.
     *
     * <p>Used only when reading ground saved by an older version: back then "teleport in: denied" meant
     * "denied for strangers", because owners and trusted players were waved through in the listener. That
     * exemption now lives in the per-tier values, so an area keeps behaving the way it was set up rather than
     * silently locking its owner out of their own home.
     */
    public Set<LandAudience> legacyExemptAudiences() {
        return legacyExempt;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Optional<LandFlag> byKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (LandFlag flag : values()) {
            if (flag.name().equals(normalised)) {
                return Optional.of(flag);
            }
        }
        // Retired here rather than removed silently: these three were claim notifications rather than
        // world protection and now live with the claims module. A caller can tell "moved" from "typo".
        return Optional.empty();
    }

    /**
     * Flag keys that used to exist here and deliberately no longer do.
     *
     * <p>{@code show-enter-message}, {@code show-border-on-enter} and {@code show-titles} were never world
     * protection — they decide whether a plugin says something when you arrive, which is that plugin's
     * business. A loader finding one of these in an old file can say "that moved" instead of "unknown flag".
     */
    public static boolean wasRetired(String raw) {
        if (raw == null) {
            return false;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "show-enter-message", "show-border-on-enter", "show-titles" -> true;
            default -> false;
        };
    }
}
