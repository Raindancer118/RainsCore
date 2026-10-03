package de.raindancer.core.social.team;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teams is written from commands, joins and sweeps — on Folia, from as many region threads as there are
 * players. Every call is one step on one roster.
 */
class TeamsConcurrencyTest {

    private boolean frozen;
    private final Teams teams = new Teams(TeamPolicy::tournament, () -> frozen, uuid -> true);

    @Test
    @DisplayName("joining, leaving, creating and reading from many threads at once keeps one team per player")
    void manyThreads() throws Exception {
        List<TeamId> ids = new ArrayList<>();
        for (TeamColour colour : List.of(TeamColour.RED, TeamColour.BLUE, TeamColour.GREEN)) {
            ids.add(teams.create(colour.name(), colour).team().orElseThrow().id());
        }
        List<UUID> players = new ArrayList<>();
        for (int at = 0; at < 40; at++) {
            players.add(UUID.randomUUID());
        }
        ConcurrentLinkedQueue<Throwable> thrown = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        for (int worker = 0; worker < 8; worker++) {
            Random random = new Random(worker);
            pool.submit(() -> {
                try {
                    go.await();
                    for (int step = 0; step < 4_000; step++) {
                        UUID player = players.get(random.nextInt(players.size()));
                        switch (random.nextInt(5)) {
                            case 0, 1 -> teams.join(player, ids.get(random.nextInt(ids.size())));
                            case 2 -> teams.leave(player);
                            case 3 -> teams.all();
                            default -> teams.teamOf(player);
                        }
                    }
                } catch (Throwable failure) {
                    thrown.add(failure);
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(thrown).as("unsynchronised, the roster's maps throw or tear under this").isEmpty();
        Set<UUID> seen = new HashSet<>();
        for (Team team : teams.all()) {
            for (UUID member : team.members()) {
                assertThat(seen.add(member)).as("a player in two teams at once").isTrue();
            }
        }
    }

    @Nested
    @DisplayName("placing somebody — a host's join, refused only by the freeze")
    class Placing {

        @Test
        @DisplayName("ignores switching, size and eligibility rules, and moves them out of their old team")
        void ignoresThePolicy() {
            Teams strict = new Teams(() -> TeamPolicy.tournament().withMaxMembers(1)
                    .withAllowSwitching(false), () -> frozen, uuid -> false);
            TeamId red = strict.create("Red", TeamColour.RED).team().orElseThrow().id();
            TeamId blue = strict.create("Blue", TeamColour.BLUE).team().orElseThrow().id();
            UUID one = UUID.randomUUID();
            UUID two = UUID.randomUUID();

            assertThat(strict.place(one, red).status()).isEqualTo(TeamOutcome.SUCCESS);
            assertThat(strict.place(two, red).status()).isEqualTo(TeamOutcome.SUCCESS);
            Teams.MembershipChange moved = strict.place(one, blue);

            assertThat(moved.status()).isEqualTo(TeamOutcome.SUCCESS);
            assertThat(moved.oldTeam()).contains(red);
            assertThat(strict.teamIdOf(one)).contains(blue);
            assertThat(strict.team(red).orElseThrow().members()).containsExactly(two);
        }

        @Test
        @DisplayName("is refused while teams are frozen, and for a team that does not exist")
        void refusedByTheFreeze() {
            TeamId red = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
            frozen = true;

            assertThat(teams.place(UUID.randomUUID(), red).status()).isEqualTo(TeamOutcome.FROZEN);
            frozen = false;
            assertThat(teams.place(UUID.randomUUID(), TeamId.fromName("nobody")).status())
                    .isEqualTo(TeamOutcome.NO_SUCH_TEAM);
        }
    }

    @Test
    @DisplayName("reassigning to a UUID that is already in another team leaves them in exactly one")
    void reassignKeepsOneTeam() {
        TeamId red = teams.create("Red", TeamColour.RED).team().orElseThrow().id();
        TeamId blue = teams.create("Blue", TeamColour.BLUE).team().orElseThrow().id();
        UUID placeholder = UUID.randomUUID();
        UUID real = UUID.randomUUID();
        teams.join(placeholder, red);
        teams.join(real, blue);

        teams.reassign(placeholder, real);

        assertThat(teams.teamIdOf(real)).contains(red);
        assertThat(teams.team(blue).orElseThrow().members()).doesNotContain(real);
    }
}
