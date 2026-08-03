package de.raindancer.core.ui.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Buttons in chat: what the component actually says to the client.
 *
 * <p>Every assertion here is about a component tree, so none of it needs a server. What is being
 * pinned down is the part that is easy to get subtly wrong and impossible to notice by playing —
 * a button with no hover, a callback whose token leaked into the visible text, a row that glues two
 * buttons together so they read as one.
 */
class ChatButtonsTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private AtomicLong clock;
    private ClickActions actions;
    private ChatButtons buttons;
    private List<UUID> accepted;
    private List<UUID> denied;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(1_000L);
        actions = new ClickActions(clock::get);
        buttons = new ChatButtons(actions, "rcore:click");
        accepted = new ArrayList<>();
        denied = new ArrayList<>();
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static ClickEvent<?> clickOf(Component component) {
        return component.style().clickEvent();
    }

    /** Adventure 5 made ClickEvent generic over a payload; the command is no longer value(). */
    private static String commandOf(ClickEvent<?> click) {
        return ChatButtons.commandOf(click);
    }

    /** The token a callback button runs, dug out of its click event. */
    private static String tokenOf(Component button) {
        ClickEvent click = clickOf(button);
        assertThat(click).isNotNull();
        assertThat(click.action()).isEqualTo(ClickEvent.Action.RUN_COMMAND);
        String command = commandOf(click);
        return command.substring(command.lastIndexOf(' ') + 1);
    }

    // ------------------------------------------------------------------ the simple kinds

    @Nested
    @DisplayName("what a button can do")
    class Kinds {

        @Test
        @DisplayName("run a command")
        void runsACommand() {
            Component button = buttons.label("<green>[Spawn]").runs("/spawn").render();
            assertThat(clickOf(button).action()).isEqualTo(ClickEvent.Action.RUN_COMMAND);
            assertThat(commandOf(clickOf(button))).isEqualTo("/spawn");
            assertThat(plain(button)).isEqualTo("[Spawn]");
        }

        @Test
        @DisplayName("put text in the player's chat box without sending it")
        void suggestsACommand() {
            Component button = buttons.label("[Reply]").suggests("/msg Bentex_OG ").render();
            assertThat(clickOf(button).action()).isEqualTo(ClickEvent.Action.SUGGEST_COMMAND);
            assertThat(commandOf(clickOf(button))).isEqualTo("/msg Bentex_OG ");
        }

        @Test
        @DisplayName("open a link")
        void opensAUrl() {
            Component button = buttons.label("[Wiki]").opens("https://example.invalid/wiki").render();
            assertThat(clickOf(button).action()).isEqualTo(ClickEvent.Action.OPEN_URL);
        }

        @Test
        @DisplayName("copy something to the clipboard")
        void copiesText() {
            Component button = buttons.label("[Copy]").copies("claim-4821").render();
            assertThat(clickOf(button).action()).isEqualTo(ClickEvent.Action.COPY_TO_CLIPBOARD);
            assertThat(commandOf(clickOf(button))).isEqualTo("claim-4821");
        }

        @Test
        @DisplayName("a label is MiniMessage, so a button can be coloured without a second argument")
        void labelIsMiniMessage() {
            Component button = buttons.label("<green><bold>[Accept]</bold></green>").runs("/x")
                    .render();
            assertThat(plain(button)).isEqualTo("[Accept]");
            assertThat(button.color()).isNotNull();
        }

        @Test
        @DisplayName("a button with nothing to do is still a component, just not clickable")
        void aButtonNeedNotDoAnything() {
            Component button = buttons.label("[Nothing]").render();
            assertThat(clickOf(button)).isNull();
            assertThat(plain(button)).isEqualTo("[Nothing]");
        }
    }

    // ------------------------------------------------------------------ the tooltip

    @Nested
    @DisplayName("the tooltip")
    class Tooltip {

        @Test
        @DisplayName("is shown on hover when one is given")
        void isCarried() {
            Component button = buttons.label("[Accept]").tooltip("<gray>Approve this claim")
                    .runs("/x").render();
            HoverEvent<?> hover = button.style().hoverEvent();
            assertThat(hover).isNotNull();
            assertThat(plain((Component) hover.value())).isEqualTo("Approve this claim");
        }

        @Test
        @DisplayName("is absent rather than empty when none is given")
        void isOptional() {
            assertThat(buttons.label("[Accept]").runs("/x").render().style().hoverEvent()).isNull();
        }
    }

    // ------------------------------------------------------------------ callbacks

    @Nested
    @DisplayName("a callback button")
    class Callbacks {

        @Test
        @DisplayName("runs the registered action when its owner clicks it")
        void runsTheAction() {
            Component button = buttons.label("[Accept]")
                    .forOnly(ALICE)
                    .does(accepted::add)
                    .render();

            assertThat(actions.run(ALICE, tokenOf(button))).isEqualTo(ClickResult.RAN);
            assertThat(accepted).containsExactly(ALICE);
        }

        @Test
        @DisplayName("points at the one shared command, not at a command per feature")
        void usesTheSharedCommand() {
            Component button = buttons.label("[Accept]").forOnly(ALICE).does(accepted::add).render();
            assertThat(commandOf(clickOf(button))).startsWith("/rcore:click ");
        }

        @Test
        @DisplayName("the token is in the command, never in what the player can read")
        void doesNotLeakTheToken() {
            Component button = buttons.label("[Accept]").forOnly(ALICE).does(accepted::add).render();
            String token = tokenOf(button);
            assertThat(plain(button))
                    .as("a token visible in chat is a token another player can retype")
                    .isEqualTo("[Accept]")
                    .doesNotContain(token);
        }

        @Test
        @DisplayName("is bound to its owner, so nobody else's click does anything")
        void isBoundToItsOwner() {
            Component button = buttons.label("[Accept]").forOnly(ALICE).does(accepted::add).render();
            assertThat(actions.run(BOB, tokenOf(button))).isEqualTo(ClickResult.NOT_YOURS);
            assertThat(accepted).isEmpty();
        }

        @Test
        @DisplayName("is one-shot by default — double-clicking [Accept] accepts once")
        void isOneShotByDefault() {
            Component button = buttons.label("[Accept]").forOnly(ALICE).does(accepted::add).render();
            String token = tokenOf(button);
            actions.run(ALICE, token);
            assertThat(actions.run(ALICE, token)).isEqualTo(ClickResult.SPENT);
            assertThat(accepted).hasSize(1);
        }

        @Test
        @DisplayName("can be made repeatable on purpose, for a button that pages a list")
        void canBeRepeatable() {
            Component button = buttons.label("[Next]").forOnly(ALICE).repeatable()
                    .does(accepted::add).render();
            String token = tokenOf(button);
            actions.run(ALICE, token);
            actions.run(ALICE, token);
            assertThat(accepted).hasSize(2);
        }

        @Test
        @DisplayName("expires when it is told to")
        void honoursItsLifetime() {
            Component button = buttons.label("[Accept]").forOnly(ALICE)
                    .expiringIn(Duration.ofMinutes(2)).does(accepted::add).render();
            clock.addAndGet(Duration.ofMinutes(3).toMillis());
            assertThat(actions.run(ALICE, tokenOf(button))).isEqualTo(ClickResult.EXPIRED);
        }

        @Test
        @DisplayName("rendering twice makes two buttons, not one clicked twice")
        void eachRenderIsItsOwnButton() {
            ChatButton offer = buttons.label("[Accept]").forOnly(ALICE).does(accepted::add);
            String first = tokenOf(offer.render());
            String second = tokenOf(offer.render());
            assertThat(first)
                    .as("two council members must not share one [Accept]")
                    .isNotEqualTo(second);
        }
    }

    // ------------------------------------------------------------------ rows

    @Nested
    @DisplayName("a row of buttons")
    class Rows {

        @Test
        @DisplayName("puts a space between them so they do not read as one word")
        void separatesThem() {
            Component row = buttons.row(
                    buttons.label("[Accept]").runs("/yes"),
                    buttons.label("[Deny]").runs("/no"));
            assertThat(plain(row)).isEqualTo("[Accept] [Deny]");
        }

        @Test
        @DisplayName("keeps each button's own click event")
        void keepsEachClick() {
            Component row = buttons.row(
                    buttons.label("[Accept]").runs("/yes"),
                    buttons.label("[Deny]").runs("/no"));
            List<String> commands = new ArrayList<>();
            for (Component child : row.children()) {
                String command = commandOf(child.style().clickEvent());
                if (command != null) {
                    commands.add(command);
                }
            }
            assertThat(commands).containsExactly("/yes", "/no");
        }

        @Test
        @DisplayName("an empty row is empty rather than a stray separator")
        void handlesNothing() {
            assertThat(plain(buttons.row())).isEmpty();
        }

        @Test
        @DisplayName("a null button in the row is skipped, so a conditional button can be omitted")
        void skipsNulls() {
            Component row = buttons.row(
                    buttons.label("[Accept]").runs("/yes"),
                    null,
                    buttons.label("[Deny]").runs("/no"));
            assertThat(plain(row)).isEqualTo("[Accept] [Deny]");
        }
    }

    // ------------------------------------------------------------------ ask

    /**
     * The shape this whole thing exists for: a question with two answers, which is what a claim fee
     * and a town council vote both are.
     */
    @Nested
    @DisplayName("asking a yes-or-no question")
    class Ask {

        @Test
        @DisplayName("both answers work, and answering closes the other one too")
        void answeringOnceAnswersTheQuestion() {
            Component question = buttons.ask(ALICE, Duration.ofMinutes(5),
                    accepted::add, denied::add);
            List<String> tokens = new ArrayList<>();
            for (Component child : question.children()) {
                String command = commandOf(child.style().clickEvent());
                if (command != null) {
                    tokens.add(command.substring(command.lastIndexOf(' ') + 1));
                }
            }
            assertThat(tokens).hasSize(2);

            assertThat(actions.run(ALICE, tokens.get(0))).isEqualTo(ClickResult.RAN);
            assertThat(accepted).containsExactly(ALICE);

            assertThat(actions.run(ALICE, tokens.get(1)))
                    .as("saying yes must take the [Deny] button away, not leave it live")
                    .isEqualTo(ClickResult.UNKNOWN);
            assertThat(denied).isEmpty();
        }

        @Test
        @DisplayName("nobody but the person asked can answer")
        void onlyTheAskedMayAnswer() {
            Component question = buttons.ask(ALICE, Duration.ofMinutes(5),
                    accepted::add, denied::add);
            String first = commandOf(question.children().getFirst().style().clickEvent());
            String token = first.substring(first.lastIndexOf(' ') + 1);
            assertThat(actions.run(BOB, token)).isEqualTo(ClickResult.NOT_YOURS);
        }
    }

    // ------------------------------------------------------------------ nobody registered it

    /**
     * What happens before a plugin has registered the callback command.
     *
     * <p>Core registers no commands at all, deliberately, so this is the state a fresh server is in.
     * The behaviour that matters is that it degrades to readable text rather than to a button that
     * looks live and does nothing when clicked — which is the version somebody reports as "the
     * plugin is broken".
     */
    @Nested
    @DisplayName("with no callback command registered")
    class NotWiredUp {

        @Test
        @DisplayName("a button is drawn but is not clickable")
        void rendersWithoutAClick() {
            ChatButtons loose = new ChatButtons(actions, "");
            assertThat(loose.isClickable()).isFalse();

            Component button = loose.label("<green>[Yes]").forOnly(ALICE).does(accepted::add).render();

            assertThat(PlainTextComponentSerializer.plainText().serialize(button))
                    .as("the label still has to be readable; it is the click that is missing")
                    .isEqualTo("[Yes]");
            assertThat(button.style().clickEvent())
                    .as("a button that looks live and does nothing is worse than plain text")
                    .isNull();
        }

        @Test
        @DisplayName("no callback is registered for a click that cannot happen")
        void doesNotLeakCallbacks() {
            ChatButtons loose = new ChatButtons(actions, "");
            loose.label("<green>[Yes]").forOnly(ALICE).does(accepted::add).render();

            assertThat(actions.size())
                    .as("registering a callback nothing can ever run is a slow leak")
                    .isZero();
        }

        @Test
        @DisplayName("it becomes clickable once a plugin says what it registered")
        void becomesClickable() {
            ChatButtons loose = new ChatButtons(actions, "");
            loose.callbackCommand("/myplugin:rcclick");

            assertThat(loose.isClickable()).isTrue();
            Component button = loose.label("<green>[Yes]").forOnly(ALICE).does(accepted::add).render();
            assertThat(commandOf(button.style().clickEvent()))
                    .as("a leading slash somebody typed must not become a double slash")
                    .startsWith("/myplugin:rcclick ");
        }
    }
}
