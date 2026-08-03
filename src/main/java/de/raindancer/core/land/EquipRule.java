package de.raindancer.core.land;

import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Optional;

/**
 * One auto-equip entry: an item the claim keeps its people supplied with, and where it goes.
 * <p>
 * The destination is deliberately explicit rather than inferred every time. A totem belongs in the off
 * hand, an elytra in the chest slot, and fireworks on a particular hotbar key that the owner picks — the
 * item alone cannot express that last one at all.
 */
public final class EquipRule {

    /** Where a restocked item is placed. */
    public enum Target {
        AUTO("Where it belongs", "Armour to its slot, elytra to the chest, anything else to the hotbar"),
        OFF_HAND("Off hand", "The shield slot — totems, shields, a spare map"),
        HEAD("Helmet", "The helmet slot"),
        CHEST("Chestplate", "The chest slot — also where an elytra goes"),
        LEGS("Leggings", "The leggings slot"),
        FEET("Boots", "The boots slot"),
        HOTBAR("Hotbar slot", "A specific hotbar key you choose");

        private final String displayName;
        private final String description;

        Target(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String displayName() {
            return displayName;
        }

        public String description() {
            return description;
        }

        public Target next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<Target> byKey(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            String normalised = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            for (Target target : values()) {
                if (target.name().equals(normalised)) {
                    return Optional.of(target);
                }
            }
            return Optional.empty();
        }

        /** The equipment slot this target maps to, or empty for the hotbar. */
        public Optional<EquipmentSlot> toEquipmentSlot() {
            return switch (this) {
                case OFF_HAND -> Optional.of(EquipmentSlot.OFF_HAND);
                case HEAD -> Optional.of(EquipmentSlot.HEAD);
                case CHEST -> Optional.of(EquipmentSlot.CHEST);
                case LEGS -> Optional.of(EquipmentSlot.LEGS);
                case FEET -> Optional.of(EquipmentSlot.FEET);
                default -> Optional.empty();
            };
        }
    }

    private final ItemStack template;
    private Target target;
    /** Hotbar key 0–8, only meaningful for {@link Target#HOTBAR}. */
    private int hotbarSlot;
    /** How many the player should be kept topped up to; one for a totem, a stack for fireworks. */
    private int keepAmount;

    public EquipRule(ItemStack template, Target target, int hotbarSlot, int keepAmount) {
        ItemStack copy = template.clone();
        copy.setAmount(1);
        this.template = copy;
        this.target = target == null ? Target.AUTO : target;
        this.hotbarSlot = Math.max(0, Math.min(8, hotbarSlot));
        this.keepAmount = Math.max(1, Math.min(template.getMaxStackSize(), keepAmount));
    }

    public ItemStack template() {
        return template.clone();
    }

    public Target target() {
        return target;
    }

    public void target(Target target) {
        this.target = target == null ? Target.AUTO : target;
    }

    public int hotbarSlot() {
        return hotbarSlot;
    }

    public void hotbarSlot(int slot) {
        this.hotbarSlot = Math.max(0, Math.min(8, slot));
    }

    public int keepAmount() {
        return keepAmount;
    }

    public void keepAmount(int amount) {
        this.keepAmount = Math.max(1, Math.min(template.getMaxStackSize(), amount));
    }

    /**
     * Resolves {@link Target#AUTO} against the item itself.
     * <p>
     * Reads the {@code EQUIPPABLE} component rather than guessing from the material name, so modded or
     * custom-equippable items land in the right slot too.
     */
    public Target resolvedTarget() {
        if (target != Target.AUTO) {
            return target;
        }
        if (template.hasData(DataComponentTypes.EQUIPPABLE)) {
            var equippable = template.getData(DataComponentTypes.EQUIPPABLE);
            if (equippable != null) {
                return switch (equippable.slot()) {
                    case HEAD -> Target.HEAD;
                    case CHEST -> Target.CHEST;
                    case LEGS -> Target.LEGS;
                    case FEET -> Target.FEET;
                    case OFF_HAND -> Target.OFF_HAND;
                    default -> Target.HOTBAR;
                };
            }
        }
        return Target.HOTBAR;
    }

    public String describeTarget() {
        Target resolved = resolvedTarget();
        if (resolved == Target.HOTBAR) {
            return "hotbar slot " + (hotbarSlot + 1);
        }
        return resolved.displayName();
    }

    public String serialize() {
        return target.key() + ";" + hotbarSlot + ";" + keepAmount;
    }
}
