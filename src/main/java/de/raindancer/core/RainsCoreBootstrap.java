package de.raindancer.core;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.jetbrains.annotations.NotNull;

/**
 * Registers this plugin's commands, before it is enabled.
 *
 * <h2>Why a bootstrapper and not {@code onEnable}</h2>
 * Because {@code onEnable} is too late, and nothing says so. Paper fires
 * {@link LifecycleEvents#COMMANDS} during the bootstrap phase; a handler registered in
 * {@code onEnable} is registered after that has already happened, so it never runs — with no
 * warning, no exception and no line in the log. The command simply does not exist, and
 * {@code dispatchCommand} answers false as though somebody had typed a word nobody has heard of.
 *
 * <p>That is exactly how this was found: a live-server check tried {@code /settings list}, got
 * false, and the diagnostic line put inside the handler never printed. Both commands had been
 * written that way, so <em>every chat button in the library had been dead on a real server</em> —
 * the callback registry worked perfectly and the command it pointed at was not there. Nothing below
 * the server line could have caught it, because the machinery was right and only the registration
 * was in the wrong place.
 *
 * <h2>Why the commands are given a supplier rather than the objects</h2>
 * A bootstrapper runs before {@code onEnable}, so the registry, the chat and the navigation do not
 * exist yet. Each command therefore takes a way to <em>find</em> the running plugin and asks when it
 * is actually run, by which time everything is up.
 */
public final class RainsCoreBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(@NotNull BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register("rcclick",
                    "Runs a button you clicked in chat.",
                    new de.raindancer.core.chat.ClickCommand());
            event.registrar().register("settings",
                    "Everything every plugin on this server can be told to do.",
                    java.util.List.of("rcsettings"),
                    new de.raindancer.core.settings.SettingsCommand());
        });
    }
}
