package de.raindancer.core.ui.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What a player's chat box offers after an @. */
class MentionCompletionsTest {

    private static final UUID ME = UUID.nameUUIDFromBytes("me".getBytes());
    private static final UUID LILLY = UUID.nameUUIDFromBytes("lilly".getBytes());
    private static final UUID HIDDEN = UUID.nameUUIDFromBytes("hidden".getBytes());
    private static final UUID GONE = UUID.nameUUIDFromBytes("gone".getBytes());

    @Test
    @DisplayName("every visible online name and nickname, the offline after them, never yourself or the hidden")
    void offered() {
        MentionCompletions.Known online = new MentionCompletions.Known(
                Map.of(ME, "Me", LILLY, "lillyyxoxo", HIDDEN, "Sneaky"));
        Map<UUID, String> nicknames = Map.of(LILLY, "Lilly_Pad", HIDDEN, "Shadow", GONE, "Casper");
        List<String> offline = List.of("OldGhost");

        List<String> offered = MentionCompletions.completionsFor(ME, online, who -> !who.equals(HIDDEN),
                nicknames, offline);

        assertThat(offered).contains("@lillyyxoxo", "@Lilly_Pad", "@Casper", "@OldGhost")
                .doesNotContain("@Me", "@Sneaky", "@Shadow");
        assertThat(offered.indexOf("@lillyyxoxo")).isLessThan(offered.indexOf("@OldGhost"));
    }

    @Test
    @DisplayName("what changed is worked out, so only differences are sent")
    void difference() {
        MentionCompletions.Change change = MentionCompletions.difference(Set.of("@a", "@b"), Set.of("@b", "@c"));

        assertThat(change.added()).containsExactly("@c");
        assertThat(change.removed()).containsExactly("@a");
        assertThat(MentionCompletions.difference(Set.of("@a"), Set.of("@a")).isEmpty()).isTrue();
    }
}
