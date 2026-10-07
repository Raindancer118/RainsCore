package de.raindancer.core.ui.chat;

import de.raindancer.core.ui.text.NameStyle;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Everything the prefix menu does, typed — for the console and for people who know what they want. */
class PrefixCommandTest {

    @TempDir
    Path directory;

    private PrefixService service;
    private PrefixCommand command;
    private final CommandSender console = mock(CommandSender.class);

    @BeforeEach
    void setUp() {
        service = new PrefixService(new PrefixFile(directory.resolve("prefix.yml")));
        service.reload();
        command = new PrefixCommand(() -> service);
    }

    @AfterEach
    void reset() {
        Prefixes.use(PrefixDesign.DEFAULT);
    }

    @Test
    @DisplayName("mode, tag, style and shape change the design and are saved")
    void changesAreSaved() {
        command.run(console, new String[]{"mode", "shared"});
        command.run(console, new String[]{"tag", "Lilly", "SMP"});
        command.run(console, new String[]{"style", "#ff8800,#ffee00|bold"});
        command.run(console, new String[]{"format", "[{tag}]"});

        PrefixDesign saved = new PrefixFile(directory.resolve("prefix.yml")).load();
        assertThat(saved.mode()).isEqualTo(PrefixDesign.Mode.SHARED);
        assertThat(saved.tag()).isEqualTo("Lilly SMP");
        assertThat(saved.style().colours()).hasSize(2);
        assertThat(saved.style().has(TextDecoration.BOLD)).isTrue();
        assertThat(saved.format()).isEqualTo("[{tag}] ");
        assertThat(Prefixes.design()).isEqualTo(saved);
    }

    @Test
    @DisplayName("a shape without {tag} is refused rather than silently dropping the tag")
    void formatNeedsTag() {
        command.run(console, new String[]{"format", "[x]"});

        assertThat(service.design().format()).isEqualTo(PrefixDesign.DEFAULT_FORMAT);
    }

    @Test
    @DisplayName("a style with nothing readable in it is refused")
    void badStyle() {
        command.run(console, new String[]{"style", "nonsense"});

        assertThat(service.design().style()).isEqualTo(NameStyle.NONE);
    }

    @Test
    @DisplayName("one plugin can get its own tag and be hidden, and reset back")
    void plugin() {
        command.run(console, new String[]{"plugin", "Moderation", "tag", "Mod"});
        command.run(console, new String[]{"plugin", "Claims", "hide"});

        assertThat(service.design().tagFor("Moderation", "Moderation")).isEqualTo("Mod");
        assertThat(Prefixes.chatPrefix("Claims", "Claims")).isEmpty();

        command.run(console, new String[]{"plugin", "Claims", "reset"});
        assertThat(Prefixes.chatPrefix("Claims", "Claims")).isNotEmpty();
    }

    @Test
    @DisplayName("hide and reset")
    void hideAndReset() {
        command.run(console, new String[]{"hide"});
        assertThat(Prefixes.chatPrefix("Claims", "Claims")).isEmpty();

        command.run(console, new String[]{"reset"});
        assertThat(service.design()).isEqualTo(PrefixDesign.DEFAULT);
    }

    @Test
    @DisplayName("suggestions: the words, then what each word takes")
    void suggestions() {
        assertThat(command.suggest(null, new String[]{"mo"})).containsExactly("mode");
        assertThat(command.suggest(null, new String[]{"mode", ""})).containsExactly("shared", "per-plugin");
        assertThat(command.suggest(null, new String[]{"plugin", "x", ""})).contains("tag", "style", "reset");
    }
}
