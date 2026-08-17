package de.raindancer.core.ui.profile;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

import java.util.UUID;

/**
 * Makes a name in chat open {@link ProfileMenu} when clicked.
 *
 * <h2>Why the command name is fixed rather than configurable</h2>
 * Unlike {@code ChatButtons}' callback command — which a server owner might reasonably want to
 * rename — this is wired up once, in {@code ModuleBootstrap}, the same moment for every server that
 * runs any of these plugins at all. There is nothing to configure and nothing that could disagree
 * with itself, so it is a constant rather than a second thing every caller has to be told.
 *
 * @see ProfileCommand
 */
public final class ProfileLink {

    /** What {@code CoreCommands.profile(registrar)} registers this as, without its slash. */
    public static final String COMMAND = "rprofile";

    private ProfileLink() {
    }

    /**
     * Wraps an already-built name — coloured, prefixed, whatever it is — so clicking it opens that
     * player's profile. The text itself is untouched; only the click and hover are added.
     */
    public static Component of(Component name, UUID subject) {
        if (name == null || subject == null) {
            return name;
        }
        return name.clickEvent(ClickEvent.runCommand(COMMAND + " " + subject))
                .hoverEvent(HoverEvent.showText(Component.text("Click to view their profile")));
    }

    /** The same, for a plain player name with no styling of its own yet. */
    public static Component of(String plainName, UUID subject) {
        return of(Component.text(plainName == null ? "" : plainName), subject);
    }
}
