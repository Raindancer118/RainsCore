package de.raindancer.core.content.pack;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The three lines that actually put a pack on a player's screen.
 *
 * <p>Everything worth getting right about resource packs — what goes in one, who has it, when to
 * send it and when not to send it again — lives above this and is tested without a server. This is
 * the seam, and it is deliberately the dullest class in the package.
 *
 * <p>The one judgement in here is that a player who is not online is not an error: a pack sent as
 * somebody is disconnecting is ordinary, and it must not become a stack trace in the log.
 */
public final class BukkitPackSink implements PackSink {

    private static final LogChannel log = Log.of("pack");

    @Override
    public void send(UUID player, List<PackOffer> offers) {
        Player online = Bukkit.getPlayer(player);
        if (online == null || offers == null || offers.isEmpty()) {
            return;
        }
        try {
            List<ResourcePackInfo> packs = new ArrayList<>(offers.size());
            for (PackOffer offer : offers) {
                packs.add(ResourcePackInfo.resourcePackInfo()
                        .id(offer.id())
                        .uri(URI.create(offer.url()))
                        .hash(offer.sha1())
                        .build());
            }
            PackOffer first = offers.get(0);
            ResourcePackRequest.Builder request = ResourcePackRequest.resourcePackRequest()
                    .packs(packs)
                    .required(first.required())
                    // Replaces rather than adds to what the player already has: these are the packs
                    // the server sends, and adding would leave somebody wearing one that has been
                    // rebuilt since. The stacking that matters is *within* this list.
                    .replace(true);
            if (first.prompt() != null) {
                request.prompt(first.prompt());
            }
            online.sendResourcePacks(request.build());
        } catch (IllegalArgumentException badUrl) {
            // A URL a server owner typed. Saying which one is the difference between a fixable
            // problem and "the resource pack does not work".
            log.error("Cannot send the resource pack: {}", badUrl.getMessage());
        }
    }

    @Override
    public void clear(UUID player) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            online.clearResourcePacks();
        }
    }
}
