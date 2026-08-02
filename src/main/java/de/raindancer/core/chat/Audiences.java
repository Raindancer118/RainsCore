package de.raindancer.core.chat;

import net.kyori.adventure.audience.Audience;

import java.util.Collection;

/**
 * Where "everybody" and "the console" come from.
 *
 * <h2>Why this is an interface</h2>
 * Because {@link Chat} was untestable without it. Its first version called
 * {@code Bukkit.getOnlinePlayers()} and {@code Bukkit.getConsoleSender()} directly, which meant the
 * two methods most likely to be wrong — who a broadcast actually reaches, and whether the console
 * gets the prefix — could not be covered by a single test. Writing the tests is what found that;
 * the seam is the fix.
 *
 * <p>The real implementation is three lines against Paper. A test implements it with a list.
 */
public interface Audiences {

    /** Everyone who can currently be spoken to. */
    Collection<? extends Audience> everyone();

    /** The server console. */
    Audience console();
}
