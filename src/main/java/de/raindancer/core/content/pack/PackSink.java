package de.raindancer.core.content.pack;

import java.util.List;
import java.util.UUID;

/**
 * The one thing in this package that talks to the server.
 *
 * <p>Everything else — what to build, who to send it to, whether they have it already, what the
 * client said — is arithmetic and bookkeeping and is tested without a server. This interface is
 * where that stops, which is why it has two methods and no logic.
 */
public interface PackSink {

    /**
     * Offers packs to a player — all of them in one request.
     *
     * <p>A list because the client stacks packs and applies them in order. One at a time would be a
     * prompt per pack and a chance per pack for the order to come out wrong.
     */
    void send(UUID player, List<PackOffer> offers);

    /** Takes whatever pack they have back off again. */
    void clear(UUID player);
}
