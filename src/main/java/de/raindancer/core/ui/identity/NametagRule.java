package de.raindancer.core.ui.identity;

import net.kyori.adventure.text.Component;

import java.util.Optional;

/** When a styled nametag floats over somebody, and what it says. No server in here. */
public final class NametagRule {

    /**
     * @param inForeignTeam in a scoreboard team some other plugin made — their vanilla name stays as that
     *                      plugin wants it, and a second name above it would be worse than either
     */
    public boolean shows(boolean dead, boolean spectating, boolean invisible, boolean vanished,
                         boolean inForeignTeam) {
        return !dead && !spectating && !invisible && !vanished && !inForeignTeam;
    }

    /** The name, and the subtitle under it when there is one. */
    public Component text(Component name, Optional<Component> subtitle) {
        return subtitle.map(line -> name.append(Component.newline()).append(line)).orElse(name);
    }
}
