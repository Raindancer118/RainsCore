package de.raindancer.core.world.teleport;

import java.util.UUID;

/** Where a traveller's own {@link TravelLook} comes from — a cosmetics plugin, typically. */
@FunctionalInterface
public interface TravelLooks {

    /** Nobody has chosen anything. */
    TravelLooks SERVERS = traveller -> TravelLook.SERVERS;

    /** Asked on the traveller's own thread, at every departure, arrival and waiting tick — keep it cheap. */
    TravelLook lookFor(UUID traveller);
}
