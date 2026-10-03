package de.raindancer.core.ui.text;

/**
 * A placeholder value that <em>is</em> markup, and is meant to be parsed as such.
 *
 * <p>The exception, deliberately spelled out. Every other value handed to a message — a String, a
 * number, a name — is inserted as the text it is, so a home called {@code <rainbow>} or an item renamed
 * {@code <click:run_command:'/op me'>} shows up as those characters and never becomes a colour or a
 * button. A value only becomes markup when it is wrapped in this, which makes it a decision somebody
 * made in code rather than something a player's typing can do.
 *
 * <pre>{@code
 * messages.send(player, "shop.bought", "item", Markup.of(definition.name()), "buyer", buyer.getName());
 * }</pre>
 *
 * <p>Wrap only text a server owner or a plugin wrote: a definition from a file, a configured format.
 * Never wrap anything a player typed, a player's name, or an item's display name.
 *
 * @param miniMessage the markup, parsed where it is inserted
 */
public record Markup(String miniMessage) {

    public Markup {
        miniMessage = miniMessage == null ? "" : miniMessage;
    }

    public static Markup of(String miniMessage) {
        return new Markup(miniMessage);
    }

    @Override
    public String toString() {
        return miniMessage;
    }
}
