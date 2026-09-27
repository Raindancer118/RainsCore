package de.raindancer.core.ui.profile;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;

/**
 * A player's own on/off for one thing — "show me the particle trail", "no sounds for me". Kept in the
 * player's own data, so it survives restarts and needs no file of its own; {@code owner} namespaces it
 * so two plugins' switches of the same name never collide.
 *
 * <p>The server-wide setting, where there is one, stays the owner's to check first: a switch is a
 * player opting out of something the server offers, not a way round the server turning it off.
 */
public record PlayerSwitch(String owner, String name, boolean defaultOn) {

    public PlayerSwitch {
        if (owner == null || name == null || name.isBlank()
                || !owner.equals(owner.toLowerCase(Locale.ROOT)) || !owner.matches("[a-z0-9._-]+")
                || !name.matches("[a-z0-9._-]+")) {
            throw new IllegalArgumentException("not a valid switch: " + owner + ":" + name);
        }
    }

    public NamespacedKey key() {
        return new NamespacedKey(owner, "switch-" + name);
    }

    public boolean isOn(Player player) {
        Boolean stored = player.getPersistentDataContainer().get(key(), PersistentDataType.BOOLEAN);
        return stored == null ? defaultOn : stored;
    }

    public void set(Player player, boolean on) {
        player.getPersistentDataContainer().set(key(), PersistentDataType.BOOLEAN, on);
    }

    /** Flips it. @return what it is now */
    public boolean toggle(Player player) {
        boolean now = !isOn(player);
        set(player, now);
        return now;
    }
}
