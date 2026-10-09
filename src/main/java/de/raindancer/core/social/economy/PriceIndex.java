package de.raindancer.core.social.economy;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.PluginCode;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleSupplier;

/**
 * How far prices have moved since the server started measuring them, so a fee an owner wrote in coins a
 * year ago is worth as much today: a warp that cost 100 when iron was 5 costs 200 once iron is 10.
 *
 * <p>Whoever measures prices — the economy module, with a basket of goods — provides the level, and decides
 * whether fees should follow it at all; with nobody providing, or the owner having said no, the level is
 * {@code 1.0} and every fee is what was written. The newest provider answers.
 */
public final class PriceIndex {

    /** The level is held here at most: one absurd price must not make every fee unpayable. */
    public static final double MOST = 100.0;

    private static final LogChannel log = Log.of("economy");

    private record Provided(Plugin owner, DoubleSupplier level) {
    }

    private static final List<Provided> providers = new CopyOnWriteArrayList<>();

    private PriceIndex() {
    }

    /** @param level the factor fees are multiplied by now; {@code 1.0} for "as written" */
    public static synchronized void provide(Plugin owner, DoubleSupplier level) {
        if (level != null && providers.stream().noneMatch(each -> each.level() == level)) {
            providers.add(new Provided(owner, level));
        }
    }

    public static synchronized void retract(DoubleSupplier level) {
        providers.removeIf(each -> each.level() == level);
    }

    /** The factor fees follow, {@code 1.0} when nobody measures or the answer makes no sense. */
    public static double level() {
        if (providers.isEmpty()) {
            return 1.0;
        }
        double level;
        try {
            level = providers.getLast().level().getAsDouble();
        } catch (RuntimeException broken) {
            log.error(broken, "The price level could not be read; fees stay as written.");
            return 1.0;
        }
        if (Double.isNaN(level) || level <= 0) {
            return 1.0;
        }
        return Math.min(level, MOST);
    }

    /** {@code written} at today's prices. Rounded up, as a fee is. */
    public static Money follow(Money written) {
        double level = level();
        if (written == null || !written.isPositive() || level == 1.0) {
            return written == null ? Money.ZERO : written;
        }
        return Money.of(BigDecimal.valueOf(written.minor())
                .multiply(BigDecimal.valueOf(level))
                .setScale(0, RoundingMode.CEILING)
                .min(BigDecimal.valueOf(Long.MAX_VALUE))
                .longValue());
    }

    public static synchronized int forgetFrom(ClassLoader loader) {
        int before = providers.size();
        providers.removeIf(each -> PluginCode.isFrom(each.level(), loader));
        return before - providers.size();
    }

    public static synchronized void clear() {
        providers.clear();
    }
}
