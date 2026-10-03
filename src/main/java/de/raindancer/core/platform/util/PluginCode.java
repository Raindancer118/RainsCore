package de.raindancer.core.platform.util;

import org.bukkit.plugin.Plugin;

/**
 * Whose code an object is, by the class loader that defined it.
 *
 * <p>For the registries a plugin hands callbacks to — a combat rule, a land provider, a profile page.
 * A plugin that is disabled (a reload, a module stopping) without taking them back leaves code from a
 * dead class loader answering questions: protection asked of a provider whose database is closed, a
 * second copy of every rule after it starts again. Core drops them itself when a plugin is disabled.
 *
 * <p>A module hosted inside a plugin has its own loader below the plugin's, so it counts as the
 * plugin's too.
 */
public final class PluginCode {

    private PluginCode() {
    }

    /** Whether {@code code} was defined by {@code plugin}, or by something it hosts. */
    public static boolean isFrom(Object code, Plugin plugin) {
        return plugin != null && isFrom(code, plugin.getClass().getClassLoader());
    }

    /** The same, by loader. */
    public static boolean isFrom(Object code, ClassLoader loader) {
        return code != null && loadedBy(code.getClass().getClassLoader(), loader);
    }

    /** Whether {@code type} was defined by {@code loader} or something below it. */
    public static boolean loaded(Class<?> type, ClassLoader loader) {
        return type != null && loadedBy(type.getClassLoader(), loader);
    }

    /** Whether {@code candidate} is {@code loader} or a loader whose parents lead to it. */
    static boolean loadedBy(ClassLoader candidate, ClassLoader loader) {
        if (loader == null) {
            return false;
        }
        for (ClassLoader at = candidate; at != null; at = at.getParent()) {
            if (at == loader) {
                return true;
            }
        }
        return false;
    }
}
