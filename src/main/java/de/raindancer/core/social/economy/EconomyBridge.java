package de.raindancer.core.social.economy;

import org.bukkit.plugin.Plugin;

import java.util.Optional;

/**
 * Where {@link Economies} meets an economy system outside Rain's plugins — Vault, on a real server.
 *
 * <p>Both directions: a Rain economy is offered to every Vault plugin, and when no Rain economy is
 * installed a foreign one is used through Vault, so a claims fee works on a server running EssentialsX
 * money just the same.
 */
public interface EconomyBridge {

    EconomyBridge NONE = new EconomyBridge() {
    };

    /** A Rain economy has just started providing money. */
    default void exported(Plugin owner, Economy economy) {
    }

    /** It has stopped. */
    default void retracted(Economy economy) {
    }

    /** An economy from outside, used only while no Rain economy is provided. */
    default Optional<Economy> foreign() {
        return Optional.empty();
    }
}
