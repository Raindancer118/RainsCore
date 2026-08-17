package de.raindancer.core.ui.profile;

import de.raindancer.core.ui.chat.ChatButtons;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileLinkTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final UUID SUBJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    @DisplayName("wraps the name without changing its text")
    void leavesTextUnchanged() {
        Component wrapped = ProfileLink.of("Raindancer118", SUBJECT);

        assertThat(PLAIN.serialize(wrapped)).isEqualTo("Raindancer118");
    }

    @Test
    @DisplayName("clicking it runs the profile command with the subject's id")
    void clicksRunProfileCommand() {
        Component wrapped = ProfileLink.of("Raindancer118", SUBJECT);

        ClickEvent<?> click = wrapped.style().clickEvent();
        assertThat(click).as("no click event at all").isNotNull();
        assertThat(click.action()).isEqualTo(ClickEvent.Action.RUN_COMMAND);
        assertThat(ChatButtons.commandOf(click)).isEqualTo(ProfileLink.COMMAND + " " + SUBJECT);
    }

    @Test
    @DisplayName("it has something to say on hover")
    void hasAHoverHint() {
        Component wrapped = ProfileLink.of("Raindancer118", SUBJECT);

        HoverEvent<?> hover = wrapped.style().hoverEvent();
        assertThat(hover).isNotNull();
    }

    @Test
    @DisplayName("an already-styled name keeps that style, just gains the click")
    void preservesExistingStyling() {
        Component coloured = Component.text("Raindancer118")
                .color(net.kyori.adventure.text.format.NamedTextColor.GOLD);

        Component wrapped = ProfileLink.of(coloured, SUBJECT);

        assertThat(wrapped.color()).isEqualTo(net.kyori.adventure.text.format.NamedTextColor.GOLD);
        assertThat(wrapped.style().clickEvent()).isNotNull();
    }

    @Test
    @DisplayName("a null name or subject is handed back untouched rather than throwing")
    void nullsAreHarmless() {
        assertThat(ProfileLink.of((Component) null, SUBJECT)).isNull();
        assertThat(ProfileLink.of(Component.text("x"), null).style().clickEvent()).isNull();
    }
}
