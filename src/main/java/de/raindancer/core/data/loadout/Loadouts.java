package de.raindancer.core.data.loadout;

import de.raindancer.core.data.nbt.ItemText;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads a {@link Loadout} off a player and puts one onto a player. Both must run on the player's own
 * thread. Moving them to {@link Loadout#place()} is the caller's — a teleport is asynchronous and this is
 * not.
 */
public final class Loadouts {

    private final ItemText codec;

    public Loadouts(ItemText codec) {
        this.codec = codec;
    }

    public static Loadouts ofTheServer() {
        return new Loadouts(ItemText.ofTheServer());
    }

    public Loadout capture(Player player) {
        List<Loadout.Effect> effects = new ArrayList<>();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            effects.add(new Loadout.Effect(effect.getType().getKey().asString(), effect.getDuration(),
                    effect.getAmplifier(), effect.isAmbient(), effect.hasParticles(), effect.hasIcon()));
        }
        Location at = player.getLocation();
        Loadout.Place place = at == null || at.getWorld() == null ? null
                : new Loadout.Place(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(),
                at.getPitch());
        return new Loadout(encode(player.getInventory().getContents()),
                encode(player.getEnderChest().getContents()),
                player.getLevel(), player.getExp(), player.getHealth(), player.getFoodLevel(),
                player.getSaturation(), player.getGameMode().name(), player.getAllowFlight(), player.isFlying(),
                effects, place, SideData.capture(player));
    }

    /** How many items in this loadout this server cannot make — each would be lost by applying it. */
    public int unreadable(Loadout loadout) {
        int count = 0;
        for (String line : loadout.inventory()) {
            if (!line.isEmpty() && codec.read(line) == null) {
                count++;
            }
        }
        for (String line : loadout.enderChest()) {
            if (!line.isEmpty() && codec.read(line) == null) {
                count++;
            }
        }
        return count;
    }

    /**
     * Replaces what the player carries and is with this loadout.
     *
     * @return false, changing nothing, when an item in it cannot be read — applying it anyway would put
     *         an empty slot where the item was, and the next capture would make that permanent
     */
    public boolean apply(Player player, Loadout loadout) {
        if (unreadable(loadout) > 0) {
            return false;
        }
        player.getInventory().setContents(decode(loadout.inventory(), player.getInventory().getContents().length));
        player.getEnderChest().setContents(decode(loadout.enderChest(), player.getEnderChest().getContents().length));

        GameMode mode = gameMode(loadout.gameMode());
        if (mode != null) {
            player.setGameMode(mode);
        }
        player.setAllowFlight(loadout.allowFlight() || mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR);
        player.setFlying(player.getAllowFlight() && loadout.flying());

        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        double most = maxHealth == null ? 20.0 : maxHealth.getValue();
        player.setHealth(Math.min(most, loadout.health()));
        player.setFoodLevel(loadout.food());
        player.setSaturation(loadout.saturation());
        player.setLevel(loadout.level());
        player.setExp(loadout.exp());
        player.setFireTicks(0);
        player.setFallDistance(0);

        for (PotionEffect active : player.getActivePotionEffects()) {
            player.removePotionEffect(active.getType());
        }
        for (Loadout.Effect effect : loadout.effects()) {
            PotionEffectType type = effectType(effect.type());
            if (type != null) {
                player.addPotionEffect(new PotionEffect(type, effect.ticks(), effect.amplifier(), effect.ambient(),
                        effect.particles(), effect.icon()));
            }
        }
        if (loadout.data() != null) {
            SideData.apply(player, loadout.data());
        }
        return true;
    }

    private List<String> encode(ItemStack[] contents) {
        List<String> slots = new ArrayList<>(contents.length);
        for (ItemStack item : contents) {
            String line = codec.write(item);
            slots.add(line == null ? "" : line);
        }
        return slots;
    }

    /**
     * Sized to the inventory being filled: Bukkit refuses an array larger than the inventory, and the
     * loadout is padded to vanilla's size, which a mocked or modded inventory may not report.
     */
    private ItemStack[] decode(List<String> slots, int size) {
        int length = size <= 0 ? slots.size() : size;
        ItemStack[] items = new ItemStack[length];
        for (int slot = 0; slot < Math.min(length, slots.size()); slot++) {
            items[slot] = slots.get(slot).isEmpty() ? null : codec.read(slots.get(slot));
        }
        return items;
    }

    private static GameMode gameMode(String name) {
        try {
            return GameMode.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return null;
        }
    }

    private static PotionEffectType effectType(String key) {
        NamespacedKey parsed = NamespacedKey.fromString(key);
        return parsed == null ? null : Registry.EFFECT.get(parsed);
    }
}
