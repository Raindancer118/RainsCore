package de.raindancer.core.ui.chat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("chat channels — team chat and the like")
class ChatChannelsTest {

    private static final UUID ANNA = UUID.nameUUIDFromBytes("anna".getBytes());
    private static final UUID BEN = UUID.nameUUIDFromBytes("ben".getBytes());

    private final ChatChannel team = new ChatChannel() {
        @Override
        public String id() {
            return "team";
        }

        @Override
        public String label() {
            return "Team";
        }

        @Override
        public Optional<Set<UUID>> audienceFor(UUID speaker) {
            return ANNA.equals(speaker) ? Optional.of(Set.of(ANNA, BEN)) : Optional.empty();
        }
    };

    @AfterEach
    void clean() {
        ChatChannels.unregister(team);
        ChatChannels.forget(ANNA);
        ChatChannels.forget(BEN);
    }

    @Test
    @DisplayName("everybody talks to everybody until they choose otherwise")
    void allByDefault() {
        ChatChannels.register(team);

        assertThat(ChatChannels.selected(ANNA)).isEqualTo(ChatChannels.ALL);
        assertThat(ChatChannels.route(ANNA)).isEmpty();
    }

    @Test
    @DisplayName("a selected channel routes the line to its audience, with its tag")
    void routes() {
        ChatChannels.register(team);
        assertThat(ChatChannels.select(ANNA, "team")).isTrue();

        ChatChannels.Route route = ChatChannels.route(ANNA).orElseThrow();

        assertThat(route.audience()).containsExactlyInAnyOrder(ANNA, BEN);
        assertThat(route.tag()).isEqualTo("[Team]");
    }

    @Test
    @DisplayName("a channel the speaker has no part in right now falls back to everybody")
    void unavailableFallsBack() {
        ChatChannels.register(team);
        ChatChannels.select(BEN, "team");

        assertThat(ChatChannels.route(BEN)).isEmpty();
        assertThat(ChatChannels.availableTo(BEN)).isEmpty();
        assertThat(ChatChannels.availableTo(ANNA)).containsExactly(team);
    }

    @Test
    @DisplayName("an unknown channel cannot be selected, and 'all' always can")
    void selecting() {
        assertThat(ChatChannels.select(ANNA, "nonsense")).isFalse();
        assertThat(ChatChannels.select(ANNA, ChatChannels.ALL)).isTrue();
        assertThat(ChatChannels.selected(ANNA)).isEqualTo(ChatChannels.ALL);
    }

    @Test
    @DisplayName("a channel whose plugin unloaded routes nowhere")
    void unregistered() {
        ChatChannels.register(team);
        ChatChannels.select(ANNA, "team");
        ChatChannels.unregister(team);

        assertThat(ChatChannels.route(ANNA)).isEmpty();
    }
}
