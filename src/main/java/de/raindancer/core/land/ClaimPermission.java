package de.raindancer.core.land;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Optional;

/**
 * What a player is allowed to <em>do</em> inside a claim.
 * <p>
 * These are granted per player (see {@link ClaimMember}) or to everybody without an explicit entry
 * (see {@link Claim#publicPermissions()}).
 */
public enum ClaimPermission {

    ENTER("Enter", "Walk or teleport into the claim", Material.OAK_DOOR, true),
    BUILD("Build", "Place blocks", Material.BRICKS, false),
    BREAK("Break", "Break blocks", Material.IRON_PICKAXE, false),
    CONTAINERS("Containers", "Open chests, barrels, furnaces, hoppers …", Material.CHEST, false),
    DOORS("Doors & Gates", "Open doors, trapdoors and fence gates", Material.IRON_DOOR, false),
    REDSTONE("Buttons & Levers", "Use buttons, levers and pressure plates", Material.LEVER, false),
    BEDS("Beds & Anchors", "Sleep in beds and use respawn anchors", Material.RED_BED, false),
    WORKSTATIONS("Workstations", "Use crafting tables, anvils, enchanting tables …", Material.CRAFTING_TABLE, true),
    ANIMALS("Animals", "Interact with and leash passive mobs", Material.WHEAT, false),
    DAMAGE_ANIMALS("Hurt Animals", "Damage or kill passive mobs", Material.IRON_SWORD, false),
    VEHICLES("Vehicles", "Place, enter and break boats and minecarts", Material.OAK_BOAT, false),
    ITEM_FRAMES("Frames & Stands", "Modify item frames and armour stands", Material.ITEM_FRAME, false),
    FARMLAND("Farmland", "Trample crops and till soil", Material.FARMLAND, false),
    BUCKETS("Buckets", "Fill and empty buckets", Material.WATER_BUCKET, false),
    IGNITE("Fire", "Use flint and steel or fire charges", Material.FLINT_AND_STEEL, false),
    ITEM_PICKUP("Item Pickup", "Pick up dropped items", Material.HOPPER, true),
    TRADE("Villager Trading", "Trade with villagers", Material.EMERALD, true);

    private final String displayName;
    private final String description;
    private final Material icon;
    private final boolean publicByDefault;

    ClaimPermission(String displayName, String description, Material icon, boolean publicByDefault) {
        this.displayName = displayName;
        this.description = description;
        this.icon = icon;
        this.publicByDefault = publicByDefault;
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

    /** Whether outsiders get this permission on a freshly created claim. */
    public boolean publicByDefault() {
        return publicByDefault;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Optional<ClaimPermission> byKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (ClaimPermission permission : values()) {
            if (permission.name().equals(normalised)) {
                return Optional.of(permission);
            }
        }
        return Optional.empty();
    }
}
