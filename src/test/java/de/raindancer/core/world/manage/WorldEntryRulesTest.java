package de.raindancer.core.world.manage;

import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * "May this player go into that world right now?" — asked by whatever moves people, answered by
 * whatever locks worlds, with neither knowing the other.
 */
class WorldEntryRulesTest {

    private final WorldEntryRules rules = new WorldEntryRules();
    private final Player player = mock(Player.class);
    private final World nether = mock(World.class);

    @Test
    @DisplayName("nobody has an opinion: the way is open")
    void openByDefault() {
        assertThat(rules.refusal(player, nether)).isEmpty();
    }

    @Test
    @DisplayName("the first refusal is the answer, worded by whoever refused")
    void firstRefusalWins() {
        rules.register("open", (who, where) -> Optional.empty());
        rules.register("gate", (who, where) -> Optional.of(Component.text("The Nether is closed")));
        rules.register("later", (who, where) -> Optional.of(Component.text("never asked")));

        assertThat(rules.refusal(player, nether)).contains(Component.text("The Nether is closed"));
    }

    @Test
    @DisplayName("a rule that is closed again stops being asked")
    void unregistering() throws Exception {
        AutoCloseable handle = rules.register("gate", (who, where) -> Optional.of(Component.text("no")));
        handle.close();

        assertThat(rules.refusal(player, nether)).isEmpty();
    }

    @Test
    @DisplayName("a rule that throws is skipped rather than locking every world on the server")
    void aBrokenRuleIsNotARefusal() {
        rules.register("broken", (who, where) -> {
            throw new IllegalStateException("bug");
        });

        assertThat(rules.refusal(player, nether)).isEmpty();
    }

    @Test
    @DisplayName("no player or no world is nothing to refuse")
    void nulls() {
        rules.register("gate", (who, where) -> Optional.of(Component.text("no")));

        assertThat(rules.refusal(null, nether)).isEmpty();
        assertThat(rules.refusal(player, null)).isEmpty();
    }
}
