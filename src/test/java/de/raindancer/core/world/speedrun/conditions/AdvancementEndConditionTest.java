package de.raindancer.core.world.speedrun.conditions;

import de.raindancer.core.world.speedrun.SpeedrunSession;
import de.raindancer.core.world.speedrun.SpeedrunState;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ending a run on a specific advancement — {@code minecraft:end/kill_dragon}, or anything else a
 * caller wants to race for.
 */
class AdvancementEndConditionTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());
    private static final NamespacedKey KEY = NamespacedKey.minecraft("end/kill_dragon");

    private Plugin plugin;
    private SpeedrunSession session;
    private AdvancementEndCondition condition;

    @BeforeEach
    void setUp() {
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        when(server.getPluginManager()).thenReturn(manager);
        plugin = mock(Plugin.class);
        when(plugin.getServer()).thenReturn(server);

        session = new SpeedrunSession(Set.of(ALICE));
        condition = new AdvancementEndCondition(plugin, KEY);
        session.addEndCondition(condition);
        session.start();
    }

    private static Player playerWithId(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    private static Advancement advancementWithKey(NamespacedKey key) {
        Advancement advancement = mock(Advancement.class);
        when(advancement.getKey()).thenReturn(key);
        return advancement;
    }

    @Test
    void finishesWhenAParticipantCompletesIt() {
        condition.onAdvancement(
                new PlayerAdvancementDoneEvent(playerWithId(ALICE), advancementWithKey(KEY)));

        assertThat(session.state()).isEqualTo(SpeedrunState.FINISHED);
        assertThat(session.outcome()).isPresent();
        assertThat(session.outcome().get().reason()).isEqualTo("advancement:" + KEY);
    }

    @Test
    void doesNotFinishForADifferentAdvancement() {
        NamespacedKey other = NamespacedKey.minecraft("story/mine_stone");
        condition.onAdvancement(
                new PlayerAdvancementDoneEvent(playerWithId(ALICE), advancementWithKey(other)));

        assertThat(session.state()).isEqualTo(SpeedrunState.RUNNING);
    }

    @Test
    void doesNotFinishForANonParticipant() {
        condition.onAdvancement(
                new PlayerAdvancementDoneEvent(playerWithId(BOB), advancementWithKey(KEY)));

        assertThat(session.state()).isEqualTo(SpeedrunState.RUNNING);
    }
}
