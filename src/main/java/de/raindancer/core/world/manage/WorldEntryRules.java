package de.raindancer.core.world.manage;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Whether a player may go into a world right now — the question, owned here; the answers, registered by
 * whichever plugin locks worlds.
 *
 * <h2>Why Core's</h2>
 * The same arrangement as {@code LandProvider}: a world gate that only cancelled portal events left
 * every command that teleports — a world switcher, a home, a warp — walking straight into a closed End.
 * Whatever moves people asks here; whatever closes worlds answers here; and neither needs the other
 * installed, or even to know it exists.
 *
 * <h2>What an answer is</h2>
 * Empty is "no objection". A refusal is the sentence to show the player, worded by the plugin that
 * refused, because only it knows whether this is "closed for good" or "being drained for an hour".
 *
 * <p>Safe from any thread.
 */
public final class WorldEntryRules {

    private static final LogChannel log = Log.of("world");

    /** One opinion about entering worlds. Must not change anything — it is asked speculatively. */
    @FunctionalInterface
    public interface Rule {
        Optional<Component> refusal(Player player, World target);
    }

    private record Registered(String owner, Rule rule) {
    }

    private final CopyOnWriteArrayList<Registered> rules = new CopyOnWriteArrayList<>();

    /**
     * Adds a rule.
     *
     * @param owner for the log line when the rule throws
     * @return closing it removes the rule — hand it to a module's {@code closeWith}
     */
    public AutoCloseable register(String owner, Rule rule) {
        Registered registered = new Registered(owner == null ? "unknown" : owner, rule);
        if (rule != null) {
            rules.add(registered);
        }
        return () -> rules.remove(registered);
    }

    /** The first refusal any rule gives, or empty when every rule lets them through. */
    public Optional<Component> refusal(Player player, World target) {
        if (player == null || target == null) {
            return Optional.empty();
        }
        for (Registered registered : rules) {
            try {
                Optional<Component> said = registered.rule().refusal(player, target);
                if (said != null && said.isPresent()) {
                    return said;
                }
            } catch (RuntimeException broken) {
                // A bug in one plugin's rule must not close every world on the server.
                log.warn(broken, "The world entry rule from {} failed and was skipped.", registered.owner());
            }
        }
        return Optional.empty();
    }
}
