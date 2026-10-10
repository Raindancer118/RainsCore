package de.raindancer.core.social.roles;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.PluginCode;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Which role a player took, for plugins that are not the roles plugin — quests for miners, a tag in chat —
 * so they need not depend on it. Provided by the roles plugin; with none, nobody has a role.
 */
public final class PlayerRoles {

    private static final LogChannel log = Log.of("roles");

    private record Provided(Plugin owner, RoleSource source) {
    }

    private static final List<Provided> providers = new CopyOnWriteArrayList<>();

    private PlayerRoles() {
    }

    /** Providing the same source twice is one registration. */
    public static synchronized void provide(Plugin owner, RoleSource source) {
        if (source != null && providers.stream().noneMatch(each -> each.source() == source)) {
            providers.add(new Provided(owner, source));
        }
    }

    public static synchronized void retract(RoleSource source) {
        providers.removeIf(each -> each.source() == source);
    }

    public static Optional<HeldRole> of(UUID player) {
        if (player == null) {
            return Optional.empty();
        }
        for (Provided each : providers) {
            try {
                Optional<HeldRole> role = each.source().roleOf(player);
                if (role != null && role.isPresent()) {
                    return role;
                }
            } catch (RuntimeException broken) {
                log.error(broken, "The role of {} could not be asked; taken as none.", player);
            }
        }
        return Optional.empty();
    }

    public static synchronized int forgetFrom(ClassLoader loader) {
        int before = providers.size();
        providers.removeIf(each -> PluginCode.isFrom(each.source(), loader));
        return before - providers.size();
    }

    public static synchronized void clear() {
        providers.clear();
    }
}
