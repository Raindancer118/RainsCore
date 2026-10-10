package de.raindancer.core.platform.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

/**
 * Which game version a player's client really is, when ViaVersion lets clients of other versions
 * join. Their packets reach the server translated, so physics and packet order are the other
 * version's, not the server's. Without ViaVersion every client counts as the server's own version.
 *
 * <p>ViaVersion is read by reflection: no dependency, and a ViaVersion that changed its API makes
 * everybody look native rather than breaking anything.
 */
public final class ClientVersions {

    /** A game version: its protocol number and its name, like {@code 1.21.11}. */
    public record Version(int protocol, String name) {
    }

    private static volatile Via via;

    private record Via(Object api, Method player, Method server, Method highest, Method number, Method name) {
    }

    private ClientVersions() {
    }

    /** The version this player's client speaks, while they are online and ViaVersion knows them. */
    public static Optional<Version> of(UUID player) {
        Via bound = bind();
        if (bound == null) {
            return Optional.empty();
        }
        try {
            return version(bound, bound.player().invoke(bound.api(), player));
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** The server's own version, as ViaVersion sees it. */
    public static Optional<Version> server() {
        Via bound = bind();
        if (bound == null) {
            return Optional.empty();
        }
        try {
            return version(bound, bound.highest().invoke(bound.server().invoke(bound.api())));
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** Whether this player's client is another version than the server, its packets translated. */
    public static boolean translated(UUID player) {
        Optional<Version> client = of(player);
        Optional<Version> server = client.isEmpty() ? Optional.empty() : server();
        return client.isPresent() && server.isPresent() && differs(client.get().protocol(), server.get().protocol());
    }

    static boolean differs(int client, int server) {
        return client > 0 && server > 0 && client != server;
    }

    private static Optional<Version> version(Via bound, Object protocolVersion) throws ReflectiveOperationException {
        if (protocolVersion == null) {
            return Optional.empty();
        }
        int number = (int) bound.number().invoke(protocolVersion);
        return number <= 0 ? Optional.empty() : Optional.of(new Version(number, String.valueOf(bound.name().invoke(protocolVersion))));
    }

    private static Via bind() {
        Via bound = via;
        if (bound != null) {
            return bound;
        }
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("ViaVersion");
            if (plugin == null || !plugin.isEnabled()) {
                return null;
            }
            ClassLoader loader = plugin.getClass().getClassLoader();
            Class<?> entry = Class.forName("com.viaversion.viaversion.api.Via", true, loader);
            Class<?> apiType = Class.forName("com.viaversion.viaversion.api.ViaAPI", true, loader);
            Class<?> serverType = Class.forName("com.viaversion.viaversion.api.protocol.version.ServerProtocolVersion", true, loader);
            Class<?> versionType = Class.forName("com.viaversion.viaversion.api.protocol.version.ProtocolVersion", true, loader);
            Object api = entry.getMethod("getAPI").invoke(null);
            bound = new Via(api, apiType.getMethod("getPlayerProtocolVersion", UUID.class), apiType.getMethod("getServerVersion"),
                    serverType.getMethod("highestSupportedProtocolVersion"), versionType.getMethod("getVersion"),
                    versionType.getMethod("getName"));
            via = bound;
            return bound;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            return null;
        }
    }
}
