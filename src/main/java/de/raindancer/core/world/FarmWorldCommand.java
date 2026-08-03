package de.raindancer.core.world;

import de.raindancer.core.RainsCore;
import de.raindancer.core.chat.Chat;
import de.raindancer.core.moderation.Durations;
import de.raindancer.core.util.Scheduling;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /farmworld} — going to the farm world, and running it.
 *
 * <h2>Why regenerating asks twice</h2>
 * It deletes three worlds. Everything else this command does is reversible and that is not, so it
 * takes a second word — {@code /farmworld regen <name> confirm} — rather than a click somebody can
 * make by tabbing past it. The refusal is deliberate friction, not politeness.
 */
public final class FarmWorldCommand implements BasicCommand {

    private static final String USE = "rainscore.farmworld.use";
    private static final String MANAGE = "rainscore.farmworld.manage";

    /**
     * Nothing is held: this is registered from the bootstrapper, before the plugin is enabled.
     *
     * <p>The scheduler is needed for the teleport, which has to run on the thread owning the
     * destination's region — so it is looked up when the command runs rather than captured now.
     */
    public FarmWorldCommand() {
    }

    private Plugin plugin() {
        return org.bukkit.Bukkit.getPluginManager().getPlugin("RainsCore");
    }

    private FarmWorlds farms() {
        return RainsCore.get().farmWorlds();
    }

