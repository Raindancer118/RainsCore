package de.raindancer.core.social.economy;

import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/** What one of an item is worth in the server's money, as whoever prices items says. */
@FunctionalInterface
public interface ItemValuer {

    /** The worth of one {@code item} (its amount is ignored), or empty for something with no price. */
    Optional<Money> valueOf(ItemStack item);
}
