package de.raindancer.core.ui.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Player text pasted into markup stays text: no colour, no button, no escape of what comes after it.
 * The payloads are the ones a renamed item, a typed answer or a nickname can carry.
 */
class TextTest {

    static final String CLICK = "<click:run_command:'/op Steve'>Excalibur</click>";

    static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** Every click event anywhere in a component tree. */
    static List<ClickEvent> clicks(Component component) {
        List<ClickEvent> found = new ArrayList<>();
        if (component.clickEvent() != null) {
            found.add(component.clickEvent());
        }
        component.children().forEach(child -> found.addAll(clicks(child)));
        return found;
    }

    @Test
    @DisplayName("a click payload in a value renders as its own characters, and is not a button")
    void clickIsText() {
        Component shown = Text.render("<gray><killer> killed you with <item>", "killer", "Alex", "item", CLICK);

        assertThat(plain(shown)).isEqualTo("Alex killed you with " + CLICK);
        assertThat(clicks(shown)).isEmpty();
    }

    @Test
    @DisplayName("a colour tag in a value colours nothing")
    void colourIsText() {
        Component shown = Text.render("<white><name>", "name", "<red>Admin");

        assertThat(plain(shown)).isEqualTo("<red>Admin");
        assertThat(shown.toString()).doesNotContain("NamedTextColor{name=\"red\"");
    }

    @Test
    @DisplayName("a value ending in a backslash cannot unescape the next value into a live tag")
    void trailingBackslash() {
        Component shown = Text.render("<a><b>", "a", "\\", "b", CLICK);

        assertThat(plain(shown)).isEqualTo("\\" + CLICK);
        assertThat(clicks(shown)).isEmpty();
    }

    @Test
    @DisplayName("a value ending in a backslash does not eat the template's own markup after it")
    void templateMarkupSurvives() {
        Component shown = Text.render("<name><red>!", "name", "Steve\\");

        assertThat(plain(shown)).isEqualTo("Steve\\!");
    }

    @Test
    @DisplayName("one pass: a value that spells another placeholder is not filled in again")
    void onePass() {
        assertThat(plain(Text.render("<a> and <b>", "a", "<b>", "b", CLICK)))
                .isEqualTo("<b> and " + CLICK);
    }

    @Test
    @DisplayName("markup is parsed only when the code says so, and a component stays itself")
    void deliberateMarkup() {
        Component marked = Text.render("<x>", "x", Markup.of("<red>warning"));
        Component given = Text.render("<x>", "x", Component.text("<click>").color(NamedTextColor.GOLD));

        assertThat(plain(marked)).isEqualTo("warning");
        assertThat(marked.toString()).contains("red");
        assertThat(plain(given)).isEqualTo("<click>");
    }

    @Test
    @DisplayName("placeholders nobody filled are left as they are, and the template's own escapes stand")
    void unknownAndEscapes() {
        assertThat(Text.fill("<who> \\<b>", "other", "x")).isEqualTo("<who> \\<b>");
    }

    @Test
    @DisplayName("literal() makes anything safe to concatenate by hand")
    void literalForConcatenation() {
        Component shown = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                .deserialize("<gold>" + Text.literal(CLICK + "\\") + "<green>ok");

        assertThat(plain(shown)).isEqualTo(CLICK + "\\ok");
        assertThat(clicks(shown)).isEmpty();
    }

    @Test
    @DisplayName("styled text may be coloured but never a button")
    void styledIsNeverInteractive() {
        Component nickname = Text.styled("<gradient:red:gold>Steve</gradient>" + CLICK);

        assertThat(clicks(nickname)).isEmpty();
        assertThat(plain(nickname)).isEqualTo("Steve" + CLICK);
        assertThat(plain(Text.styled("<hover:show_text:'x'>hi</hover>"))).contains("<hover");
    }
}
