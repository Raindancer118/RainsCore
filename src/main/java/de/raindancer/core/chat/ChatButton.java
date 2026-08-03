package de.raindancer.core.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One clickable thing in a chat line.
 *
 * <p>Built through {@link ChatButtons}, because a callback button needs somewhere to register its
 * action. A builder rather than a record: most buttons set two of the eight things, and eight
 * constructor arguments in the order somebody happened to write them is how a call site ends up
 * with the tooltip in the label.
 *
 * <p>The builder is mutable and is meant to be used and thrown away. {@link #render()} may be called
 * more than once and each call is a <em>separate</em> button — see the note there, because it is the
 * one surprising thing here and it is deliberate.
 */
public final class ChatButton {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final ChatButtons owner;
    private final String label;

    private String tooltip;
    private ClickEvent click;
    private Consumer<UUID> action;
    private UUID onlyFor;
    private Duration lifetime;
    private boolean oneShot = true;

    ChatButton(ChatButtons owner, String label) {
        this.owner = owner;
        this.label = label == null ? "" : label;
    }

    /** What hovering says. MiniMessage, like the label. */
    public ChatButton tooltip(String miniMessage) {
        this.tooltip = miniMessage;
        return this;
    }

    /** Types a command as the player, as though they had entered it. */
    public ChatButton runs(String command) {
        this.click = ClickEvent.runCommand(command);
        return this;
    }

    /** Puts text in the chat box without sending it — for a command the player should finish. */
    public ChatButton suggests(String text) {
        this.click = ClickEvent.suggestCommand(text);
        return this;
    }

    public ChatButton opens(String url) {
        this.click = ClickEvent.openUrl(url);
        return this;
    }

    public ChatButton copies(String text) {
        this.click = ClickEvent.copyToClipboard(text);
        return this;
    }

    /**
     * The only player allowed to click this.
     *
     * <p>Almost always wanted for a callback: without it the button is public, and anyone who can
     * read the token out of somebody else's screenshot can use it.
     */
    public ChatButton forOnly(UUID player) {
        this.onlyFor = player;
        return this;
    }

    /** How long it stays good. Left unsaid, {@link ClickActions#DEFAULT_LIFETIME}. */
    public ChatButton expiringIn(Duration lifetime) {
        this.lifetime = lifetime;
        return this;
    }

    /**
     * Lets it be clicked more than once — for a button that pages a list.
     *
     * <p>The default is one-shot, because most buttons answer a question and a client sending two
     * clicks for one press must not answer it twice.
     */
    public ChatButton repeatable() {
        this.oneShot = false;
        return this;
    }

    /**
     * Runs server-side code, given whoever clicked. Registered with {@link ClickActions}.
     *
     * <h2>Why not {@code ClickEvent.callback}</h2>
     * Adventure has had a native click callback since 4.14, and it does three of the four things
     * needed here: it mints the token, it expires ({@code Options.lifetime}) and it can be one-shot
     * ({@code Options.uses}). It was seriously considered and rejected for what it does not do:
     *
     * <ul>
     *   <li><b>It is not bound to a player.</b> The callback takes whoever clicks. The offer that
     *       started all this — a town council being asked to approve a claim — has to refuse a
     *       click from anybody but the council member it was sent to.</li>
     *   <li><b>It cannot be revoked.</b> Answering "yes" has to take the {@code [Deny]} button away.
     *       An Adventure callback lives until its uses or its lifetime run out.</li>
     *   <li><b>It cannot say why nothing happened.</b> "That is not your button", "you already
     *       answered", and "that offer has expired" are three different things a player needs to
     *       hear; a spent Adventure callback simply does nothing, which reads as a broken button
     *       and gets clicked four more times.</li>
     * </ul>
     */
    public ChatButton does(Consumer<UUID> action) {
        this.action = action;
        return this;
    }

    /**
     * The finished component.
     *
     * <h2>Why each call is its own button</h2>
     * A callback button registers a fresh token every time it is rendered. That looks wasteful until
     * you see what it is for: the same offer is sent to every online council member, and if they
     * shared one token then whichever of them clicked first would consume the button in everybody
     * else's chat — and, worse, a token bound to one of them would refuse all the others. One render
     * per recipient is one button per recipient.
     */
    public Component render() {
        Component rendered = MINI.deserialize(label);
        ClickEvent effective = click;
        if (action != null && owner.isClickable()) {
            String token = owner.actions().register(onlyFor, lifetime, oneShot, action);
            if (token != null) {
                effective = ClickEvent.runCommand("/" + owner.command() + " " + token);
            }
        } else if (action != null) {
            // Rendered anyway, without the click. A label somebody can read beats a button that
            // silently does nothing, and the warning below names the one line that fixes it.
            owner.warnIfNotClickable();
        }
        if (effective != null) {
            rendered = rendered.clickEvent(effective);
        }
        if (tooltip != null && !tooltip.isBlank()) {
            rendered = rendered.hoverEvent(HoverEvent.showText(MINI.deserialize(tooltip)));
        }
        return rendered;
    }
}
