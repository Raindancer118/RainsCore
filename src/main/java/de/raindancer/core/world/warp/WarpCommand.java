package de.raindancer.core.world.warp;

import de.raindancer.core.RainsCore;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.platform.util.Scheduling;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * {@code /warp} — going to a named place, and managing the list of them.
 *
 * <h2>Why one command rather than two</h2>
 * {@code /warp} for players and {@code /warpadmin} for owners would be two things to learn and two
 * places for the list of warps to be got from. One command, with the managing subcommands hidden
 * behind a permission, means a player types {@code /warp} and sees exactly what they can do.
 *
 * <p>Everything it decides lives in {@link Warps}, tested without a server. This is argument
 * handling and messages — deliberately, because that is the part a test cannot check anyway.
 */
public final class WarpCommand implements BasicCommand {

    private static final String USE = "rainscore.warp.use";
    private static final String MANAGE = "rainscore.warp.manage";

    /**
     * Nothing is held: this is registered from the bootstrapper, before the plugin is enabled.
     *
     * <p>The scheduler is needed for the teleport, which has to run on the thread owning the
     * destination's region — so it is looked up when the command runs rather than captured now.
     */
    public WarpCommand() {
    }

    private Plugin plugin() {
        return org.bukkit.Bukkit.getPluginManager().getPlugin("RainsCore");
    }

    private Warps warps() {
        return RainsCore.get().warps();
    }

