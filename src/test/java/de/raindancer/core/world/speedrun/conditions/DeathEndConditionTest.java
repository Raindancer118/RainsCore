package de.raindancer.core.world.speedrun.conditions;

import de.raindancer.core.world.speedrun.SpeedrunSession;
import de.raindancer.core.world.speedrun.SpeedrunState;
import org.bukkit.Server;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DeathEndCondition.DeathPolicy#ANY}: the first participant to die ends it for everybody.
 * {@link DeathEndCondition.DeathPolicy#ALL}: only once every one of them has.
 */
class DeathEndConditionTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());
    private static final UUID STRANGER = UUID.nameUUIDFromBytes("stranger".getBytes());

    private static Plugin fakePlugin() {
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        when(server.getPluginManager()).thenReturn(manager);
        Plugin plugin = mock(Plugin.class);
        when(plugin.getServer()).thenReturn(server);
        return plugin;
    }

    private static Player playerWithId(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    private static PlayerDeathEvent deathOf(UUID id) {
        return new PlayerDeathEvent(playerWithId(id), mock(DamageSource.class), List.of(), 0,
                net.kyori.adventure.text.Component.text("died"), false);
    }

    @Nested
    @DisplayName("ANY policy")
    class Any {

        @Test
        void finishesOnTheFirstParticipantDeath() {
            SpeedrunSession session = new SpeedrunSession(Set.of(ALICE, BOB));
            DeathEndCondition condition =
                    new DeathEndCondition(fakePlugin(), DeathEndCondition.DeathPolicy.ANY);
            session.addEndCondition(condition);
            session.start();

            condition.onDeath(deathOf(ALICE));

            assertThat(session.state()).isEqualTo(SpeedrunState.FINISHED);
            assertThat(session.outcome().get().reason()).isEqualTo("death:" + ALICE);
        }

        @Test
        void aNonParticipantDeathDoesNotFinishIt() {
            SpeedrunSession session = new SpeedrunSession(Set.of(ALICE, BOB));
            DeathEndCondition condition =
                    new DeathEndCondition(fakePlugin(), DeathEndCondition.DeathPolicy.ANY);
            session.addEndCondition(condition);
            session.start();

            condition.onDeath(deathOf(STRANGER));

            assertThat(session.state()).isEqualTo(SpeedrunState.RUNNING);
        }
    }

    @Nested
    @DisplayName("ALL policy")
    class All {

        @Test
        void doesNotFinishUntilEveryoneHasDied() {
            SpeedrunSession session = new SpeedrunSession(Set.of(ALICE, BOB));
            DeathEndCondition condition =
                    new DeathEndCondition(fakePlugin(), DeathEndCondition.DeathPolicy.ALL);
            session.addEndCondition(condition);
            session.start();

            condition.onDeath(deathOf(ALICE));
            assertThat(session.state()).isEqualTo(SpeedrunState.RUNNING);

            condition.onDeath(deathOf(BOB));
            assertThat(session.state()).isEqualTo(SpeedrunState.FINISHED);
            assertThat(session.outcome().get().reason()).isEqualTo("death-all");
        }

        @Test
        void aNonParticipantDeathDoesNotCountTowardsTheGoal() {
            SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
            DeathEndCondition condition =
                    new DeathEndCondition(fakePlugin(), DeathEndCondition.DeathPolicy.ALL);
            session.addEndCondition(condition);
            session.start();

            condition.onDeath(deathOf(STRANGER));

            assertThat(session.state()).isEqualTo(SpeedrunState.RUNNING);
        }
    }

    @Test
    @DisplayName("disarm clears who has died, so a re-armed condition starts fresh")
    void disarmClearsTracking() {
        DeathEndCondition condition =
                new DeathEndCondition(fakePlugin(), DeathEndCondition.DeathPolicy.ALL);
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE, BOB));
        session.addEndCondition(condition);
        session.start();
        condition.onDeath(deathOf(ALICE));

        condition.disarm();   // as finish() would call it

        // Re-arming and replaying just Bob's death must not immediately finish it as if Alice's
        // earlier death were still remembered.
        SpeedrunSession fresh = new SpeedrunSession(Set.of(ALICE, BOB));
        fresh.start();
        condition.arm(fresh);
        condition.onDeath(deathOf(BOB));

        assertThat(fresh.state()).isEqualTo(SpeedrunState.RUNNING);
    }
}
