package de.raindancer.core.data.stash;

import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Optional;

/**
 * The four armour stands of a {@link Stash}.
 *
 * <p>Read from the material's name rather than {@code Material#getEquipmentSlot}, which goes through the
 * server's item data and so cannot be asked in a test. A carved pumpkin or a head can be worn too, but is
 * not armour anybody wants kept on a stand — those go with everything else.
 */
public enum ArmourPiece {

    HEAD("Helmet", EquipmentSlot.HEAD, Material.IRON_HELMET),
    CHEST("Chestplate", EquipmentSlot.CHEST, Material.IRON_CHESTPLATE),
    LEGS("Leggings", EquipmentSlot.LEGS, Material.IRON_LEGGINGS),
    FEET("Boots", EquipmentSlot.FEET, Material.IRON_BOOTS);

    private final String label;
    private final EquipmentSlot slot;
    private final Material icon;

    ArmourPiece(String label, EquipmentSlot slot, Material icon) {
        this.label = label;
        this.slot = slot;
        this.icon = icon;
    }

    public static Optional<ArmourPiece> of(Material material) {
        if (material == null) {
            return Optional.empty();
        }
        String name = material.name();
        if (name.endsWith("_HELMET")) {
            return Optional.of(HEAD);
        }
        if (name.endsWith("_CHESTPLATE") || material == Material.ELYTRA) {
            return Optional.of(CHEST);
        }
        if (name.endsWith("_LEGGINGS")) {
            return Optional.of(LEGS);
        }
        if (name.endsWith("_BOOTS")) {
            return Optional.of(FEET);
        }
        return Optional.empty();
    }

    public String label() {
        return label;
    }

    public EquipmentSlot slot() {
        return slot;
    }

    public Material icon() {
        return icon;
    }
}