    private Chat chat() {
        return RainsCore.get().chatFor("Warps");
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission(USE) || sender.hasPermission(MANAGE);
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!RainsCore.isAvailable()) {
            return;
        }
        if (args.length == 0) {
            list(sender);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender);
            case "set" -> set(sender, args);
            case "delete", "remove" -> delete(sender, args);
            case "permission" -> permission(sender, args);
            case "category" -> category(sender, args);
            // Anything else is a warp's name, so /warp spawn works without a "go" subcommand
            // nobody would think to type.
            default -> go(sender, args[0]);
        }
    }

    // ------------------------------------------------------------------------ going

    private void go(CommandSender sender, String name) {
        if (!(sender instanceof Player player)) {
            chat().no(sender, "Only a player can warp somewhere.");
            return;
        }
        if (!warps().mayUse(player.getUniqueId(), name, player::hasPermission)) {
            // The same answer as an unknown warp, deliberately: telling somebody a warp exists but
            // is not for them is telling them the staff warps are called 'staff'.
            chat().no(player, "There is no warp called <name>.", Chat.arg("name", name));
            return;
        }
        WarpUse outcome = warps().use(player.getUniqueId(), name);
        switch (outcome) {
            case WENT -> {
                Location target = warps().locationOf(name).orElse(null);
                if (target == null) {
                    chat().no(player, "That warp's world is not loaded right now.");
                    return;
                }
                // A teleport has to happen on the thread that owns the destination's region, which
                // on Folia is not the one a command runs on.
                Scheduling.region(plugin(), target, () -> {
                    player.teleport(target);
                    chat().ok(player, "Off to <name>.", Chat.arg("name", name));
                });
            }
            case UNKNOWN -> chat().no(player, "There is no warp called <name>.",
                    Chat.arg("name", name));
            case WORLD_MISSING -> chat().warn(player,
                    "<name> is in a world that is not loaded right now.", Chat.arg("name", name));
            case ON_COOLDOWN -> warps().remaining(player.getUniqueId()).ifPresent(left ->
                    chat().warn(player, "You can warp again in <time>.",
                            Chat.arg("time",
                                    de.raindancer.core.moderation.punishment.Durations.describe(left))));
            case NOT_ALLOWED -> chat().no(player, "That warp is not yours to use.");
        }
    }

    // ------------------------------------------------------------------------ listing

    private void list(CommandSender sender) {
        List<Warp> visible = sender instanceof Player player
                ? warps().visibleTo(player.getUniqueId(), player::hasPermission)
                : warps().all();
        if (visible.isEmpty()) {
            chat().tell(sender, "<gray>There are no warps yet.");
            return;
        }
        chat().tell(sender, "<gray><count> warp(s):", Chat.arg("count", visible.size()));
        for (Warp warp : visible) {
            String category = warp.category().map(name -> " <dark_gray>(" + name + ")").orElse("");
            chat().row(sender, "<dark_gray>  <white>" + warp.name() + "<dark_gray> — <gray>"
                    + de.raindancer.core.ui.tablist.TablistModel.worldLabel(warp.world())
                    + " " + warp.coordinates() + category
                    + (warp.isReachable() ? "" : " <red>(world not loaded)"));
        }
    }

    // ------------------------------------------------------------------------ managing

    private void set(CommandSender sender, String[] args) {
        if (!sender.hasPermission(MANAGE)) {
            chat().no(sender, "That is not yours to change.");
            return;
        }
        if (!(sender instanceof Player player)) {
            chat().no(sender, "A warp is set where somebody is standing, so only a player can.");
            return;
        }
        if (args.length < 2) {
            chat().tell(sender, "<gray>/warp set <name>");
            return;
        }
        warps().create(args[1], player.getLocation(), player.getUniqueId())
                .ifPresentOrElse(
                        warp -> {
                            RainsCore.get().places().flush();
                            chat().ok(player, "<name> is set, here.", Chat.arg("name", warp.name()));
                        },
                        () -> chat().no(player, "That is not a name a warp can have."));
    }

    private void delete(CommandSender sender, String[] args) {
        if (!sender.hasPermission(MANAGE)) {
            chat().no(sender, "That is not yours to change.");
            return;
        }
        if (args.length < 2) {
            chat().tell(sender, "<gray>/warp delete <name>");
            return;
        }
        if (warps().delete(args[1])) {
            RainsCore.get().places().flush();
            chat().ok(sender, "<name> is gone.", Chat.arg("name", args[1]));
        } else {
            chat().no(sender, "There is no warp called <name>.", Chat.arg("name", args[1]));
        }
    }

    private void permission(CommandSender sender, String[] args) {
        if (!sender.hasPermission(MANAGE)) {
            chat().no(sender, "That is not yours to change.");
            return;
        }
        if (args.length < 2) {
            chat().tell(sender, "<gray>/warp permission <name> [permission]");
            return;
        }
        String permission = args.length >= 3 ? args[2] : null;
        if (warps().setPermission(args[1], permission)) {
            RainsCore.get().places().flush();
            chat().ok(sender, permission == null
                            ? "<name> is open to everybody again."
                            : "<name> now needs <permission>.",
                    Chat.arg("name", args[1]), Chat.arg("permission", String.valueOf(permission)));
        } else {
            chat().no(sender, "There is no warp called <name>.", Chat.arg("name", args[1]));
        }
    }

    private void category(CommandSender sender, String[] args) {
        if (!sender.hasPermission(MANAGE)) {
            chat().no(sender, "That is not yours to change.");
            return;
        }
        if (args.length < 2) {
            chat().tell(sender, "<gray>/warp category <name> [category]");
            return;
        }
        String category = args.length >= 3 ? args[2] : null;
        if (warps().setCategory(args[1], category)) {
            RainsCore.get().places().flush();
            chat().ok(sender, "<name> filed.", Chat.arg("name", args[1]));
        } else {
            chat().no(sender, "There is no warp called <name>.", Chat.arg("name", args[1]));
        }
    }

    // ------------------------------------------------------------------------ completion

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (!RainsCore.isAvailable()) {
            return List.of();
        }
        CommandSender sender = source.getSender();
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> options = new java.util.ArrayList<>(names(sender));
            options.add("list");
            if (sender.hasPermission(MANAGE)) {
                options.addAll(List.of("set", "delete", "permission", "category"));
            }
            return options.stream().filter(word -> word.toLowerCase(Locale.ROOT).startsWith(typed))
                    .limit(50).toList();
        }
        if (args.length == 2 && sender.hasPermission(MANAGE)) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            return names(sender).stream()
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed))
                    .limit(50).toList();
        }
        return List.of();
    }

    /** Only the warps this sender can actually see — completion must not leak a staff warp's name. */
    private List<String> names(CommandSender sender) {
        if (sender instanceof Player player) {
            return warps().visibleTo(player.getUniqueId(), player::hasPermission).stream()
                    .map(Warp::name).toList();
        }
        return warps().names();
    }
}
