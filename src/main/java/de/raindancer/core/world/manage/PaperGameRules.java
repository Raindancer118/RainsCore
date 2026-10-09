package de.raindancer.core.world.manage;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.World;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** {@link GameRuleAccess} on the server's game rule registry. No decisions here; checked on a real server. */
final class PaperGameRules implements GameRuleAccess {

    @Override
    public Map<String, String> read(World world) {
        Map<String, String> values = new LinkedHashMap<>();
        RegistryAccess.registryAccess().getRegistry(RegistryKey.GAME_RULE).stream().forEach(rule -> {
            Object value = world.getGameRuleValue(rule);
            if (value != null) {
                values.put(nameOf(rule), String.valueOf(value));
            }
        });
        return values;
    }

    @Override
    public Optional<Class<?>> typeOf(String name) {
        return find(name).map(GameRule::getType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void write(World world, String name, Object value) {
        find(name).ifPresent(rule -> world.setGameRule((GameRule<Object>) rule, value));
    }

    /** {@code keep_inventory} for Minecraft's own, the full key for anybody else's. */
    private static String nameOf(GameRule<?> rule) {
        NamespacedKey key = rule.getKey();
        return key.getNamespace().equals(NamespacedKey.MINECRAFT) ? key.getKey() : key.toString();
    }

    private static Optional<GameRule<?>> find(String name) {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.GAME_RULE).stream()
                .filter(rule -> nameOf(rule).equals(name))
                .findFirst();
    }
}
