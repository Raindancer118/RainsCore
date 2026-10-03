package de.raindancer.core.platform.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandsCommandTest {

    private final List<CommandNote> notes = List.of(
            CommandNote.of("Homes", "home", "Go home."),
            CommandNote.of("Homes", "home set", "Set a home here."),
            CommandNote.of("Warps", "warp", "Go to a warp."));

    @Test
    @DisplayName("what can be asked for: each plugin, and each command's first word, once")
    void names() {
        assertThat(CommandsCommand.namesIn(notes)).containsExactly("Homes", "Warps", "home", "warp");
    }

    @Test
    @DisplayName("asking narrows by plugin or command; asking nothing shows everything")
    void matching() {
        assertThat(CommandsCommand.matching(notes, "warp")).hasSize(1);
        assertThat(CommandsCommand.matching(notes, "homes")).hasSize(2);
        assertThat(CommandsCommand.matching(notes, null)).hasSize(3);
        assertThat(CommandsCommand.matching(notes, "zzz")).isEmpty();
    }
}
