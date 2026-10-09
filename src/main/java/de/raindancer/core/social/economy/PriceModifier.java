package de.raindancer.core.social.economy;

import java.util.Optional;
import java.util.UUID;

/**
 * Changes what one player pays or is paid for one kind of item — a role's discount, an event's bonus.
 *
 * <p>Asked on the server thread for every price a shop window shows, so it should be a lookup, not work.
 */
@FunctionalInterface
public interface PriceModifier {

    /**
     * @param material the item's material name, upper case
     * @return the change for this player, or empty when this modifier has nothing to say
     */
    Optional<PriceChange> change(UUID player, String material, TradeSide side);
}
