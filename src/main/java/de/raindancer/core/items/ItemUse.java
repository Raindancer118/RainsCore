package de.raindancer.core.items;

import java.util.Optional;
import java.util.UUID;

/**
 * One use of an ability: who, what, and why it fired.
 *
 * <p>A record rather than a handful of arguments, so an ability's signature does not change every
 * time a trigger needs to pass something new — and so an ability can be written and tested without
 * a server, which is the whole point of keeping the effect separate from the machinery.
 *
 * @param charges what the holder has left <em>after</em> this use, or empty when unlimited
 */
public record ItemUse(UUID player, String ability, ItemTrigger trigger, Integer charges) {

    /** What is left after this use, or empty for an ability that never runs out. */
    public Optional<Integer> chargesLeft() {
        return Optional.ofNullable(charges);
    }

    /** Whether this was the last one — i.e. whether the item should now be taken away. */
    public boolean wasLast() {
        return charges != null && charges <= 0;
    }
}
