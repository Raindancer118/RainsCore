package de.raindancer.core.ui.actionbar;

import net.kyori.adventure.text.Component;

import java.util.UUID;

/**
 * The last step: putting a finished line above one player's hotbar.
 *
 * <h2>Why this is an interface</h2>
 * So {@link ActionBars} can be tested. Every rule worth getting right here — who wins, when a
 * message expires, what falls back when it does, how often anything is actually sent — is arithmetic
 * over a map, and none of it needs a server. Behind this seam sits one line calling Paper; in a test
 * sits something that writes down what it was asked to send.
 */
@FunctionalInterface
public interface ActionBarSink {

    /**
     * Shows {@code message} to {@code player}, or clears their bar when it is
     * {@link Component#empty()}.
     *
     * <p>May throw — a player can log out between the decision and the send — and
     * {@link ActionBars} expects that.
     */
    void send(UUID player, Component message);
}
