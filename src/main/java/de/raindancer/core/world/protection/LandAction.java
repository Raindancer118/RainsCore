package de.raindancer.core.world.protection;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Optional;

/**
 * What a player may be allowed to <em>do</em> on a piece of protected ground.
 *
 * <p>The vocabulary of the question, and it lives in Core because the question does. A plugin asking "may I
 * put this player here" or "may I break this block for them" needs these names without compiling against
 * whoever happens to own the ground.
 *
 * <p>Core does not decide who holds any of them — {@link ProtectedArea#may} does, and how somebody comes to
 * hold one is entirely the answering plugin's business: a trust list, a rank, a plot deed, a team
 * membership. What Core owns is that the list is one list, so two region plugins on the same server mean the
 * same thing by "may open containers".
 *
 * <p>The granularity is deliberate and is not free to reduce. An owner letting somebody through their doors
 * without letting them into their chests is a distinction people actually use, and collapsing these into
 * three coarse actions would quietly take it away.
 */
public enum LandAction {

    ENTER(Material.OAK_DOOR, true),
    BUILD(Material.BRICKS, false),
    BREAK(Material.IRON_PICKAXE, false),
    CONTAINERS(Material.CHEST, false),
    DOORS(Material.IRON_DOOR, false),
    REDSTONE(Material.LEVER, false),
    BEDS(Material.RED_BED, false),
    WORKSTATIONS(Material.CRAFTING_TABLE, true),
    ANIMALS(Material.WHEAT, false),
    DAMAGE_ANIMALS(Material.IRON_SWORD, false),
    VEHICLES(Material.OAK_BOAT, false),
    ITEM_FRAMES(Material.ITEM_FRAME, false),
    FARMLAND(Material.FARMLAND, false),
    BUCKETS(Material.WATER_BUCKET, false),
    IGNITE(Material.FLINT_AND_STEEL, false),
    ITEM_PICKUP(Material.HOPPER, true),
    TRADE(Material.EMERALD, true);

    private final Material icon;
    private final boolean publicByDefault;

    LandAction(Material icon, boolean publicByDefault) {
        this.icon = icon;
        this.publicByDefault = publicByDefault;
    }

    /**
     * The message key holding what to call this action.
     *
     * <p>Not a string in the enum, for the same reason as {@link LandFlag}: "Walk or teleport into the claim"
     * is claim wording, and Core does not know that the ground is a claim.
     */
    public String nameKey() {
        return "land.action." + key() + ".name";
    }

    public String descriptionKey() {
        return "land.action." + key() + ".description";
    }

    public Material icon() {
        return icon;
    }

    /** Whether outsiders hold this on freshly protected ground, before anybody has decided. */
    public boolean publicByDefault() {
        return publicByDefault;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Optional<LandAction> byKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (LandAction permission : values()) {
            if (permission.name().equals(normalised)) {
                return Optional.of(permission);
            }
        }
        return Optional.empty();
    }
}
