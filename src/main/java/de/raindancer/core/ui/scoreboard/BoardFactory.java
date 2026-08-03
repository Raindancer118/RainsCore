package de.raindancer.core.ui.scoreboard;

import java.util.UUID;

/**
 * Makes a {@link Board} for a player — the one place the packet layer is reached.
 *
 * <p>May throw, and does on any server whose internals the copied-in FastBoard does not recognise.
 * {@link Scoreboards} treats that as "this server has no sidebars" and stops asking.
 */
@FunctionalInterface
public interface BoardFactory {

    Board create(UUID player);
}
