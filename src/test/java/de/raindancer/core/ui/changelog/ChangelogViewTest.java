package de.raindancer.core.ui.changelog;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChangelogViewTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final long OCT_10 = LocalDate.of(2026, 10, 10).atTime(12, 0).atZone(BERLIN).toInstant().toEpochMilli();

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    @DisplayName("each entry: its title, its date, its points; and how to read it again")
    void rendersEntries() {
        ChangelogEntry entry = new ChangelogEntry("lb", "<gold>Leaderboard fixed", List.of("Bedrock players <b>count</b>."), OCT_10);

        String text = plain(ChangelogView.render(List.of(entry), "Since you were last here", BERLIN));

        assertThat(text).contains("Since you were last here")
                .contains("Leaderboard fixed")
                .contains("10 Oct")
                .contains("• Bedrock players count.")
                .contains("/changelog");
    }

    @Test
    @DisplayName("points are grey and titles yellow unless the entry paints them itself")
    void coloured() {
        ChangelogEntry entry = new ChangelogEntry("lb", "Title", List.of("Point"), OCT_10);
        net.kyori.adventure.text.Component built = ChangelogView.render(List.of(entry), "News", BERLIN);

        assertThat(colourOf(built, "Point")).isEqualTo(net.kyori.adventure.text.format.NamedTextColor.GRAY);
        assertThat(colourOf(built, "Title")).isEqualTo(net.kyori.adventure.text.format.NamedTextColor.YELLOW);
    }

    /** The colour the text is drawn in: its own, or the nearest parent's. */
    private static net.kyori.adventure.text.format.TextColor colourOf(net.kyori.adventure.text.Component root, String text) {
        return find(root, text, null);
    }

    private static net.kyori.adventure.text.format.TextColor find(net.kyori.adventure.text.Component node, String text,
                                                                  net.kyori.adventure.text.format.TextColor inherited) {
        net.kyori.adventure.text.format.TextColor colour = node.color() != null ? node.color() : inherited;
        if (node instanceof net.kyori.adventure.text.TextComponent t && t.content().equals(text)) {
            return colour;
        }
        for (net.kyori.adventure.text.Component child : node.children()) {
            net.kyori.adventure.text.format.TextColor found = find(child, text, colour);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    @DisplayName("a draft is marked as one, so staff previewing it cannot mistake it for what players see")
    void draftMarked() {
        ChangelogEntry draft = new ChangelogEntry("lb", "Leaderboard fixed", List.of("x"), 0);

        assertThat(plain(ChangelogView.render(List.of(draft), "Drafts", BERLIN))).contains("draft lb");
    }
}
