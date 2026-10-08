package de.raindancer.core.ui.menu;

import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Where a player head's skin comes from. A head given only an id makes Paper ask Mojang for the skin
 * every time it is drawn — a player list of a busy server is enough to get rate-limited — while the
 * server already holds the skin of everybody online, from their login. So: their own profile while
 * online, remembered for later; Mojang only for somebody never seen with one; and never for a Bedrock
 * player, whom Mojang does not know.
 */
public final class HeadSkins {

    static final int CAPACITY = 1024;

    /** @param profile a profile with textures to put on the head, or null; @param lookUp whether to fall back to Mojang */
    public record Skin(PlayerProfile profile, boolean lookUp) {
    }

    private final Map<UUID, PlayerProfile> known = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, PlayerProfile> eldest) {
            return size() > CAPACITY;
        }
    };

    public synchronized Skin of(OfflinePlayer who) {
        UUID id = who.getUniqueId();
        Player online = who.getPlayer();
        if (online != null) {
            PlayerProfile own = online.getPlayerProfile();
            if (own != null && own.hasTextures()) {
                PlayerProfile copy = own.clone();
                known.put(id, copy);
                return new Skin(copy, false);
            }
        }
        PlayerProfile remembered = known.get(id);
        if (remembered != null) {
            return new Skin(remembered, false);
        }
        return new Skin(null, !isBedrock(id));
    }

    /** Floodgate gives Bedrock players ids whose upper half is zero; no Java account has one. */
    static boolean isBedrock(UUID id) {
        return id.getMostSignificantBits() == 0;
    }

    synchronized int size() {
        return known.size();
    }
}
