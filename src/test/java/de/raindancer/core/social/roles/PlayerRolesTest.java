package de.raindancer.core.social.roles;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PlayerRolesTest {

    private final org.bukkit.plugin.Plugin owner = mock(org.bukkit.plugin.Plugin.class);
    private final UUID ana = UUID.randomUUID();

    @AfterEach
    void reset() {
        PlayerRoles.clear();
    }

    @Test
    @DisplayName("with no roles plugin nobody has a role")
    void nobody() {
        assertThat(PlayerRoles.of(ana)).isEmpty();
        assertThat(PlayerRoles.of(null)).isEmpty();
    }

    @Test
    @DisplayName("the source is asked, and retracting it takes every role away")
    void asks() {
        RoleSource roles = player -> player.equals(ana) ? Optional.of(new HeldRole("miner", "Miner", "#8d99ae"))
                : Optional.empty();
        PlayerRoles.provide(owner, roles);
        assertThat(PlayerRoles.of(ana)).contains(new HeldRole("miner", "Miner", "#8d99ae"));
        assertThat(PlayerRoles.of(UUID.randomUUID())).isEmpty();
        PlayerRoles.retract(roles);
        assertThat(PlayerRoles.of(ana)).isEmpty();
    }

    @Test
    @DisplayName("a source that throws or answers null gives no role; the next one still counts")
    void broken() {
        PlayerRoles.provide(owner, player -> {
            throw new IllegalStateException("loading");
        });
        PlayerRoles.provide(owner, player -> null);
        PlayerRoles.provide(owner, player -> Optional.of(new HeldRole("cook", "Cook", "#e8a33d")));
        assertThat(PlayerRoles.of(ana)).map(HeldRole::id).contains("cook");
    }

    @Test
    @DisplayName("what a disabled plugin's code provided is forgotten")
    void forgetFrom() {
        RoleSource mine = player -> Optional.of(new HeldRole("cook", "Cook", "#e8a33d"));
        PlayerRoles.provide(owner, mine);
        PlayerRoles.provide(owner, mine);
        assertThat(PlayerRoles.forgetFrom(mine.getClass().getClassLoader())).isEqualTo(1);
        assertThat(PlayerRoles.of(ana)).isEmpty();
    }
}