    private Chat chat() {
        return RainsCore.get().chatFor("Worlds");
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
            goToTheOnlyOne(sender);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender);
            case "create" -> create(sender, args);
            case "delete" -> delete(sender, args);
            case "regen", "regenerate" -> regenerate(sender, args);
            case "info" -> info(sender, args);
            default -> go(sender, args[0]);
        }
    }

    // ------------------------------------------------------------------------ going

    /** {@code /farmworld} with no name: the one there is, when there is exactly one. */
    private void goToTheOnlyOne(CommandSender sender) {
        List<WorldSet> all = farms().state().all();
        if (all.size() == 1) {
            go(sender, all.getFirst().name());
            return;
        }
        list(sender);
    }

    private void go(CommandSender sender, String name) {
        if (!(sender instanceof Player player)) {
            chat().no(sender, "Only a player can go to a world.");
            return;
        }
        Optional<WorldSet> set = farms().state().byName(name);
        if (set.isEmpty()) {
            chat().no(player, "There is no farm world called <name>.", Chat.arg("name", name));
            return;
        }
        World world = Bukkit.getWorld(set.get().overworld());
        if (world == null) {
            chat().warn(player, "<name> is not loaded right now.", Chat.arg("name", name));
            return;
        }
        // Somewhere safe rather than 0,0: a farm world's spawn is wherever the generator put it and
        // may well be inside a mountain.
        var target = world.getSpawnLocation();
        Scheduling.region(plugin(), target, () -> {
            player.teleport(world.getSpawnLocation());
            chat().ok(player, "Off to <name>.", Chat.arg("name", name));
        });
    }

    // ------------------------------------------------------------------------ listing

    private void list(CommandSender sender) {
        List<WorldSet> all = farms().state().all();
        if (all.isEmpty()) {
            chat().tell(sender, "<gray>There are no farm worlds.");
            return;
        }
        chat().tell(sender, "<gray><count> farm world(s):", Chat.arg("count", all.size()));
        for (WorldSet set : all) {
            chat().row(sender, "<dark_gray>  <white>" + set.name() + "<dark_gray> — <gray>"
                    + set.worlds().size() + " world(s)"
                    + set.regenerateEvery()
                            .map(every -> ", made again every " + Durations.describe(every))
                            .orElse(", made again only when asked"));
        }
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length < 2) {
            chat().tell(sender, "<gray>/farmworld info <name>");
            return;
        }
        farms().state().byName(args[1]).ifPresentOrElse(set -> {
            chat().tell(sender, "<white><name></white>:", Chat.arg("name", set.name()));
            for (String world : set.worlds()) {
                chat().row(sender, "<dark_gray>  " + world
                        + (Bukkit.getWorld(world) == null ? " <red>(not loaded)" : " <gray>(loaded)"));
            }
            set.border().ifPresent(radius ->
                    chat().row(sender, "<dark_gray>  border: <gray>" + radius + " blocks"));
            farms().state().lastRegenerated(set.name()).ifPresent(when ->
                    set.until(when, Instant.now()).ifPresentOrElse(
                            left -> chat().row(sender, "<dark_gray>  made again in <gray>"
                                    + Durations.describe(left)),
                            () -> chat().row(sender, "<dark_gray>  due to be made again")));
        }, () -> chat().no(sender, "There is no farm world called <name>.",
                Chat.arg("name", args[1])));
    }

    // ------------------------------------------------------------------------ managing

    private void create(CommandSender sender, String[] args) {
        if (!sender.hasPermission(MANAGE)) {
            chat().no(sender, "That is not yours to change.");
            return;
        }
        if (args.length < 2) {
            chat().tell(sender, "<gray>/farmworld create <name> [every] [border]");
            chat().row(sender, "<dark_gray>  e.g. /farmworld create farmworld 7d 5000");
            return;
        }
        try {
            WorldSet.Builder built = WorldSet.builder(args[1]);
            if (args.length >= 3) {
                Durations.parse(args[2]).ifPresent(built::every);
            }
            if (args.length >= 4) {
                built.border(Integer.parseInt(args[3]));
            }
            WorldSet set = built.build();
            if (farms().state().byName(set.name()).isPresent()) {
                chat().no(sender, "<name> is already a farm world.", Chat.arg("name", set.name()));
                return;
            }
            farms().state().define(set);
            chat().tell(sender, "<gray>Making <name> — the server will pause for a moment.",
                    Chat.arg("name", set.name()));
            List<World> made = farms().ensure(set);
            farms().state().flush();
            chat().ok(sender, "<name> is ready: <count> world(s).",
                    Chat.arg("name", set.name()), Chat.arg("count", made.size()));
        } catch (IllegalArgumentException refused) {
            // The name rules live in WorldSet and exist to stop somebody deleting the server.
            chat().no(sender, "<reason>", Chat.arg("reason", refused.getMessage()));
        }
    }

    private void delete(CommandSender sender, String[] args) {
        if (!sender.hasPermission(MANAGE)) {
            chat().no(sender, "That is not yours to change.");
            return;
        }
        if (args.length < 2) {
            chat().tell(sender, "<gray>/farmworld delete <name>");
            return;
        }
        if (farms().state().undefine(args[1])) {
            farms().state().flush();
            chat().ok(sender, "<name> is no longer a farm world. Its worlds are left as they are.",
                    Chat.arg("name", args[1]));
        } else {
            chat().no(sender, "There is no farm world called <name>.", Chat.arg("name", args[1]));
        }
    }

    /**
     * Throws a farm world away and makes it again.
     *
     * <p>Asks twice, because this deletes three worlds and everything else here is reversible.
     */
    private void regenerate(CommandSender sender, String[] args) {
        if (!sender.hasPermission(MANAGE)) {
            chat().no(sender, "That is not yours to change.");
            return;
        }
        if (args.length < 2) {
            chat().tell(sender, "<gray>/farmworld regen <name> confirm");
            return;
        }
        Optional<WorldSet> set = farms().state().byName(args[1]);
        if (set.isEmpty()) {
            chat().no(sender, "There is no farm world called <name>.", Chat.arg("name", args[1]));
            return;
        }
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            chat().warn(sender, "This deletes <count> world(s) and makes them again. "
                            + "Everybody in them is moved to spawn.",
                    Chat.arg("count", set.get().worlds().size()));
            chat().row(sender, "<dark_gray>  say <white>/farmworld regen "
                    + set.get().name() + " confirm</white> if you mean it");
            return;
        }
        chat().tell(sender, "<gray>Making <name> again — the server will pause.",
                Chat.arg("name", set.get().name()));
        boolean ok = farms().regenerate(set.get());
        if (ok) {
            chat().ok(sender, "<name> is new.", Chat.arg("name", set.get().name()));
        } else {
            chat().no(sender, "Something went wrong; the server log has it. "
                    + "Check which of its worlds are loaded.");
        }
    }

    // ------------------------------------------------------------------------ completion

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (!RainsCore.isAvailable()) {
            return List.of();
        }
        CommandSender sender = source.getSender();
        List<String> names = farms().state().all().stream().map(WorldSet::name).toList();
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> options = new java.util.ArrayList<>(names);
            options.addAll(List.of("list", "info"));
            if (sender.hasPermission(MANAGE)) {
                options.addAll(List.of("create", "delete", "regen"));
            }
            return options.stream().filter(word -> word.toLowerCase(Locale.ROOT).startsWith(typed))
                    .toList();
        }
        if (args.length == 2) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            return names.stream().filter(name -> name.startsWith(typed)).toList();
        }
        // The confirmation is not completed on purpose: it is meant to be typed deliberately.
        if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            return List.of("7d", "14d", "30d");
        }
        return List.of();
    }
}
