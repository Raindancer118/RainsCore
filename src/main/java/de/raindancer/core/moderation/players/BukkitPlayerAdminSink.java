package de.raindancer.core.moderation.players;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one-line half of player management.
 *
 * <p>Every rule about what is allowed, what would kill somebody and what is already the case lives
 * in {@link PlayerAdmin} and is tested without a server. This is the seam.
 */
public final class BukkitPlayerAdminSink implements PlayerAdminSink {

    private static final LogChannel log = Log.of("players");
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Effect names that turned out not to exist. Complained about once each. */
    private final Set<String> unknown = ConcurrentHashMap.newKeySet();

    @Override
    public Optional<PlayerState> stateOf(UUID who) {
        Player player = Bukkit.getPlayer(who);
        if (player == null) {
            return Optional.empty();
        }
        // The attribute rather than a hardcoded twenty: a server with a plugin that raises maximum
        // health has players for whom twenty is not full, and a heal button that stops there is a
        // heal button that does not heal.
        double max = player.getAttribute(Attribute.MAX_HEALTH) == null
                ? 20 : player.getAttribute(Attribute.MAX_HEALTH).getValue();
        return Optional.of(new PlayerState(player.getHealth(), max, player.getFoodLevel(),
                player.getAllowFlight(), player.getGameMode().name()));
    }

    @Override
    public void health(UUID who, double health) {
        Player player = Bukkit.getPlayer(who);
        if (player != null) {
            double max = player.getAttribute(Attribute.MAX_HEALTH) == null
                    ? 20 : player.getAttribute(Attribute.MAX_HEALTH).getValue();
            // Clamped here as well as decided above: setHealth outside the range throws, and this
            // is the last place before it that can stop that.
            player.setHealth(Math.clamp(health, 0, max));
        }
    }

    @Override
    public void food(UUID who, int food) {
        Player player = Bukkit.getPlayer(who);
        if (player != null) {
            player.setFoodLevel(Math.clamp(food, 0, 20));
        }
    }

    @Override
    public void effect(UUID who, String effect, int level, Duration lasting) {
        Player player = Bukkit.getPlayer(who);
        PotionEffectType type = effectType(effect);
        if (player == null || type == null) {
            return;
        }
        // Amplifier is level - 1: amplifier 0 is level I. Getting this backwards is what makes a
        // "Speed II" button apply Speed III, and it is the most common bug in this whole area.
        int amplifier = Math.max(0, level - 1);
        int ticks = lasting == null
                ? PotionEffect.INFINITE_DURATION : (int) Math.min(Integer.MAX_VALUE,
                        lasting.toMillis() / 50);
        player.addPotionEffect(new PotionEffect(type, ticks, amplifier, false, false, true));
    }

    @Override
    public void clearEffect(UUID who, String effect) {
        Player player = Bukkit.getPlayer(who);
        PotionEffectType type = effectType(effect);
        if (player != null && type != null) {
            player.removePotionEffect(type);
        }
    }

    @Override
    public void clearAllEffects(UUID who) {
        Player player = Bukkit.getPlayer(who);
        if (player != null) {
            player.getActivePotionEffects()
                    .forEach(active -> player.removePotionEffect(active.getType()));
        }
    }

    @Override
    public void allowFlight(UUID who, boolean allowed) {
        Player player = Bukkit.getPlayer(who);
        if (player == null) {
            return;
        }
        player.setAllowFlight(allowed);
        if (!allowed && player.isFlying()) {
            player.setFlying(false);
        }
    }

    @Override
    public void gamemode(UUID who, String mode) {
        Player player = Bukkit.getPlayer(who);
        if (player == null) {
            return;
        }
        try {
            player.setGameMode(GameMode.valueOf(mode));
        } catch (IllegalArgumentException notAMode) {
            log.warn("There is no gamemode called '{}'.", mode);
        }
    }

    @Override
    public void kick(UUID who, String reason) {
        Player player = Bukkit.getPlayer(who);
        if (player != null) {
            Component said = MINI.deserialize("<red>" + MINI.escapeTags(reason));
            player.kick(said);
        }
    }

    @Override
    public void extinguish(UUID who) {
        Player player = Bukkit.getPlayer(who);
        if (player != null) {
            player.setFireTicks(0);
        }
    }

    /** An effect by name, from the registry so a new one works without this class changing. */
    private PotionEffectType effectType(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.trim().toLowerCase(java.util.Locale.ROOT);
        PotionEffectType found = Registry.EFFECT.get(NamespacedKey.minecraft(key));
        if (found == null && unknown.add(key)) {
            log.warn("This server has no effect called '{}'.", key);
        }
        return found;
    }
}
