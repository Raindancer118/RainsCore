package de.raindancer.core.social.economy;

import java.util.Optional;

/** Stops a shop selling one kind of item for a while — say, while the server is collecting it from players. */
@FunctionalInterface
public interface SaleStop {

    /**
     * @param material upper case
     * @return why it is not sold now, in words a player reads, or empty when it may be
     */
    Optional<String> reason(String material);
}
