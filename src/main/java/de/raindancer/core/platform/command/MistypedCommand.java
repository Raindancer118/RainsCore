package de.raindancer.core.platform.command;

import de.raindancer.core.RainsCore;
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
import java.util.Arrays;
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

    /**
     * Guesses for the word at {@code at} in {@code args}, a sub-command of {@code command} nobody has,
     * among {@code words}. The words before it and after it stay as they were typed.
     */
    public static List<Guess> closestSub(String command, String[] args, int at, Collection<String> words, int limit) {
        if (command == null || args == null || at < 0 || at >= args.length || words == null) {
            return List.of();
        }
        String typed = args[at];
        if (words.stream().anyMatch(typed::equalsIgnoreCase)) {
            // One of the words after all — not a typo, whatever the caller thought.
            return List.of();
        }
        Set<String> options = new LinkedHashSet<>();
        for (String word : words) {
            if (word != null && !word.isBlank()) {
                options.add(word);
            }
        }
        String before = String.join(" ", Arrays.asList(args).subList(0, at));
        String after = String.join(" ", Arrays.asList(args).subList(at + 1, args.length)).strip();
        String head = "/" + command.replaceFirst("^/", "") + (before.isEmpty() ? "" : " " + before) + " ";
        List<Guess> guesses = new ArrayList<>();
        for (String word : Closest.to(typed, options, limit)) {
            guesses.add(new Guess(word, head + word + (after.isEmpty() ? "" : " " + after), after.isEmpty()));
        }
        return guesses;
    }

    /**
     * For a command's own "no such sub-command" branch: tells {@code sender} what {@code args[at]} was
     * probably meant to be, as buttons. Says nothing and returns false when nothing among {@code words}
     * is close, so the caller can fall back to its usage line.
     *
     * @param command the command's name, as the guess should be typed
     * @param words   what may stand at {@code at} — usually the command's own completions for it
     */
    public static boolean subcommand(CommandSender sender, String command, String[] args, int at,
                                     Collection<String> words) {
        if (sender == null || !RainsCore.isAvailable()) {
            return false;
        }
        List<Guess> guesses = closestSub(command, args, at, words, GUESSES);
        if (guesses.isEmpty()) {
            return false;
        }
        Messages messages = RainsCore.get().messages();
        sender.sendMessage(answer(messages, RainsCore.get().buttons(), sender,
                messages.prefixed("command.unknown-sub", "command", command.replaceFirst("^/", ""),
                        "word", args[at]),
                guesses));
        return true;
    }

    private static Component answer(Messages words, ChatButtons buttons, CommandSender sender, Component headline,
                                    List<Guess> guesses) {
        return headline.append(Component.newline())
                .append(words.get("command.did-you-mean"))
                .append(guessed(buttons, sender, guesses))
                .append(words.get("command.did-you-mean-end"));
    }

    private static Component guessed(ChatButtons buttons, CommandSender sender, List<Guess> guesses) {
        if (!(sender instanceof Player) || buttons == null) {
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
            event.message(answer(words, buttons, sender, words.prefixed("command.unknown", "command", label),
                    guesses));
        }

    }
}
