package de.raindancer.core.platform.command;

import de.raindancer.core.ui.chat.ClickCommand;
import de.raindancer.core.data.settings.SettingsCommand;
import de.raindancer.core.world.warp.WarpCommand;
import de.raindancer.core.world.farm.FarmWorldCommand;
import io.papermc.paper.command.brigadier.Commands;

import java.util.List;

/**
 * Ready-made commands for the things Core knows about — none of which Core registers.
 *
 * <h2>Why Core registers nothing</h2>
 * Because a library that takes {@code /warp} for itself has decided something that is not its to
 * decide. A server may already have a warp plugin, may want the settings behind a different name,
 * or may want none of them. Core's job is to make writing those commands a line rather than a
 * weekend; owning the names is a different job, and one nobody asked it to do.
 *
 * <p>So the handlers live here as building blocks, and a plugin registers whichever it wants:
 *
 * <pre>{@code
 * public final class MyBootstrap implements PluginBootstrap {
 *     public void bootstrap(BootstrapContext context) {
 *         context.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
 *             CoreCommands.clickCallback(event.registrar());   // see below — buttons need this
 *             CoreCommands.settings(event.registrar(), "settings");
 *             CoreCommands.warps(event.registrar(), "warp");
 *         });
 *     }
 * }
 * }</pre>
 *
 * <h2>The one that is not really a command</h2>
 * {@link #clickCallback} is different in kind. A clickable thing in chat can only do one of three
 * things — open a URL, put text in the box, or run a command — so a button with a server-side
 * callback is a command by necessity, not by choice. Nobody types it and it takes an opaque token.
 * Without it registered somewhere, {@code buttons()} still produces readable text but nothing is
 * clickable, and Core says so once rather than leaving you wondering.
 *
 * <h2>Register these in a bootstrapper, not in onEnable</h2>
 * Paper fires the {@code COMMANDS} lifecycle event during the bootstrap phase. A handler registered
 * in {@code onEnable} is registered after that has already happened, so it never runs — with no
 * warning, no exception, and no line in the log. The command simply does not exist and
 * {@code dispatchCommand} answers false as though nobody had ever heard of it.
 *
 * <p>That is not a theoretical footnote. Core itself was written that way, and every chat button in
 * the library was dead on a real server for weeks: the callback registry worked perfectly and the
 * command it pointed at was not there. Nothing below the server line could have caught it, because
 * the machinery was right and only the registration was in the wrong place.
 */
public final class CoreCommands {

    private CoreCommands() {
    }

    /**
     * The callback command chat buttons need.
     *
     * <p>Not a command anybody types — it takes a token and runs whatever was registered against it.
     * Register it under a name nothing else uses and tell {@code buttons()} what you called it, or
     * take the default of {@code rcclick} and leave the button helper alone.
     *
     * @param name what to call it, without a slash
     */
    public static void clickCallback(Commands registrar, String name) {
        registrar.register(name, "Runs a button you clicked in chat.", new ClickCommand());
    }

    /** The same, as {@code rcclick} — which is what {@code buttons()} expects by default. */
    public static void clickCallback(Commands registrar) {
        clickCallback(registrar, "rcclick");
    }

    /** Reading and changing every plugin's settings, from chat. */
    public static void settings(Commands registrar, String name, String... aliases) {
        registrar.register(name, "Everything every plugin on this server can be told to do.",
                List.of(aliases), new SettingsCommand());
    }

    /** Going to a warp, and managing the list of them. */
    public static void warps(Commands registrar, String name, String... aliases) {
        registrar.register(name, "Go to a named place, or manage the list of them.",
                List.of(aliases), new WarpCommand());
    }

    /** Going to a farm world, and regenerating one. */
    public static void farmWorlds(Commands registrar, String name, String... aliases) {
        registrar.register(name, "Go to a farm world, or run one.", List.of(aliases),
                new FarmWorldCommand());
    }
}
