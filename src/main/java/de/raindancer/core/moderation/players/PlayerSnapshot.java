package de.raindancer.core.moderation.players;

import org.bukkit.Location;
import org.bukkit.Statistic;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Everything worth knowing about somebody right now, read in one go on their own thread — for a status
 * page, an info command, a moderator's player screen. Plain data: safe to hand to any thread afterwards.
 */
public record PlayerSnapshot(
        UUID id, String name,
        double health, double maxHealth, double absorption,
        int food, float saturation, float exhaustion,
        int air, int maxAir, int fireTicks, int freezeTicks,
        int level, float expProgress, int totalExperience,
        String gamemode, boolean allowFlight, boolean flying, float walkSpeed, float flySpeed, double scale,
        int ping, String world, double x, double y, double z, float yaw, float pitch,
        double armour, boolean operator, boolean sneaking, boolean sprinting, boolean swimming,
        boolean gliding, boolean sleeping, boolean inWater,
        String clientBrand, String locale, int viewDistance,
        long firstPlayed, long lastLogin, long playTicks, int deaths,
        List<Effect> effects) {

    /** One active potion effect. Level is the game's amplifier plus one; -1 seconds is forever. */
    public record Effect(String name, int level, int seconds, boolean ambient) {

        public boolean isForever() {
            return seconds < 0;
        }
    }

    public PlayerSnapshot {
        effects = List.copyOf(effects);
    }

    /** Reads {@code player}. Must run on the player's own thread. */
    public static PlayerSnapshot of(Player player) {
        Location at = player.getLocation();
        List<Effect> effects = player.getActivePotionEffects().stream()
                .sorted(Comparator.comparing(effect -> effect.getType().getKey().getKey()))
                .map(PlayerSnapshot::effect)
                .toList();
        return new PlayerSnapshot(
                player.getUniqueId(), player.getName(),
                player.getHealth(), value(player, Attribute.MAX_HEALTH, 20), player.getAbsorptionAmount(),
                player.getFoodLevel(), player.getSaturation(), player.getExhaustion(),
                player.getRemainingAir(), player.getMaximumAir(), player.getFireTicks(), player.getFreezeTicks(),
                player.getLevel(), player.getExp(), player.getTotalExperience(),
                player.getGameMode().name(), player.getAllowFlight(), player.isFlying(),
                player.getWalkSpeed(), player.getFlySpeed(), base(player, Attribute.SCALE, 1),
                player.getPing(), at.getWorld() == null ? "?" : at.getWorld().getName(),
                at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch(),
                value(player, Attribute.ARMOR, 0), player.isOp(), player.isSneaking(), player.isSprinting(),
                player.isSwimming(), player.isGliding(), player.isSleeping(), player.isInWater(),
                player.getClientBrandName() == null ? "unknown" : player.getClientBrandName(),
                player.locale().toString(), player.getClientViewDistance(),
                player.getFirstPlayed(), player.getLastLogin(),
                player.getStatistic(Statistic.PLAY_ONE_MINUTE), player.getStatistic(Statistic.DEATHS),
                effects);
    }

    private static Effect effect(PotionEffect effect) {
        return new Effect(effect.getType().getKey().getKey().toLowerCase(Locale.ROOT).replace('_', ' '),
                effect.getAmplifier() + 1,
                effect.isInfinite() ? -1 : effect.getDuration() / 20,
                effect.isAmbient());
    }

    private static double value(Player player, Attribute attribute, double fallback) {
        AttributeInstance instance = player.getAttribute(attribute);
        return instance == null ? fallback : instance.getValue();
    }

    private static double base(Player player, Attribute attribute, double fallback) {
        AttributeInstance instance = player.getAttribute(attribute);
        return instance == null ? fallback : instance.getBaseValue();
    }

    /** Hunger as the bar shows it: 0–10 drumsticks. */
    public double drumsticks() {
        return food / 2.0;
    }

    public double hearts() {
        return health / 2.0;
    }

    /** Distance to another snapshot in the same world; infinity across worlds. */
    public double distanceTo(PlayerSnapshot other) {
        if (!world.equals(other.world)) {
            return Double.POSITIVE_INFINITY;
        }
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** "N", "NE", … — which way they face, as a compass would say. */
    public String facing() {
        String[] points = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
        double turned = ((yaw % 360) + 360 + 22.5) % 360;
        return points[(int) (turned / 45) % 8];
    }
}
