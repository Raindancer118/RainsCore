package de.raindancer.core.ui.messages;

import de.raindancer.core.ui.text.Markup;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Placeholder values are text. A value a player controls — a name, an anvil-renamed item, an answer
 * they typed — can never become a colour, a button or an escape in somebody else's chat.
 */
class MessagesInjectionTest {

    private static final String CLICK = "<click:run_command:'/op Steve'>Excalibur</click>";

    @TempDir
    Path directory;
    private Messages messages;

    @BeforeEach
    void setUp() {
        messages = new Messages(directory.resolve("messages.yml"));
        messages.load(new ByteArrayInputStream("""
                prefix: "<gray>[Test] "
                death: "<red><victim> was slain by <killer> using <item>"
                pair: "<a><b>"
                listed:
                  - "<gray>one <name>"
                  - "<gray>two <name>"
                """.getBytes(StandardCharsets.UTF_8)));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static List<ClickEvent> clicks(Component component) {
        List<ClickEvent> found = new ArrayList<>();
        if (component.clickEvent() != null) {
            found.add(component.clickEvent());
        }
        component.children().forEach(child -> found.addAll(clicks(child)));
        return found;
    }

    @Test
    @DisplayName("an item renamed into a /op button is shown as text, with no click on it")
    void renamedItem() {
        Component line = messages.get("death", "victim", "Alex", "killer", "Steve", "item", CLICK);

        assertThat(plain(line)).isEqualTo("Alex was slain by Steve using " + CLICK);
        assertThat(clicks(line)).isEmpty();
    }

    @Test
    @DisplayName("the same through send(), prefixed, as a player would receive it")
    void sent() {
        Audience admin = mock(Audience.class);
        messages.send(admin, "death", "victim", "<red>Alex", "killer", "Steve\\", "item", CLICK);

        ArgumentCaptor<Component> said = ArgumentCaptor.forClass(Component.class);
        verify(admin).sendMessage(said.capture());
        assertThat(plain(said.getValue())).isEqualTo("[Test] <red>Alex was slain by Steve\\ using " + CLICK);
        assertThat(clicks(said.getValue())).isEmpty();
    }

    @Test
    @DisplayName("a value ending in a backslash cannot turn the next value into a live tag")
    void backslashChain() {
        Component line = messages.get("pair", "a", "\\", "b", CLICK);

        assertThat(clicks(line)).isEmpty();
        assertThat(plain(line)).isEqualTo("\\" + CLICK);
    }

    @Test
    @DisplayName("every line of a multi-line message is just as safe")
    void lines() {
        List<Component> shown = messages.lines("listed", "name", CLICK);

        assertThat(shown).hasSize(2);
        shown.forEach(line -> assertThat(clicks(line)).isEmpty());
        assertThat(plain(shown.get(1))).isEqualTo("two " + CLICK);
    }

    @Test
    @DisplayName("markup a plugin means as markup is wrapped, and only then parsed")
    void deliberateMarkup() {
        Component line = messages.get("death", "victim", "Alex", "killer", "Steve",
                "item", Markup.of("<gold>Excalibur"));

        assertThat(plain(line)).isEqualTo("Alex was slain by Steve using Excalibur");
    }
}
