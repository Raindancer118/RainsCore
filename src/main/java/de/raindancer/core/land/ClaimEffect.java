package de.raindancer.core.land;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;
import java.util.Optional;

/**
 * A potion effect the claim grants to everybody inside it.
 * <p>
 * Stored as type plus amplifier rather than as a full {@link PotionEffect}, because the duration is not
 * the owner's business: the effect is refreshed while a player stands inside and expires shortly after
 * they leave, so it can never be carried out of the claim.
 */
public final class ClaimEffect {

    /** Refresh window in ticks — comfortably longer than the refresh interval so it never flickers. */
    public static final int DURATION_TICKS = 200;

    private final PotionEffectType type;
    private int amplifier;
    private boolean showParticles;

    public ClaimEffect(PotionEffectType type, int amplifier, boolean showParticles) {
        this.type = type;
        this.amplifier = Math.max(0, Math.min(4, amplifier));
        this.showParticles = showParticles;
    }

    public PotionEffectType type() {
        return type;
    }

    public int amplifier() {
        return amplifier;
    }

    public void amplifier(int amplifier) {
        this.amplifier = Math.max(0, Math.min(4, amplifier));
    }

    public boolean showParticles() {
        return showParticles;
    }

    public void showParticles(boolean showParticles) {
        this.showParticles = showParticles;
    }

    /** Roman-ish level as players know it: amplifier 0 is "I". */
    public int level() {
        return amplifier + 1;
    }

    /**
     * Builds the effect to apply.
     * <p>
     * {@code ambient} is on so the screen overlay stays subtle, and the icon is shown so players can see
     * where the effect comes from.
     */
    public PotionEffect toPotionEffect() {
        return new PotionEffect(type, DURATION_TICKS, amplifier, true, showParticles, true);
    }

    public String key() {
        return type.getKey().getKey();
    }

    public String displayName() {
        String raw = type.getKey().getKey().replace('_', ' ');
        return raw.substring(0, 1).toUpperCase(Locale.ROOT) + raw.substring(1);
    }

    public String serialize() {
        return type.getKey().asString() + ";" + amplifier + ";" + showParticles;
    }

    public static Optional<ClaimEffect> deserialize(String raw) {
        String[] parts = raw.split(";");
        if (parts.length < 3) {
            return Optional.empty();
        }
        return byKey(parts[0]).map(type -> {
            int amplifier;
            try {
                amplifier = Integer.parseInt(parts[1]);
            } catch (NumberFormatException malformed) {
                amplifier = 0;
            }
            return new ClaimEffect(type, amplifier, Boolean.parseBoolean(parts[2]));
        });
    }

    /** Resolves an effect by its key, with or without the {@code minecraft:} namespace. */
    public static Optional<PotionEffectType> byKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalised = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        NamespacedKey key = normalised.contains(":")
                ? NamespacedKey.fromString(normalised)
                : NamespacedKey.minecraft(normalised);
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(Registry.EFFECT.get(key));
    }
}
