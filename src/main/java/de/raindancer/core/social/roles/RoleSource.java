package de.raindancer.core.social.roles;

import java.util.Optional;
import java.util.UUID;

/** Whatever knows which role each player took. */
@FunctionalInterface
public interface RoleSource {

    Optional<HeldRole> roleOf(UUID player);
}
