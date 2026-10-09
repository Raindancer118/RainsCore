package de.raindancer.core.social.presence;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PresenceLinesTest {

    @AfterEach
    void clear() {
        PresenceLines.clear();
    }

    private record Recording(boolean speaks, List<String> said) implements PresenceLines.Voice {
        @Override
        public boolean arrived(Player who, Collection<? extends Player> to) {
            said.add("arrived to " + to.size());
            return speaks;
        }

        @Override
        public boolean departed(Player who, Collection<? extends Player> to) {
            said.add("departed to " + to.size());
            return speaks;
        }
    }

    @Test
    @DisplayName("nobody speaking means vanilla's line")
    void silentFallsBack() {
        assertThat(PresenceLines.departed(mock(Player.class), List.of())).isFalse();
        PresenceLines.speak(new Recording(false, new ArrayList<>()));
        assertThat(PresenceLines.departed(mock(Player.class), List.of())).isFalse();
    }

    @Test
    @DisplayName("a voice that speaks says it to exactly whom it is given, and vanilla says nothing")
    void spoken() {
        List<String> said = new ArrayList<>();
        PresenceLines.speak(new Recording(true, said));
        assertThat(PresenceLines.departed(mock(Player.class), List.of(mock(Player.class), mock(Player.class)))).isTrue();
        assertThat(PresenceLines.arrived(mock(Player.class), List.of(mock(Player.class)))).isTrue();
        assertThat(said).containsExactly("departed to 2", "arrived to 1");
    }

    @Test
    @DisplayName("a broken voice falls through to vanilla rather than swallowing the line")
    void broken() {
        PresenceLines.speak(new PresenceLines.Voice() {
            @Override
            public boolean arrived(Player who, Collection<? extends Player> to) {
                throw new IllegalStateException("broken");
            }

            @Override
            public boolean departed(Player who, Collection<? extends Player> to) {
                throw new IllegalStateException("broken");
            }
        });
        assertThat(PresenceLines.departed(mock(Player.class), List.of())).isFalse();
    }
}
