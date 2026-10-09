package de.raindancer.core.social.economy;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.PluginCode;
import org.bukkit.plugin.Plugin;

import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What a single player pays or is paid, where that differs from the shop's price for everybody.
 *
 * <p>The shop owns the prices; anything else on the server — roles, events, ranks — changes them per
 * player here, without the shop knowing it exists. Every modifier is asked and the percentages add up,
 * so two discounts of 10% are 20% rather than 19%: a player can check that in their head.
 *
 * <p>Buying rounds up and selling rounds down, the same direction the shop already rounds, so no
 * combination of discounts ever hands out a cent. A positive price never drops below
 * {@link #LARGEST_DISCOUNT}: stacking enough discounts must not make anything free by accident.
 */
public final class PriceModifiers {

    /** The most a price can fall, in percent, whatever is stacked. */
    public static final int LARGEST_DISCOUNT = -90;

    private static final LogChannel log = Log.of("economy");

    private record Provided(Plugin owner, PriceModifier modifier) {
    }

    // Read for every price a shop window draws, written once per plugin start.
    private static final List<Provided> providers = new CopyOnWriteArrayList<>();

    private PriceModifiers() {
    }

    /** Starts changing prices. Providing the same modifier twice is one registration. */
    public static synchronized void provide(Plugin owner, PriceModifier modifier) {
        if (modifier != null && providers.stream().noneMatch(each -> each.modifier() == modifier)) {
            providers.add(new Provided(owner, modifier));
        }
    }

    /** Stops it. Harmless for one that was never provided. */
    public static synchronized void retract(PriceModifier modifier) {
        providers.removeIf(each -> each.modifier() == modifier);
    }

    public static boolean isAnyProvided() {
        return !providers.isEmpty();
    }

    /** What {@code player} pays for {@code base}'s worth of {@code material}. Rounded up. */
    public static PersonalPrice buy(UUID player, String material, Money base) {
        return price(player, material, base, TradeSide.BUY);
    }

    /** What {@code player} is paid for {@code base}'s worth of {@code material}. Rounded down. */
    public static PersonalPrice sell(UUID player, String material, Money base) {
        return price(player, material, base, TradeSide.SELL);
    }

    public static PersonalPrice price(UUID player, String material, Money base, TradeSide side) {
        if (player == null || material == null || base == null || !base.isPositive() || providers.isEmpty()) {
            return base == null ? PersonalPrice.unchanged(Money.ZERO) : PersonalPrice.unchanged(base);
        }
        String name = material.toUpperCase(Locale.ROOT);
        int percent = 0;
        List<String> reasons = new ArrayList<>();
        for (Provided each : providers) {
            Optional<PriceChange> change;
            try {
                change = each.modifier().change(player, name, side);
            } catch (RuntimeException broken) {
                log.error(broken, "A price change for {} could not be worked out; it is left out.", name);
                continue;
            }
            if (change == null || change.isEmpty() || change.get().percent() == 0) {
                continue;
            }
            percent += change.get().percent();
            if (!change.get().reason().isBlank()) {
                reasons.add(change.get().reason());
            }
        }
        percent = Math.clamp(percent, LARGEST_DISCOUNT, 1000);
        if (percent == 0) {
            return PersonalPrice.unchanged(base);
        }
        return new PersonalPrice(base, scaled(base, percent, side), percent, reasons);
    }

    /**
     * {@code base} changed by {@code percent}, rounded against the player: up when buying, down when selling.
     * For applying one player's change to a whole line — ten of something — rather than rounding each one.
     */
    public static Money scale(Money base, int percent, TradeSide side) {
        if (base == null || !base.isPositive() || percent == 0) {
            return base == null ? Money.ZERO : base;
        }
        return scaled(base, Math.clamp(percent, LARGEST_DISCOUNT, 1000), side);
    }

    private static Money scaled(Money base, int percent, TradeSide side) {
        BigInteger exact = BigInteger.valueOf(base.minor()).multiply(BigInteger.valueOf(100L + percent));
        long minor = new java.math.BigDecimal(exact)
                .divide(java.math.BigDecimal.valueOf(100), 0,
                        side == TradeSide.BUY ? RoundingMode.CEILING : RoundingMode.FLOOR)
                .min(java.math.BigDecimal.valueOf(Long.MAX_VALUE))
                .longValue();
        return Money.of(side == TradeSide.BUY ? Math.max(1, minor) : minor);
    }

    /**
     * Drops whatever {@code loader}'s code provided — for a plugin disabled without retracting.
     *
     * @return how many were dropped
     */
    public static synchronized int forgetFrom(ClassLoader loader) {
        int before = providers.size();
        providers.removeIf(each -> PluginCode.isFrom(each.modifier(), loader));
        return before - providers.size();
    }

    /** Forgets everything — for Core shutting down, and for tests. */
    public static synchronized void clear() {
        providers.clear();
    }
}
