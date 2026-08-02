package de.raindancer.core.platform;

import de.raindancer.core.chat.Audiences;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;

import java.util.Collection;

/**
 * {@link Audiences} against the running server.
 *
 * <p>The whole of the Bukkit half of chat, which is the point of the seam: everything worth testing
 * lives on the other side of it.
 */
public final class BukkitAudiences implements Audiences {

    @Override
    public Collection<? extends Audience> everyone() {
        return Bukkit.getOnlinePlayers();
    }

    @Override
    public Audience console() {
        return Bukkit.getConsoleSender();
    }
}
