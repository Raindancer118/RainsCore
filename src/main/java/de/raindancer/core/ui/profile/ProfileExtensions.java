package de.raindancer.core.ui.profile;

import de.raindancer.core.platform.util.PluginCode;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every {@link ProfileExtension} currently registered.
 *
 * <p>Static and process-wide rather than owned by one plugin: modules that register here are
 * hosted by whichever plugin loaded them, and there is exactly one of these per server regardless
 * of how many plugins are involved — {@code join-classpath: true} means one loaded copy of this
 * class for every module that depends on Core, the same reasoning {@code ChatButtons} and
 * {@code ClaimMenuExtensions} already rely on.
 *
 * <p>A module registers when it enables and unregisters when it disables. Left registered past
 * that point would mean a profile page still asking a module that has already unwound its own
 * state for an answer.
 */
public final class ProfileExtensions {

    private static final List<ProfileExtension> extensions = new CopyOnWriteArrayList<>();

    private ProfileExtensions() {
    }

    public static void register(ProfileExtension extension) {
        if (extension != null) {
            extensions.add(extension);
        }
    }

    public static void unregister(ProfileExtension extension) {
        extensions.remove(extension);
    }

    /** Every contributor currently registered, in the order they registered. */
    /**
     * Drops whatever {@code loader}'s code registered here — for a plugin disabled without taking its
     * own back. See {@link PluginCode}.
     *
     * @return how many were dropped
     */
    public static int forgetFrom(ClassLoader loader) {
        int before = extensions.size();
        extensions.removeIf(extension -> PluginCode.isFrom(extension, loader));
        return before - extensions.size();
    }

    public static List<ProfileExtension> all() {
        return List.copyOf(extensions);
    }
}
