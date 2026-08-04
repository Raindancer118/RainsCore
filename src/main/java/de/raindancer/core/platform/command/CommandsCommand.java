package de.raindancer.core.platform.command;

import de.raindancer.core.RainsCore;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * {@code /commands} — the directory, as a book.
 *
 * <h2>Why it filters</h2>
 * A reader is shown what they may use. Not greyed: absent. A directory that lists every staff command
 * to every player is a directory that teaches the server's whole moderation vocabulary to somebody
 * who cannot run any of it, and the first thing that follows is thirty "you may not do that" messages
 * from people finding out one at a time.
 *
 * <h2>Why the console gets lines instead</h2>
 * A console cannot open a book. It gets the same directory printed, which is also the form somebody
 * grepping a log wants.
 *
 * <p>Nothing is held: registered from a bootstrapper, before anything exists. Everything is looked up
 * when the command is actually run.
 */
public final class CommandsCommand implements BasicCommand {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    @Override
    public boolean canUse(CommandSender sender) {
        // Deliberately everybody. The filtering is per entry, so the answer is never an empty book —
        // every player has at least the commands nothing guards.
        return true;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        CommandDirectory directory = RainsCore.get().commands();

        String wanted = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : null;
        List<CommandNote> visible = directory.visibleTo(sender::hasPermission).stream()
                .filter(note -> wanted == null
                        || note.plugin().toLowerCase(Locale.ROOT).contains(wanted)
                        || note.command().contains(wanted))
                .toList();

        if (sender instanceof Player reader) {
            reader.openBook(new CommandBook(visible, title(wanted)).asBook());
            return;
        }
        // The console, or anything else that is not holding a book.
        sender.sendMessage(MINI.deserialize("<dark_aqua>" + visible.size() + " command(s):"));
        for (CommandNote note : visible) {
            sender.sendMessage(MINI.deserialize("<blue>" + note.slashed() + " <gray>— "
                    + MINI.escapeTags(note.sentence())));
            for (String option : note.options()) {
                sender.sendMessage(MINI.deserialize("<dark_gray>    " + MINI.escapeTags(option)));
            }
        }
    }

    private static String title(String wanted) {
        return wanted == null ? "Commands" : "Commands: " + wanted;
    }

    /** The plugins that have reported anything, so {@code /commands wa<tab>} finds Warps. */
    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length > 1) {
            return List.of();
        }
        String typed = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        return RainsCore.get().commands().plugins().stream()
                .filter(plugin -> plugin.toLowerCase(Locale.ROOT).startsWith(typed))
                .toList();
    }
}
