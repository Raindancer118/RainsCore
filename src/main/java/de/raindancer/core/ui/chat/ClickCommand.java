package de.raindancer.core.ui.chat;

import de.raindancer.core.RainsCore;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;

/**
 * The command behind every chat button. Never typed by a person on purpose.
 *
 * <h2>Why it looks the plugin up rather than being given it</h2>
 * It is registered from the bootstrapper, which runs before {@code onEnable} — so the registry it
 * needs does not exist yet at that point. It asks when it is run instead, by which time everything
 * is up. See {@code RainsCoreBootstrap} for why registration cannot wait until enable.
 */
public final class ClickCommand implements BasicCommand {

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!RainsCore.isAvailable()) {
            return;
        }
        RainsCore core = RainsCore.get();
        Chat chat = core.chatFor("Core");
        if (!(sender instanceof Player clicker)) {
            chat.no(sender, "Only a player can click a button.");
            return;
        }
        if (args.length != 1) {
            // Somebody typed it by hand. There is nothing useful to offer them.
            chat.warn(clicker, "That is not something to type.");
            return;
        }
        ClickResult result = core.clickActions().run(clicker.getUniqueId(), args[0]);
        switch (result) {
            case RAN -> {
                // The action said whatever needed saying.
            }
            case NOT_YOURS -> chat.no(clicker, "That is not your button.");
            case SPENT -> chat.warn(clicker, "You have already answered that.");
            case EXPIRED, UNKNOWN -> chat.warn(clicker, "That is no longer on offer.");
            case FAILED -> chat.no(clicker, "That did not work. The server has written down why.");
        }
    }

    /**
     * Deliberately no completions.
     *
     * <p>Completing tokens would list every pending button on the server, which is exactly the thing
     * being kept out of players' reach.
     */
    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return List.of();
    }
}
