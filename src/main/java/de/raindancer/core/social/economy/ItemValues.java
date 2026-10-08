package de.raindancer.core.social.economy;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.PluginCode;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What an item is worth, for anything that has to turn items into money — paying for something with
 * its value, charging somebody for items they can no longer give back.
 *
 * <p>The prices belong to whichever plugin runs the shop; this is where everybody else asks, the way
 * {@link Economies} is for balances. Static and process-wide for the same reason. Several providers may
 * register; the newest answers and the one before takes over again when it retracts.
 */
public final class ItemValues {

    private static final LogChannel log = Log.of("economy");

    private record Provided(Plugin owner, ItemValuer valuer) {
    }

    private static final List<Provided> providers = new ArrayList<>();

    private ItemValues() {
    }

    /** Starts pricing items. Providing the same valuer twice is one registration. */
    public static void provide(Plugin owner, ItemValuer valuer) {
        if (valuer == null) {
            return;
        }
        synchronized (providers) {
            if (providers.stream().noneMatch(each -> each.valuer() == valuer)) {
                providers.add(new Provided(owner, valuer));
            }
        }
    }

    /** Stops it. Harmless for one that was never provided. */
    public static void retract(ItemValuer valuer) {
        synchronized (providers) {
            providers.removeIf(each -> each.valuer() == valuer);
        }
    }

    public static Optional<ItemValuer> current() {
        synchronized (providers) {
            return providers.isEmpty() ? Optional.empty() : Optional.of(providers.getLast().valuer());
        }
    }

    /**
     * The worth of one {@code item}, or empty when nobody prices items, it has no price, or the pricing
     * failed. A price of zero is no price: nothing should be settled for free by accident.
     */
    public static Optional<Money> valueOf(ItemStack item) {
        if (item == null) {
            return Optional.empty();
        }
        Optional<ItemValuer> valuer = current();
        if (valuer.isEmpty()) {
            return Optional.empty();
        }
        try {
            Optional<Money> value = valuer.get().valueOf(item);
            return value == null ? Optional.empty() : value.filter(Money::isPositive);
        } catch (RuntimeException broken) {
            log.error(broken, "Item prices could not be read.");
            return Optional.empty();
        }
    }

    /**
     * Drops whatever {@code loader}'s code provided — for a plugin disabled without retracting.
     *
     * @return how many were dropped
     */
    public static int forgetFrom(ClassLoader loader) {
        synchronized (providers) {
            int before = providers.size();
            providers.removeIf(each -> PluginCode.isFrom(each.valuer(), loader));
            return before - providers.size();
        }
    }

    /** Forgets everything — for Core shutting down, and for tests. */
    public static void clear() {
        synchronized (providers) {
            providers.clear();
        }
    }
}
