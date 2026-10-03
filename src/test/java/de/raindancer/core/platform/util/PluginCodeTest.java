package de.raindancer.core.platform.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

import static org.assertj.core.api.Assertions.assertThat;

/** Whose code a callback is — the question a plugin being disabled has to have answered. */
class PluginCodeTest {

    @Test
    @DisplayName("code loaded by a plugin's loader, or by a loader below it, is that plugin's")
    void belongsByLoader() {
        ClassLoader plugin = getClass().getClassLoader();
        ClassLoader module = new URLClassLoader(new URL[0], plugin);
        ClassLoader stranger = new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader());

        assertThat(PluginCode.loadedBy(plugin, plugin)).isTrue();
        assertThat(PluginCode.loadedBy(module, plugin)).as("a module hosted inside the plugin").isTrue();
        assertThat(PluginCode.loadedBy(stranger, plugin)).isFalse();
        assertThat(PluginCode.loadedBy(null, plugin)).isFalse();
    }

    @Test
    @DisplayName("a lambda belongs to whoever wrote it")
    void lambdas() {
        Runnable mine = () -> { };

        assertThat(PluginCode.isFrom(mine, getClass().getClassLoader())).isTrue();
        assertThat(PluginCode.isFrom(null, getClass().getClassLoader())).isFalse();
    }
}
