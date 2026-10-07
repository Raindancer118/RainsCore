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
    @DisplayName("every visible online name and nickname, then the offline — never yourself")
    void offered() {
        MentionCompletions.Known online = new MentionCompletions.Known(
                Map.of(ME, "Me", LILLY, "lillyyxoxo", HIDDEN, "Sneaky"));
        Map<UUID, String> nicknames = Map.of(LILLY, "Lilly_Pad", HIDDEN, "Shadow", GONE, "Casper");
        List<String> offline = List.of("OldGhost", "Sneaky", "Me");

        List<String> offered = MentionCompletions.completionsFor(ME, online, who -> !who.equals(HIDDEN),
                nicknames, offline);

        assertThat(offered).contains("@lillyyxoxo", "@Lilly_Pad", "@Casper", "@OldGhost").doesNotContain("@Me");
        assertThat(offered.indexOf("@lillyyxoxo")).isLessThan(offered.indexOf("@OldGhost"));
    }

    @Test
    @DisplayName("a hidden player is offered exactly as if they were offline — their absence would give them away")
    void hiddenLooksOffline() {
        MentionCompletions.Known hiddenOnline = new MentionCompletions.Known(Map.of(ME, "Me", HIDDEN, "Sneaky"));
        MentionCompletions.Known hiddenOffline = new MentionCompletions.Known(Map.of(ME, "Me"));
        Map<UUID, String> nicknames = Map.of(HIDDEN, "Shadow");
        List<String> offline = List.of("Sneaky");

        List<String> whileHidden = MentionCompletions.completionsFor(ME, hiddenOnline, who -> !who.equals(HIDDEN),
                nicknames, offline);
        List<String> whileAway = MentionCompletions.completionsFor(ME, hiddenOffline, who -> true,
                nicknames, offline);

        assertThat(whileHidden).containsExactlyInAnyOrderElementsOf(whileAway).contains("@Sneaky", "@Shadow");
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
