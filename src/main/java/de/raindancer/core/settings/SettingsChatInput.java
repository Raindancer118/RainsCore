package de.raindancer.core.settings;

import de.raindancer.core.chat.Brand;
import de.raindancer.core.chat.Chat;
import de.raindancer.core.util.Scheduling;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Typing a settings value in chat.
 *
 * <h2>Why chat and not an anvil</h2>
 * An anvil rename is the usual trick and it is worse here: it cannot show what the value is now, nor
 * what it is allowed to be, and both matter more than not leaving the window. So the menu closes,
 * says what it wants along with the current value and the range, and waits for one line.
 *
 * <h2>The two things this has to get right</h2>
 * The line must not reach chat — nobody meant to say "40000" to the server — and the reply must
 * happen on a thread that may touch the world, because {@link AsyncChatEvent} does not. Both are
 * easy to miss and neither shows up until somebody uses it.
 */
public final class SettingsChatInput implements Listener {

    /** Who is being asked for what, and which page to send them back to. */
    private record Waiting(String key, String returnTo) {
    }

    private static final Map<UUID, Waiting> WAITING = new ConcurrentHashMap<>();

    private final Plugin plugin;
    private final SettingsNavigation navigation;
    private final Chat chat;
    private final Brand brand;

    public SettingsChatInput(Plugin plugin, SettingsNavigation navigation, Chat chat, Brand brand) {
        this.plugin = plugin;
        this.navigation = navigation;
        this.chat = chat;
        this.brand = brand;
    }

    /** Called by the menu: the next line this player types is a value for this setting. */
    static void expect(UUID player, String key, String returnTo) {
        WAITING.put(player, new Waiting(key, returnTo));
    }

    /** Whether we are waiting on somebody — for a diagnostic, and for the tests. */
    public static boolean isWaiting(UUID player) {
        return WAITING.containsKey(player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Waiting waiting = WAITING.remove(event.getPlayer().getUniqueId());
        if (waiting == null) {
            return;
        }
        // Nobody meant to say "40000" to the whole server.
        event.setCancelled(true);

        Player player = event.getPlayer();
        String typed = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        // Back onto a thread that may open an inventory: a chat event is not one.
        Scheduling.global(plugin, () -> apply(player, waiting, typed));
    }

    private void apply(Player player, Waiting waiting, String typed) {
        if (typed.equalsIgnoreCase("cancel")) {
            chat.tell(player, "<gray>Left it as it was.");
            reopen(player, waiting);
            return;
        }
        if (navigation.registry().set(waiting.key(), typed)) {
            navigation.registry().saveAll();
            chat.ok(player, "<name> is now <value>.",
                    Chat.arg("name", waiting.key()),
                    Chat.arg("value", navigation.registry().display(waiting.key())));
        } else {
            // Refused rather than clamped, and told why: whoever typed this is standing there.
            chat.no(player, "<value> is not something <name> can be.",
                    Chat.arg("value", typed),
                    Chat.arg("name", waiting.key()));
            navigation.registry().setting(waiting.key()).ifPresent(setting -> {
                if (setting.min() != null) {
                    chat.row(player, "<dark_gray>  it goes from " + setting.min()
                            + " to " + setting.max());
                } else if (!setting.choices().isEmpty()) {
                    chat.row(player, "<dark_gray>  one of: "
                            + String.join(", ", setting.choices()));
                }
            });
        }
        reopen(player, waiting);
    }

    private void reopen(Player player, Waiting waiting) {
        new SettingsMenu(player, brand, chat, navigation, waiting.returnTo(), null).open();
    }

    /** A player who logs out mid-question is not still being asked when they come back. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        WAITING.remove(event.getPlayer().getUniqueId());
    }
}
