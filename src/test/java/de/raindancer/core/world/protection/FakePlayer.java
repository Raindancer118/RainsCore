package de.raindancer.core.world.protection;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A player with a uuid, a set of permissions, and a record of what was said to them.
 *
 * <p>Built with a {@link Proxy} because {@link Player} has hundreds of methods and the land decision asks
 * two of them. The alternative is not testing the single most important rule in the plugin — whether
 * somebody may build somewhere — because faking a player is tedious, and that is not a good trade.
 */
final class FakePlayer {

    private final UUID id = UUID.randomUUID();
    private final Set<String> permissions = new HashSet<>();
    private final List<String> actionBar = new ArrayList<>();
    private final Player player;

    FakePlayer() {
        this.player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "hasPermission" -> args[0] instanceof String node && permissions.contains(node);
                    case "sendActionBar" -> {
                        if (args[0] instanceof Component said) {
                            actionBar.add(PlainTextComponentSerializer.plainText().serialize(said));
                        }
                        yield null;
                    }
                    case "getName" -> "Fake" + id.toString().substring(0, 4);
                    case "toString" -> "a fake player";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultFor(method.getReturnType());
                });
    }

    private static Object defaultFor(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == void.class) {
            return null;
        }
        return 0;
    }

    FakePlayer holding(String... nodes) {
        permissions.addAll(List.of(nodes));
        return this;
    }

    UUID id() {
        return id;
    }

    Player player() {
        return player;
    }

    List<String> actionBar() {
        return actionBar;
    }
}
