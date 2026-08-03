package de.raindancer.core.ui.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Makes clickable things for chat.
 *
 * <p>One per server, held by {@code RainsCore} and handed to every plugin. It knows the two things a
 * button needs and a plugin should not have to: where callbacks are registered
 * ({@link ClickActions}) and which command runs them.
 *
 * <h2>Somebody has to register that command</h2>
 * A clickable thing in chat can only open a URL, put text in the box, or run a command — so a button
 * with a server-side callback is a command by necessity. Core does not register one: it registers no
 * commands at all, on purpose, because taking names on a server is not a library's decision.
 *
 * <p>So a plugin registers
 * {@link de.raindancer.core.platform.command.CoreCommands#clickCallback(io.papermc.paper.command.brigadier.Commands)}
 * in its bootstrapper and calls {@link #callbackCommand(String)} with the name it used. Until one
 * does, buttons still render — with their label and their tooltip — but nothing is clickable, and
 * this says so once with the exact fix rather than leaving a server owner with buttons that do
 * nothing when clicked.
 */
public final class ChatButtons {

    /** What goes between two buttons in a row. A space, so they do not read as one word. */
    private static final Component GAP = Component.text(" ");

    private static final de.raindancer.core.platform.log.LogChannel log =
            de.raindancer.core.platform.log.Log.of("chat");

    private final ClickActions actions;
    private volatile String command;
    /** So the warning about there being no command is said once, not once per button. */
    private final java.util.concurrent.atomic.AtomicBoolean warned =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * @param command the command that runs a callback, without its slash — one for the whole server,
     *                or empty until a plugin has registered one
     */
    public ChatButtons(ClickActions actions, String command) {
        this.actions = actions;
        this.command = command == null ? "" : command.trim();
    }

    /**
     * Tells this which command runs its callbacks.
     *
     * <p>Called by whichever plugin registered it, with the name it used — namespaced is safest, as
     * {@code myplugin:rcclick} always resolves to that plugin's command whatever else is installed.
     * A button pointing at a name somebody else owns is a button that does something nobody meant.
     */
    public void callbackCommand(String command) {
        this.command = command == null ? "" : command.trim().replaceFirst("^/", "");
    }

    /** Whether callbacks can actually run — false until a plugin has registered the command. */
    public boolean isClickable() {
        return !command.isEmpty();
    }

    /** Says once, with the fix, that buttons are not wired up. */
    void warnIfNotClickable() {
        if (!isClickable() && warned.compareAndSet(false, true)) {
            log.warn("Chat buttons are being drawn but nothing has registered the command that "
                    + "runs them, so they are not clickable. Register CoreCommands.clickCallback in "
                    + "a plugin bootstrapper and call buttons().callbackCommand(\"<name>\").");
        }
    }

    /** A button whose label is MiniMessage. */
    public ChatButton label(String miniMessage) {
        return new ChatButton(this, miniMessage);
    }

    /** Several buttons on one line, spaced apart. Nulls are skipped, so one can be conditional. */
    public Component row(ChatButton... buttons) {
        if (buttons == null || buttons.length == 0) {
            return Component.empty();
        }
        Component built = Component.empty();
        boolean first = true;
        for (ChatButton button : buttons) {
            if (button == null) {
                continue;
            }
            if (!first) {
                built = built.append(GAP);
            }
            built = built.append(button.render());
            first = false;
        }
        return built;
    }

    /**
     * A yes-or-no question put to one player: {@code [Accept] [Deny]}.
     *
     * <p>This is the shape the whole button mechanism was built for — a claim fee, and a town
     * council being asked to approve a new claim inside their town. Answering either way takes the
     * other button away, so a player cannot accept and then also deny; the second click says the
     * offer is gone rather than quietly doing the opposite of what they already chose.
     *
     * @param asked    the only player who may answer
     * @param validFor how long the offer stands
     */
    public Component ask(UUID asked, Duration validFor, Consumer<UUID> onYes, Consumer<UUID> onNo) {
        List<String> issued = new ArrayList<>(2);
        Consumer<UUID> yes = closing(issued, onYes);
        Consumer<UUID> no = closing(issued, onNo);

        ChatButton accept = label("<green>[Accept]</green>")
                .tooltip("<gray>Say yes to this")
                .forOnly(asked).expiringIn(validFor).does(yes);
        ChatButton deny = label("<red>[Deny]</red>")
                .tooltip("<gray>Say no to this")
                .forOnly(asked).expiringIn(validFor).does(no);

        Component question = row(accept, deny);
        // The tokens are only known once the buttons have been rendered, which is why the closing
        // wrapper reads the list rather than capturing the tokens.
        issued.addAll(tokensIn(question));
        return question;
    }

    /** Wraps an answer so that taking it revokes every button of the same question. */
    private Consumer<UUID> closing(List<String> issued, Consumer<UUID> answer) {
        return clicker -> {
            for (String token : issued) {
                actions.revoke(token);
            }
            if (answer != null) {
                answer.accept(clicker);
            }
        };
    }

    private List<String> tokensIn(Component question) {
        List<String> tokens = new ArrayList<>();
        for (Component child : question.children()) {
            String command = commandOf(child.style().clickEvent());
            if (command != null) {
                tokens.add(command.substring(command.lastIndexOf(' ') + 1));
            }
        }
        return tokens;
    }

    /**
     * The command text behind a click, or null when the click is not one that carries text.
     *
     * <p>Adventure 5 made {@code ClickEvent} generic over a payload — {@code changePage} carries an
     * int and {@code custom} carries NBT — so the string is no longer simply {@code value()}.
     */
    public static String commandOf(ClickEvent<?> click) {
        if (click == null) {
            return null;
        }
        return click.payload() instanceof ClickEvent.Payload.Text text ? text.value() : null;
    }

    ClickActions actions() {
        return actions;
    }

    String command() {
        return command;
    }
}
