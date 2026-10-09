package de.raindancer.core.social.economy;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.PluginCode;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Items a shop should not sell right now, and why — asked by whoever runs the shop, provided by anything that
 * pays for items: a server goal collecting cod must not let cod be bought from the shop and handed straight in.
 */
public final class SaleStops {

    private static final LogChannel log = Log.of("economy");

    private record Provided(Plugin owner, SaleStop stop) {
    }

    private static final List<Provided> providers = new CopyOnWriteArrayList<>();

    private SaleStops() {
    }

    /** Providing the same stop twice is one registration. */
    public static synchronized void provide(Plugin owner, SaleStop stop) {
        if (stop != null && providers.stream().noneMatch(each -> each.stop() == stop)) {
            providers.add(new Provided(owner, stop));
        }
    }

    public static synchronized void retract(SaleStop stop) {
        providers.removeIf(each -> each.stop() == stop);
    }

    /** Why this item is not to be sold now, or empty when it may be. */
    public static Optional<String> reason(String material) {
        if (material == null || providers.isEmpty()) {
            return Optional.empty();
        }
        String name = material.toUpperCase(Locale.ROOT);
        for (Provided each : providers) {
            try {
                Optional<String> reason = each.stop().reason(name);
                if (reason != null && reason.isPresent()) {
                    return reason;
                }
            } catch (RuntimeException broken) {
                log.error(broken, "Whether {} may be sold could not be asked; it is left for sale.", name);
            }
        }
        return Optional.empty();
    }

    public static synchronized int forgetFrom(ClassLoader loader) {
        int before = providers.size();
        providers.removeIf(each -> PluginCode.isFrom(each.stop(), loader));
        return before - providers.size();
    }

    public static synchronized void clear() {
        providers.clear();
    }
}
