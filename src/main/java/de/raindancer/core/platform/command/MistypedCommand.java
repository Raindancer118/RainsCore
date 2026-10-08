package de.raindancer.core.platform.command;

import de.raindancer.core.platform.util.Closest;
import de.raindancer.core.ui.chat.ChatButton;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.text.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.command.UnknownCommandEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * "Did you mean": a command nobody has, answered with the ones close to it — as buttons, for a player.
 *
 * <p>Only commands the sender may use are guessed, so a typo never advertises a staff command. A guess
 * without arguments runs on click; one with arguments is only put in the chat box, because
 * {@code /bna Steve} must not become a ban on one click.
 */
public final class MistypedCommand {

    static final int GUESSES = 3;

    private MistypedCommand() {
    }

    /**
     * One guess.
     *
     * @param command the command's name, without slash
     * @param line    the whole line as it was probably meant, with slash and the original arguments
     * @param bare    whether nothing followed the command
     */
    public record Guess(String command, String line, boolean bare) {
    }

    /** Up to {@code limit} guesses for {@code typed} — a command line, slash or not — among {@code usable}. */
    public static List<Guess> closest(String typed, Collection<String> usable, int limit) {
        if (typed == null || usable == null) {
            return List.of();
        }
        String line = typed.strip();
        if (line.startsWith("/")) {
            line = line.substring(1);
        }
        int space = line.indexOf(' ');
        String label = space < 0 ? line : line.substring(0, space);
        String rest = space < 0 ? "" : line.substring(space + 1).strip();
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        if (label.isEmpty()) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (String name : usable) {
            if (name != null && !name.isBlank() && name.indexOf(':') < 0) {
                names.add(name.toLowerCase(Locale.ROOT));
            }
        }
        names.remove(label.toLowerCase(Locale.ROOT));
        List<Guess> guesses = new ArrayList<>();
        for (String name : Closest.to(label, names, limit)) {
            guesses.add(new Guess(name, "/" + name + (rest.isEmpty() ? "" : " " + rest), rest.isEmpty()));
        }
        return guesses;
    }

    /** The names in {@code commands} that {@code sender} may run. */
    static List<String> usableBy(CommandSender sender, Map<String, Command> commands) {
        List<String> usable = new ArrayList<>();
        for (Map.Entry<String, Command> entry : commands.entrySet()) {
            if (entry.getValue() != null && entry.getValue().testPermissionSilent(sender)) {
                usable.add(entry.getKey());
            }
        }
        return usable;
    }

    /** Swaps the server's "Unknown command" for the guesses, when there are any. */
    public static final class Listener implements org.bukkit.event.Listener {

        private final Supplier<CommandMap> commandMap;
        private final Messages words;
        private final ChatButtons buttons;
        /**
         * What each player's client was told it may run. Command#testPermissionSilent is wrong for
         * Brigadier commands (a non-op was offered /mspt but not /list), this list is what tab
         * completion shows them.
         */
        private final Map<UUID, Set<String>> sent = new ConcurrentHashMap<>();

        public Listener(Supplier<CommandMap> commandMap, Messages words, ChatButtons buttons) {
            this.commandMap = commandMap;
            this.words = words;
            this.buttons = buttons;
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onSent(PlayerCommandSendEvent event) {
            sent.put(event.getPlayer().getUniqueId(), Set.copyOf(event.getCommands()));
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            sent.remove(event.getPlayer().getUniqueId());
        }

        @EventHandler
        public void onUnknown(UnknownCommandEvent event) {
            CommandSender sender = event.getSender();
            Collection<String> usable = sender instanceof Player player && sent.containsKey(player.getUniqueId())
                    ? sent.get(player.getUniqueId())
                    : usableBy(sender, commandMap.get().getKnownCommands());
            List<Guess> guesses = closest(event.getCommandLine(), usable, GUESSES);
            if (guesses.isEmpty()) {
                // Nothing close: the server's own message, which at least says where to look.
                return;
            }
            String typed = event.getCommandLine().strip();
            int space = typed.indexOf(' ');
            String label = (space < 0 ? typed : typed.substring(0, space)).replaceFirst("^/", "");
            event.message(words.prefixed("command.unknown", "command", label)
                    .append(Component.newline())
                    .append(words.get("command.did-you-mean"))
                    .append(guessed(sender, guesses))
                    .append(words.get("command.did-you-mean-end")));
        }

        private Component guessed(CommandSender sender, List<Guess> guesses) {
            boolean clickable = sender instanceof Player && buttons != null;
            if (!clickable) {
                return Component.text(String.join(", ", guesses.stream().map(Guess::line).toList()));
            }
            List<ChatButton> row = new ArrayList<>();
            for (Guess guess : guesses) {
                String shown = Text.literal(guess.line());
                ChatButton button = buttons.label("<aqua>[" + shown + "]");
                row.add(guess.bare()
                        ? button.tooltip("<gray>Click to run <white>" + shown).runs(guess.line())
                        : button.tooltip("<gray>Click to put <white>" + shown + "</white> in the chat")
                                .suggests(guess.line()));
            }
            return buttons.row(row.toArray(ChatButton[]::new));
        }
    }
}
