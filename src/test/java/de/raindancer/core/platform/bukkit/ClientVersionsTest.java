package de.raindancer.core.platform.bukkit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ClientVersionsTest {

    @Test
    @DisplayName("a client on another protocol than the server's is translated; unknown either side is not")
    void differs() {
        assertThat(ClientVersions.differs(774, 777)).isTrue();
        assertThat(ClientVersions.differs(777, 777)).isFalse();
        assertThat(ClientVersions.differs(-1, 777)).isFalse();
        assertThat(ClientVersions.differs(774, -1)).isFalse();
    }

    @Test
    @DisplayName("without ViaVersion nobody is translated and nothing breaks")
    void withoutVia() {
        UUID somebody = UUID.randomUUID();
        assertThat(ClientVersions.of(somebody)).isEmpty();
        assertThat(ClientVersions.translated(somebody)).isFalse();
    }
}
