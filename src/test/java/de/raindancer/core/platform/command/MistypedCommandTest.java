package de.raindancer.core.platform.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MistypedCommandTest {

    private final List<String> usable = List.of("home", "homes", "warp", "warps", "gamemode", "tpa",
            "minecraft:gamemode", "rainscore:home");

    @Test
    @DisplayName("a swapped pair of letters finds the command, closest first")
    void swapped() {
        assertThat(MistypedCommand.closest("hoem", usable, 3))
                .extracting(MistypedCommand.Guess::command)
                .startsWith("home");
    }

    @Test
    @DisplayName("what came after the command is kept, so the guess is the whole line")
    void keepsArguments() {
        MistypedCommand.Guess guess = MistypedCommand.closest("wrap spawn north", usable, 1).getFirst();
        assertThat(guess.line()).isEqualTo("/warp spawn north");
        assertThat(guess.bare()).isFalse();
    }

    @Test
    @DisplayName("a guess without arguments is bare, one with them is not")
    void bare() {
        assertThat(MistypedCommand.closest("wrap", usable, 1).getFirst().bare()).isTrue();
        assertThat(MistypedCommand.closest("wrap  ", usable, 1).getFirst().bare()).isTrue();
    }

    @Test
    @DisplayName("a leading slash and a namespace on what was typed are ignored")
    void slashAndNamespace() {
        assertThat(MistypedCommand.closest("/minecraft:gamemod creative", usable, 1))
                .extracting(MistypedCommand.Guess::line)
                .containsExactly("/gamemode creative");
    }

    @Test
    @DisplayName("namespaced spellings are never offered — the short name is, once")
    void noNamespacedDuplicates() {
        assertThat(MistypedCommand.closest("gamemod", usable, 5))
                .extracting(MistypedCommand.Guess::command)
                .containsExactly("gamemode");
    }

    @Test
    @DisplayName("upper case typed still finds the command, and the guess is in its real spelling")
    void caseIgnored() {
        assertThat(MistypedCommand.closest("HOEM", usable, 1))
                .extracting(MistypedCommand.Guess::command)
                .containsExactly("home");
    }

    @Test
    @DisplayName("nothing close, nothing typed, or no commands: no guess rather than a bad one")
    void nothing() {
        assertThat(MistypedCommand.closest("xyzzyplugh", usable, 3)).isEmpty();
        assertThat(MistypedCommand.closest("", usable, 3)).isEmpty();
        assertThat(MistypedCommand.closest("/", usable, 3)).isEmpty();
        assertThat(MistypedCommand.closest(null, usable, 3)).isEmpty();
        assertThat(MistypedCommand.closest("hoem", List.of(), 3)).isEmpty();
    }

    @Test
    @DisplayName("the limit is kept")
    void limit() {
        assertThat(MistypedCommand.closest("hom", usable, 1)).hasSize(1);
    }
}
