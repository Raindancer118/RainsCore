package de.raindancer.core.data.settings;

import de.raindancer.core.ui.chat.ChatButtons;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one piece of {@link SettingsMenu} worth a test without a server: the clickable half of its
 * typed-value prompt. See that class's own note on why nothing else in it has one.
 */
class SettingsMenuPromptTest {

    @Test
    @DisplayName("the cancel button sends the word 'cancel' as a chat line, not a slash command")
    void cancelButtonSendsPlainChatLine() {
        Component parsed = MiniMessage.miniMessage().deserialize(SettingsMenu.CANCEL_BUTTON);

        ClickEvent<?> click = findClickEvent(parsed);
        assertThat(click).as("the button has no click event at all").isNotNull();
        assertThat(click.action()).isEqualTo(ClickEvent.Action.RUN_COMMAND);
        assertThat(ChatButtons.commandOf(click))
                .as("a leading slash would make the client dispatch this as a command instead of "
                        + "the plain chat line ChatPrompts.offer() is waiting for")
                .isEqualTo("cancel");
    }

    private static ClickEvent<?> findClickEvent(Component component) {
        if (component.style().clickEvent() != null) {
            return component.style().clickEvent();
        }
        for (Component child : component.children()) {
            ClickEvent<?> found = findClickEvent(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
