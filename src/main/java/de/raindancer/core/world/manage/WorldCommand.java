package de.raindancer.core.world.manage;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.chat.Chat;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * {@code /world} — jumping to a loaded world, and throwing one away and making it again.
 *
 * <h2>Why this exists next to {@code /farmworld}</h2>
 * {@code /farmworld} (farmworld-module's, built on this package's {@link WorldRegenerator}
 * underneath) manages a set of worlds tied together by its own bookkeeping — a name, a regeneration
 * schedule, a border. Most of the time an owner just wants "send me to that world" or "wipe that one
 * world and start it over", with no schedule and no set to define first. This is that: two
 * subcommands, no state of its own, {@link WorldRegenerator} doing the actual wipe directly.
 *
 * <h2>Two permissions, not one</h2>
 * Switching worlds is harmless; regenerating one deletes it. A server that wants staff to be able to
 * jump around without trusting them to wipe a world grants one and not the other — the same reasoning
 * farmworld-module's own command splits {@code use} from {@code manage} for. Neither is declared in
 * {@code paper-plugin.yml} — see its own comment for why Core declares no permissions — so both fall
 * back to Bukkit's own default for an unregistered node: an operator has it, nobody else does, until a
 * server owner grants it explicitly.
 */
public final class WorldCommand implements BasicCommand {

    private static final String SWITCH = "rainscore.world.switch";
    private static final String REGEN = "rainscore.world.regen";

    private final WorldRegenerator regenerator;

    public WorldCommand() {
        this(new WorldRegenerator());
    }

    /** For tests: a fake regenerator that never touches a real world. */
    WorldCommand(WorldRegenerator regenerator) {
        this.regenerator = regenerator;
    }

    private Plugin plugin() {
        return Bukkit.getPluginManager().getPlugin("RainsCore");
    }

    private Chat chat() {
        return RainsCore.get().chatFor("Worlds");
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission(SWITCH) || sender.hasPermission(REGEN);
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!RainsCore.isAvailable()) {
            return;
        }
        if (args.length == 0) {
            chat().tell(sender, "<gray>/world switch <world>, or /world regen <world> [player]");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "switch", "tp" -> doSwitch(sender, args);
            case "regen", "regenerate" -> regen(sender, args);
            default -> chat().tell(sender, "<gray>/world switch <world>, or /world regen <world> [player]");
        }
    }

    // ------------------------------------------------------------------------ switching

    private void doSwitch(CommandSender sender, String[] args) {
        if (!sender.hasPermission(SWITCH)) {
            chat().no(sender, "That is not yours to use.");
            return;
        }
        if (!(sender instanceof Player player)) {
            chat().no(sender, "Only a player can switch world.");
            return;
        }
        if (args.length < 2) {
            chat().tell(sender, "<gray>/world switch <world>");
            return;
        }
        World world = Bukkit.getWorld(args[1]);
        if (world == null) {
            chat().no(sender, "There is no loaded world called <name>.", Chat.arg("name", args[1]));
            return;
        }
        var target = world.getSpawnLocation();
        Scheduling.region(plugin(), target, () -> {
            player.teleport(target);
            chat().ok(player, "Off to <name>.", Chat.arg("name", world.getName()));
        });
    }

    // ------------------------------------------------------------------------ regenerating

    /**
     * Wipes {@code <world>} and makes it again with a fresh seed — see {@link WorldRegenerator} for
     * why no seed is ever set on purpose — and, if a player was named, drops them straight back into
     * the result.
     */
    private void regen(CommandSender sender, String[] args) {
        if (!sender.hasPermission(REGEN)) {
            chat().no(sender, "That is not yours to change.");
            return;
        }
        if (args.length < 2) {
            chat().tell(sender, "<gray>/world regen <world> [player]");
            return;
        }
        String name = args[1];
        World world = Bukkit.getWorld(name);
        if (world == null) {
            chat().no(sender, "There is no loaded world called <name>.", Chat.arg("name", name));
            return;
        }
        OfflinePlayer toReturn = null;
        if (args.length >= 3) {
            toReturn = Bukkit.getPlayerExact(args[2]);
            if (toReturn == null) {
                chat().no(sender, "<name> is not online.", Chat.arg("name", args[2]));
                return;
            }
        }
        chat().tell(sender, "<gray>Regenerating <name> — the server will pause.", Chat.arg("name", name));
        OfflinePlayer finalToReturn = toReturn;
        regenerator.regenerate(world, ok -> {
            if (!ok) {
                chat().no(sender, "Something went wrong; the server log has it.");
                return;
            }
            chat().ok(sender, "<name> is new.", Chat.arg("name", name));
            if (finalToReturn instanceof Player player) {
                World fresh = Bukkit.getWorld(name);
                if (fresh != null) {
                    var target = fresh.getSpawnLocation();
                    Scheduling.region(plugin(), target, () -> player.teleport(target));
                }
            }
        });
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
            List<String> options = new java.util.ArrayList<>();
            if (sender.hasPermission(SWITCH)) {
                options.add("switch");
            }
            if (sender.hasPermission(REGEN)) {
                options.add("regen");
            }
            return options.stream().filter(word -> word.startsWith(typed)).toList();
        }
        if (args.length == 2) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getWorlds().stream().map(World::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("regen")) {
            String typed = args[2].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
        }
        return List.of();
    }
}
