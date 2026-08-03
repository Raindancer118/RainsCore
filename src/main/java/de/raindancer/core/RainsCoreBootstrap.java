package de.raindancer.core;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import org.jetbrains.annotations.NotNull;

/**
 * Exists so that other plugins can reach Core's classes before the server starts. Registers nothing.
 *
 * <h2>Why an empty bootstrapper is not a mistake</h2>
 * Because Paper's bootstrap phase has its own registry, and a plugin that declares no bootstrapper
 * is not in it. A dependent plugin that wants to register commands — which it must do in a
 * bootstrapper, since {@code COMMANDS} fires before {@code onEnable} — cannot then declare a
 * {@code dependencies.bootstrap} entry on Core: the server refuses it with "Unknown/missing
 * dependency plugins: [RainsCore]" and the dependent does not load at all.
 *
 * <p>So the foundation Core offers for writing commands ({@link de.raindancer.core.platform.command.CoreCommands})
 * is unusable unless this class is here. It was found the only way it could be: by deleting it,
 * declaring the dependency properly in the test plugin, and watching a real server refuse to load it.
 *
 * <h2>What it deliberately does not do</h2>
 * Register a single command. Not {@code /warp}, not {@code /settings}, not the callback command chat
 * buttons need — taking a name on somebody's server is not a library's decision. Every one of those
 * is a handler a plugin registers itself, by name, in its own bootstrapper.
 */
public final class RainsCoreBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(@NotNull BootstrapContext context) {
        // Nothing. See the class comment: being here at all is the entire job.
    }
}
