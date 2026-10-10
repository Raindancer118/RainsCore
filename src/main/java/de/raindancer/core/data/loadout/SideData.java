package de.raindancer.core.data.loadout;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.PluginCode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * What belongs to one of a player's sides besides their things — the cosmetics they wear as admin, say, and the
 * ones they wear in survival. Plugins register parts; {@link Loadouts} captures them with a loadout and puts them
 * back with it, so an admin mode that swaps loadouts swaps these too, without knowing what they are.
 *
 * <p>A part is either a namespace of the player's persistent data ({@link #keepNamespace}) or something a plugin
 * captures and applies itself ({@link #keep}). After a side is put on, {@link #onSwitched} listeners are told, to
 * show what changed.
 */
public final class SideData {

    private static final LogChannel log = Log.of("loadout");

    /** Something that belongs to a side. */
    public interface Part {

        /** Unique and stable: what it is saved under. */
        String id();

        /** What it is on this player now, or null for nothing. On the player's own thread. */
        String capture(Player player);

        /** Puts it on this player; null is "nothing". On the player's own thread. */
        void apply(Player player, String value);
    }

    private record Provided(Plugin owner, Part part) {
    }

    private record Listener(Plugin owner, Consumer<Player> listener) {
    }

    private static final List<Provided> parts = new CopyOnWriteArrayList<>();
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private SideData() {
    }

    public static synchronized void keep(Plugin owner, Part part) {
        if (part != null && parts.stream().noneMatch(each -> each.part().id().equals(part.id()))) {
            parts.add(new Provided(owner, part));
        }
    }

    /** Every key of the player's persistent data in this namespace ("rainscosmetics") belongs to a side. */
    public static Part keepNamespace(Plugin owner, String namespace) {
        Part part = new NamespacePart(namespace);
        keep(owner, part);
        return part;
    }

    public static synchronized void retract(Part part) {
        parts.removeIf(each -> each.part() == part);
    }

    public static void onSwitched(Plugin owner, Consumer<Player> listener) {
        if (listener != null) {
            listeners.add(new Listener(owner, listener));
        }
    }

    public static synchronized void stopListening(Consumer<Player> listener) {
        listeners.removeIf(each -> each.listener() == listener);
    }

    /** Everything registered, as this player has it now. */
    public static Map<String, String> capture(Player player) {
        Map<String, String> data = new LinkedHashMap<>();
        for (Provided each : parts) {
            try {
                String value = each.part().capture(player);
                if (value != null) {
                    data.put(each.part().id(), value);
                }
            } catch (RuntimeException broken) {
                log.error(broken, "Side data {} could not be captured for {}; it is left out.", each.part().id(),
                        player.getName());
            }
        }
        return data;
    }

    /**
     * Puts a side's data on a player: every registered part gets its value, or nothing when the side had none —
     * then the listeners are told.
     */
    public static void apply(Player player, Map<String, String> data) {
        for (Provided each : parts) {
            try {
                each.part().apply(player, data.get(each.part().id()));
            } catch (RuntimeException broken) {
                log.error(broken, "Side data {} could not be put on {}.", each.part().id(), player.getName());
            }
        }
        for (Listener each : listeners) {
            try {
                each.listener().accept(player);
            } catch (RuntimeException broken) {
                log.error(broken, "A side-switch listener threw for {}.", player.getName());
            }
        }
    }

    public static synchronized int forgetFrom(ClassLoader loader) {
        int before = parts.size() + listeners.size();
        parts.removeIf(each -> PluginCode.isFrom(each.part(), loader) || each.owner() != null
                && PluginCode.isFrom(each.owner(), loader));
        listeners.removeIf(each -> PluginCode.isFrom(each.listener(), loader));
        return before - parts.size() - listeners.size();
    }

    public static synchronized void clear() {
        parts.clear();
        listeners.clear();
    }
}
