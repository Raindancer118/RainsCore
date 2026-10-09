package de.raindancer.core.social.economy;

import de.raindancer.core.data.store.YamlStore;
import org.bukkit.configuration.ConfigurationSection;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Extra slots players buy — another home, another claim: what the next one costs (each one dearer by a percent),
 * how many one player may buy, and how many each has, kept in a file. Charged through {@link Fees}, so the price
 * index and the economy's levers apply, and a slot paid for is never lost on a failed write: it is refunded.
 *
 * <p>The file is {@code bought.<uuid>: count}, the shape the homes module has always written.
 */
public final class BuyableSlots {

    /**
     * What the owner decided.
     *
     * @param on            the switch
     * @param price         the first slot, as written; each further one costs {@code growthPercent} more
     * @param mostPerPlayer how many one player may buy; zero or less for no limit
     */
    public record Terms(boolean on, Money price, int growthPercent, int mostPerPlayer) {
    }

    public enum Outcome { BOUGHT, OFF, NO_PRICE, MAXED, NOT_ENOUGH, NO_ECONOMY, REFUSED, NOT_SAVED }

    /** @param paid what was taken, after the price index and levers; zero unless bought */
    public record Purchase(Outcome outcome, Money paid, int owned) {
    }

    private final YamlStore file;
    private final String source;
    private final String reason;
    private final Map<UUID, Integer> counts = new HashMap<>();

    /**
     * @param source the economy source of the price, {@code homes.slot}
     * @param reason the statement line, "Extra home slot"
     */
    public BuyableSlots(Path file, String source, String reason) {
        this.file = new YamlStore(file);
        this.source = source;
        this.reason = reason;
    }

    public synchronized void load() {
        counts.clear();
        ConfigurationSection section = file.read().getConfigurationSection("bought");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                int count = section.getInt(key);
                if (count > 0) {
                    counts.put(UUID.fromString(key), count);
                }
            } catch (IllegalArgumentException notAnId) {
                // Skipped; the file is only ever written here.
            }
        }
    }

    public synchronized int bought(UUID player) {
        return counts.getOrDefault(player, 0);
    }

    /** The written price of the next slot after {@code alreadyBought}; compounding, rounded down, saturating. */
    public static Money priceOfNext(Money base, int growthPercent, int alreadyBought) {
        if (base == null || !base.isPositive()) {
            return Money.ZERO;
        }
        long growth = Math.max(0, growthPercent);
        Money price = base;
        for (int step = 0; step < Math.max(0, alreadyBought) && growth > 0; step++) {
            BigInteger raised = BigInteger.valueOf(price.minor()).multiply(BigInteger.valueOf(100 + growth))
                    .divide(BigInteger.valueOf(100));
            if (raised.bitLength() > 62) {
                return Money.of(Long.MAX_VALUE);
            }
            price = Money.of(raised.longValueExact());
        }
        return price;
    }

    /** The next slot's written price for this player. */
    public Money nextPrice(UUID player, Terms terms) {
        return priceOfNext(terms.price(), terms.growthPercent(), bought(player));
    }

    /** What the next slot would actually cost now, as the player should read it. */
    public Money quote(UUID player, Terms terms) {
        return Fees.quote(source, nextPrice(player, terms));
    }

    /** Why the next slot cannot be offered to this player, or empty when it can. */
    public Optional<Outcome> why(UUID player, Terms terms) {
        if (!terms.on()) {
            return Optional.of(Outcome.OFF);
        }
        if (terms.price() == null || !terms.price().isPositive()) {
            return Optional.of(Outcome.NO_PRICE);
        }
        if (terms.mostPerPlayer() > 0 && bought(player) >= terms.mostPerPlayer()) {
            return Optional.of(Outcome.MAXED);
        }
        return Optional.empty();
    }

    /** Buys one slot: charged, then written down; refunded when it cannot be written. */
    public synchronized Purchase buy(UUID player, Terms terms) {
        Optional<Outcome> refused = why(player, terms);
        if (refused.isPresent()) {
            return new Purchase(refused.get(), Money.ZERO, bought(player));
        }
        EconomyResult charged = Fees.charge(player, nextPrice(player, terms), reason, source);
        if (!charged.succeeded()) {
            Outcome outcome = switch (charged.outcome()) {
                case NOT_ENOUGH -> Outcome.NOT_ENOUGH;
                case UNAVAILABLE -> Outcome.NO_ECONOMY;
                default -> Outcome.REFUSED;
            };
            return new Purchase(outcome, Money.ZERO, bought(player));
        }
        int before = bought(player);
        counts.put(player, before + 1);
        boolean written = file.write(yaml -> counts.forEach((who, count) -> yaml.set("bought." + who, count)));
        if (!written) {
            if (before == 0) {
                counts.remove(player);
            } else {
                counts.put(player, before);
            }
            Fees.refund(player, charged.amount(), reason + " not saved", source);
            return new Purchase(Outcome.NOT_SAVED, Money.ZERO, before);
        }
        return new Purchase(Outcome.BOUGHT, charged.amount(), before + 1);
    }
}
