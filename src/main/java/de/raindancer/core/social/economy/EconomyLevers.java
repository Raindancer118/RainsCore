package de.raindancer.core.social.economy;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.PluginCode;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * How much of every payout the economy makes and every fee it takes is actually paid — the place anything
 * fighting inflation turns the taps, without the plugin that pays knowing it exists.
 *
 * <p>Percentages from every {@link EconomyLever} add up, as with {@link PriceModifiers}. A payout may be cut
 * to nothing but never below; a fee is never cut by more than {@link #LARGEST_FEE_CUT} percent, so a lever
 * cannot make the server's sinks free by accident. Payouts round down and fees round up: neither direction
 * ever hands out a cent.
 */
public final class EconomyLevers {

    public static final int LARGEST_FEE_CUT = -90;
    public static final int MOST = 1000;

    private static final LogChannel log = Log.of("economy");

    private record Provided(Plugin owner, EconomyLever lever) {
    }

    private static final List<Provided> providers = new CopyOnWriteArrayList<>();

    private EconomyLevers() {
    }

    public static synchronized void provide(Plugin owner, EconomyLever lever) {
        if (lever != null && providers.stream().noneMatch(each -> each.lever() == lever)) {
            providers.add(new Provided(owner, lever));
        }
    }

    public static synchronized void retract(EconomyLever lever) {
        providers.removeIf(each -> each.lever() == lever);
    }

    /** The summed change for a payout from {@code source}, clamped to {@code -100..MOST}. */
    public static int faucetPercent(String source) {
        return Math.clamp(sum(source, EconomyLever::faucetChange), -100, MOST);
    }

    /** The summed change for a fee to {@code source}, clamped to {@code LARGEST_FEE_CUT..MOST}. */
    public static int sinkPercent(String source) {
        return Math.clamp(sum(source, EconomyLever::sinkChange), LARGEST_FEE_CUT, MOST);
    }

    /** What is actually paid out of {@code base} from {@code source}. Rounded down. */
    public static Money faucet(String source, Money base) {
        return scale(base, faucetPercent(source), RoundingMode.FLOOR);
    }

    /** What is actually charged for {@code base} going to {@code source}. Rounded up. */
    public static Money sink(String source, Money base) {
        return scale(base, sinkPercent(source), RoundingMode.CEILING);
    }

    private static int sum(String source, java.util.function.BiFunction<EconomyLever, String, Integer> ask) {
        if (providers.isEmpty()) {
            return 0;
        }
        String key = source == null ? "" : source;
        long total = 0;
        for (Provided each : providers) {
            try {
                total += ask.apply(each.lever(), key);
            } catch (RuntimeException broken) {
                log.error(broken, "An economy lever could not answer for {}; it is left out.", key);
            }
        }
        return (int) Math.clamp(total, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    static Money scale(Money base, int percent, RoundingMode rounding) {
        if (base == null || !base.isPositive() || percent == 0) {
            return base == null ? Money.ZERO : base;
        }
        return Money.of(BigDecimal.valueOf(base.minor())
                .multiply(BigDecimal.valueOf(100L + percent))
                .divide(BigDecimal.valueOf(100), 0, rounding)
                .min(BigDecimal.valueOf(Long.MAX_VALUE))
                .longValue());
    }

    public static synchronized int forgetFrom(ClassLoader loader) {
        int before = providers.size();
        providers.removeIf(each -> PluginCode.isFrom(each.lever(), loader));
        return before - providers.size();
    }

    public static synchronized void clear() {
        providers.clear();
    }
}
