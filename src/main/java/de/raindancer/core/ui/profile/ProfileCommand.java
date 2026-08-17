package de.raindancer.core.ui.profile;

import de.raindancer.core.RainsCore;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The command behind a clickable player name. Never typed by a person on purpose — see
 * {@link de.raindancer.core.ui.chat.ClickCommand}, which this mirrors exactly, for why a clickable
 * thing in chat has to be a command at all.
 *
 * <p>Unlike a chat button this takes the subject's id directly rather than an opaque token: viewing
 * somebody's profile is not a one-time offer that needs spending or a player it needs binding to —
 * anybody who can already see a name in chat may look the same person up by hand, so a stale click on
 * a line read back an hour later still works instead of answering "that button is no longer offered".
 */
public final class ProfileCommand implements BasicCommand {

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player viewer)) {
            return;
        }
        if (!RainsCore.isAvailable() || args.length != 1) {
            return;
        }
        UUID subject;
        try {
            subject = UUID.fromString(args[0]);
        } catch (IllegalArgumentException notAnId) {
            return;   // somebody typed it by hand; nothing useful to offer them
        }
        ProfileMenu.open(viewer, RainsCore.get().chatFor("Core").brand(), subject);
    }

    /** Deliberately no completions — this is not meant to be typed. */
    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return List.of();
    }
}
