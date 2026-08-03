package de.raindancer.core.data.settings;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.chat.Chat;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /settings} — the same settings as the menu, for somebody at a console or who prefers typing.
 *
 * <h2>Why both</h2>
 * A menu is better for finding something you cannot name; a command is better for changing something
 * you can, and it is the only one of the two that works from the console or a script. Neither is a
 * lesser copy of the other because both go through {@link SettingsRegistry}, so there is exactly one
 * place a setting is validated and written.
 */
public final class SettingsCommand implements BasicCommand {

    private static final String PERMISSION = "rainscore.settings";

    /**
     * Nothing is held, because there is nothing to hold yet.
     *
     * <p>This is registered from the bootstrapper, which runs before {@code onEnable} — so the
     * registry, the chat and the navigation do not exist when it is constructed. It asks for them
     * when it is actually run. See {@code RainsCoreBootstrap} for why registration cannot wait.
     */
    public SettingsCommand() {
    }

    /**
     * The plugin to schedule against.
     *
     * <p>Core itself, because these settings are Core's and the work is a file write rather than
     * anything belonging to whoever typed the command.
     */
    private static org.bukkit.plugin.Plugin corePlugin() {
        return org.bukkit.Bukkit.getPluginManager().getPlugin("RainsCore");
    }

    private SettingsNavigation navigation() {
        return de.raindancer.core.RainsCore.get().settingsNavigation();
    }

    private Chat chat() {
        return de.raindancer.core.RainsCore.get().chatFor("Core");
    }

    private Brand brand() {
        return chat().brand();
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission(PERMISSION);
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!de.raindancer.core.RainsCore.isAvailable()) {
            return;
        }
        if (args.length == 0) {
            if (sender instanceof Player player) {
                SettingsMenu.root(player, brand(), chat(), navigation()).open();
            } else {
                list(sender);
            }
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender);
            case "get" -> get(sender, args);
            case "set" -> set(sender, args);
            case "reset" -> reset(sender, args);
            default -> usage(sender);
        }
    }

    private void list(CommandSender sender) {
        List<String> keys = navigation().registry().keys();
        chat().tell(sender, "<gray><count> settings:", Chat.arg("count", keys.size()));
        for (String key : keys) {
            chat().row(sender, "<dark_gray>  <white><key> <dark_gray>= <gray><value>"
                    .replace("<key>", key)
                    .replace("<value>", navigation().registry().display(key)));
        }
        var clashes = navigation().registry().clashes();
        if (!clashes.isEmpty()) {
            chat().warn(sender, "<count> setting name(s) are used by more than one plugin; "
                    + "say plugin:name to be sure which you mean.",
                    Chat.arg("count", clashes.size()));
        }
    }

    private void get(CommandSender sender, String[] args) {
        if (args.length < 2) {
            usage(sender);
            return;
        }
        navigation().registry().setting(args[1]).ifPresentOrElse(setting -> {
            chat().tell(sender, "<white><name></white> is <white><value></white>.",
                    Chat.arg("name", setting.title()),
                    Chat.arg("value", navigation().registry().display(args[1])));
            for (String line : navigation().describe(setting)) {
                if (!line.isBlank()) {
                    chat().row(sender, "<dark_gray>  " + line);
                }
            }
        }, () -> unknown(sender, args[1]));
    }

    private void set(CommandSender sender, String[] args) {
        if (args.length < 3) {
            usage(sender);
            return;
        }
        // Everything after the key, so a value with spaces in it works.
        String value = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        if (navigation().registry().setting(args[1]).isEmpty()) {
            unknown(sender, args[1]);
            return;
        }
        if (navigation().registry().set(args[1], value)) {
            // Off the thread the command arrived on: this writes a YAML file for every plugin that
            // has settings, and doing that on a region thread stalls the world for the disk.
            Scheduling.async(corePlugin(), () -> navigation().registry().saveAll());
            chat().ok(sender, "<name> is now <value>.",
                    Chat.arg("name", args[1]),
                    Chat.arg("value", navigation().registry().display(args[1])));
            return;
        }
        chat().no(sender, "<value> is not something <name> can be.",
                Chat.arg("value", value), Chat.arg("name", args[1]));
        navigation().registry().setting(args[1]).ifPresent(setting -> {
            if (setting.min() != null) {
                chat().row(sender, "<dark_gray>  it goes from " + setting.min()
                        + " to " + setting.max());
            } else if (!setting.choices().isEmpty()) {
                chat().row(sender, "<dark_gray>  one of: " + String.join(", ", setting.choices()));
            }
        });
    }

    private void reset(CommandSender sender, String[] args) {
        if (args.length < 2) {
            usage(sender);
            return;
        }
        if (navigation().registry().setting(args[1]).isEmpty()) {
            unknown(sender, args[1]);
            return;
        }
        navigation().registry().reset(args[1]);
        Scheduling.async(corePlugin(), () -> navigation().registry().saveAll());
        chat().ok(sender, "<name> is back to <value>.",
                Chat.arg("name", args[1]),
                Chat.arg("value", navigation().registry().display(args[1])));
    }

    private void unknown(CommandSender sender, String key) {
        chat().no(sender, "Nothing on this server is called <name>.", Chat.arg("name", key));
    }

    private void usage(CommandSender sender) {
        chat().tell(sender, "<gray>/settings <dark_gray>— the menu");
        chat().row(sender, "<dark_gray>  /settings list");
        chat().row(sender, "<dark_gray>  /settings get <name>");
        chat().row(sender, "<dark_gray>  /settings set <name> <value>");
        chat().row(sender, "<dark_gray>  /settings reset <name>");
    }

    /**
     * Completions.
     *
     * <p>The third argument completes to what the setting can actually be — the choices of a choice,
     * true and false for a flag, the bounds of a number. Completing a value is the difference
     * between a command somebody uses and one they look up first.
     */
    @Override
    public java.util.Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (!de.raindancer.core.RainsCore.isAvailable()) {
            return List.of();
        }
        if (args.length <= 1) {
            return List.of("list", "get", "set", "reset").stream()
                    .filter(word -> args.length == 0 || word.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            return navigation().registry().keys().stream()
                    .filter(key -> key.toLowerCase(Locale.ROOT).startsWith(typed))
                    .limit(50)
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            return navigation().registry().setting(args[1])
                    .map(SettingsCommand::valuesFor)
                    .orElse(List.of());
        }
        return List.of();
    }

    private static List<String> valuesFor(Setting<?> setting) {
        if (!setting.choices().isEmpty()) {
            return setting.choices();
        }
        if (setting.type() == Boolean.class) {
            return List.of("true", "false");
        }
        if (setting.min() != null) {
            List<String> bounds = new ArrayList<>();
            bounds.add(String.valueOf(setting.min()));
            bounds.add(String.valueOf(setting.max()));
            return bounds;
        }
        return List.of();
    }
}
