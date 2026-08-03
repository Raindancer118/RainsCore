package de.raindancer.core.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Doing things to a player, from a management screen.
 *
 * <h2>Why this is not just a wrapper round setHealth</h2>
 * Because the mistakes are all in the edges, and every plugin makes them again. Healing somebody
 * above their maximum throws. Damaging somebody for more than they have kills them, from a button
 * that said "damage". Setting a speed effect without clearing the last one stacks them. Feeding
 * somebody above twenty throws. Kicking somebody who has already gone throws. Every one of those is
 * an exception in front of a moderator, or worse, a dead player who was supposed to be nudged.
 *
 * <p>So the decisions live here, tested, and only the doing needs a server.
 */
@DisplayName("player administration")
class PlayerAdminTest {

    /** What would have been done to the server, instead of a server. */
    private final List<String> did = new ArrayList<>();

    /** The state of a made-up player, so the rules have something to be right about. */
    private static final class Fake {
        double health = 20;
        double maxHealth = 20;
        int food = 20;
        boolean online = true;
        boolean flying;
        String gamemode = "SURVIVAL";
        final Map<String, Integer> effects = new LinkedHashMap<>();
    }

    private final Map<UUID, Fake> world = new LinkedHashMap<>();

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID GONE = UUID.randomUUID();

    private PlayerAdmin admin() {
        world.put(ALICE, new Fake());
        return new PlayerAdmin(new PlayerAdminSink() {
            @Override
            public Optional<PlayerState> stateOf(UUID who) {
                Fake fake = world.get(who);
                return fake == null || !fake.online ? Optional.empty()
                        : Optional.of(new PlayerState(fake.health, fake.maxHealth, fake.food,
                                fake.flying, fake.gamemode));
            }

            @Override
            public void health(UUID who, double health) {
                world.get(who).health = health;
                did.add("health=" + health);
            }

            @Override
            public void food(UUID who, int food) {
                world.get(who).food = food;
                did.add("food=" + food);
            }

            @Override
            public void effect(UUID who, String effect, int level, Duration lasting) {
                world.get(who).effects.put(effect, level);
                did.add("effect:" + effect + "=" + level
                        + (lasting == null ? ":forever" : ":" + lasting.toSeconds() + "s"));
            }

            @Override
            public void clearEffect(UUID who, String effect) {
                world.get(who).effects.remove(effect);
                did.add("clear:" + effect);
            }

            @Override
            public void clearAllEffects(UUID who) {
                world.get(who).effects.clear();
                did.add("clear:*");
            }

            @Override
            public void allowFlight(UUID who, boolean allowed) {
                world.get(who).flying = allowed;
                did.add("flight=" + allowed);
            }

            @Override
            public void gamemode(UUID who, String mode) {
                world.get(who).gamemode = mode;
                did.add("gamemode=" + mode);
            }

            @Override
            public void kick(UUID who, String reason) {
                world.get(who).online = false;
                did.add("kick:" + reason);
            }

            @Override
            public void extinguish(UUID who) {
                did.add("extinguish");
            }
        });
    }

    // ------------------------------------------------------------------ health

    @Nested
    @DisplayName("health")
    class Health {

        @Test
        @DisplayName("healing fills them up without going over")
        void healing() {
            PlayerAdmin admin = admin();
            world.get(ALICE).health = 4;

            assertThat(admin.heal(ALICE)).isEqualTo(Outcome.DONE);
            assertThat(world.get(ALICE).health).isEqualTo(20);
        }

        @Test
        @DisplayName("healing somebody already full is not an error and does nothing")
        void healingTheHealthy() {
            PlayerAdmin admin = admin();
            assertThat(admin.heal(ALICE)).isEqualTo(Outcome.NOTHING_TO_DO);
            assertThat(did).doesNotContain("health=20.0");
        }

        @Test
        @DisplayName("healing by an amount stops at their maximum rather than throwing")
        void healingByAnAmount() {
            PlayerAdmin admin = admin();
            world.get(ALICE).health = 18;

            admin.heal(ALICE, 10);
            assertThat(world.get(ALICE).health)
                    .as("setHealth above the maximum throws, and a heal button must not")
                    .isEqualTo(20);
        }

        @Test
        @DisplayName("damage that would kill is refused unless it is asked for")
        void damageThatWouldKill() {
            PlayerAdmin admin = admin();
            world.get(ALICE).health = 3;

            assertThat(admin.damage(ALICE, 10))
                    .as("a button labelled 'damage' that kills somebody is a button that lied")
                    .isEqualTo(Outcome.WOULD_KILL);
            assertThat(world.get(ALICE).health).isEqualTo(3);

            assertThat(admin.kill(ALICE)).isEqualTo(Outcome.DONE);
            assertThat(world.get(ALICE).health).isZero();
        }

        @Test
        @DisplayName("ordinary damage takes what it says")
        void ordinaryDamage() {
            PlayerAdmin admin = admin();
            assertThat(admin.damage(ALICE, 5)).isEqualTo(Outcome.DONE);
            assertThat(world.get(ALICE).health).isEqualTo(15);
        }

        @Test
        @DisplayName("damage of nothing is refused rather than pretended")
        void zeroDamage() {
            assertThat(admin().damage(ALICE, 0)).isEqualTo(Outcome.NOTHING_TO_DO);
            assertThat(admin().damage(ALICE, -3)).isEqualTo(Outcome.NOTHING_TO_DO);
        }
    }

    // ------------------------------------------------------------------ food

    @Nested
    @DisplayName("food")
    class Food {

        @Test
        @DisplayName("feeding fills them without going over twenty")
        void feeding() {
            PlayerAdmin admin = admin();
            world.get(ALICE).food = 3;

            assertThat(admin.feed(ALICE)).isEqualTo(Outcome.DONE);
            assertThat(world.get(ALICE).food)
                    .as("setFoodLevel above twenty is silently clamped by some versions and "
                            + "throws on others")
                    .isEqualTo(20);
        }

        @Test
        @DisplayName("starving empties them, and never goes below nothing")
        void starving() {
            PlayerAdmin admin = admin();
            assertThat(admin.starve(ALICE)).isEqualTo(Outcome.DONE);
            assertThat(world.get(ALICE).food).isZero();

            assertThat(admin.starve(ALICE))
                    .as("starving somebody already starving is nothing to do, not an error")
                    .isEqualTo(Outcome.NOTHING_TO_DO);
        }
    }

    // ------------------------------------------------------------------ effects

    @Nested
    @DisplayName("effects")
    class Effects {

        @Test
        @DisplayName("speed replaces whatever speed they had rather than stacking")
        void speedReplaces() {
            PlayerAdmin admin = admin();
            admin.speed(ALICE, 2, Duration.ofMinutes(1));
            admin.speed(ALICE, 4, Duration.ofMinutes(1));

            assertThat(did)
                    .as("two speed effects at once is a player at a speed nobody chose")
                    .contains("clear:SPEED");
            assertThat(world.get(ALICE).effects).containsEntry("SPEED", 4);
        }

        @Test
        @DisplayName("a level of nothing takes the effect away instead of applying level zero")
        void levelZeroClears() {
            PlayerAdmin admin = admin();
            admin.speed(ALICE, 3, Duration.ofMinutes(1));

            assertThat(admin.speed(ALICE, 0, null)).isEqualTo(Outcome.DONE);
            assertThat(world.get(ALICE).effects)
                    .as("amplifier zero is speed I, not no speed; getting this wrong makes an "
                            + "'off' button that speeds somebody up")
                    .doesNotContainKey("SPEED");
        }

        @Test
        @DisplayName("a level beyond what the game takes is refused")
        void absurdLevels() {
            assertThat(admin().speed(ALICE, 500, null)).isEqualTo(Outcome.OUT_OF_RANGE);
        }

        @Test
        @DisplayName("slowness works the same way and is its own effect")
        void slowness() {
            PlayerAdmin admin = admin();
            admin.slowness(ALICE, 2, Duration.ofSeconds(30));
            assertThat(world.get(ALICE).effects).containsEntry("SLOWNESS", 2);
        }

        @Test
        @DisplayName("curing takes everything off")
        void curing() {
            PlayerAdmin admin = admin();
            admin.speed(ALICE, 2, null);
            assertThat(admin.cure(ALICE)).isEqualTo(Outcome.DONE);
            assertThat(world.get(ALICE).effects).isEmpty();
        }

        @Test
        @DisplayName("any effect at all can be given, not only the named ones")
        void anyEffect() {
            PlayerAdmin admin = admin();
            assertThat(admin.give(ALICE, "NIGHT_VISION", 1, Duration.ofMinutes(5)))
                    .isEqualTo(Outcome.DONE);
            assertThat(world.get(ALICE).effects).containsKey("NIGHT_VISION");
        }

        @Test
        @DisplayName("an effect with no name is refused")
        void namelessEffects() {
            assertThat(admin().give(ALICE, " ", 1, null)).isEqualTo(Outcome.NOT_UNDERSTOOD);
        }
    }

    // ------------------------------------------------------------------ the rest

    @Nested
    @DisplayName("everything else")
    class Rest {

        @Test
        @DisplayName("flight can be turned on and off")
        void flight() {
            PlayerAdmin admin = admin();
            assertThat(admin.flight(ALICE, true)).isEqualTo(Outcome.DONE);
            assertThat(world.get(ALICE).flying).isTrue();

            assertThat(admin.flight(ALICE, true))
                    .as("turning on something already on is nothing to do")
                    .isEqualTo(Outcome.NOTHING_TO_DO);
        }

        @Test
        @DisplayName("gamemode can be set, and a nonsense one is refused")
        void gamemode() {
            PlayerAdmin admin = admin();
            assertThat(admin.gamemode(ALICE, "creative")).isEqualTo(Outcome.DONE);
            assertThat(world.get(ALICE).gamemode).isEqualTo("CREATIVE");
            assertThat(admin.gamemode(ALICE, "sandbox")).isEqualTo(Outcome.NOT_UNDERSTOOD);
        }

        @Test
        @DisplayName("kicking says why, because a kick with no reason is just a disconnect")
        void kicking() {
            PlayerAdmin admin = admin();
            assertThat(admin.kick(ALICE, "Please stop")).isEqualTo(Outcome.DONE);
            assertThat(did).contains("kick:Please stop");
        }

        @Test
        @DisplayName("a kick with no reason given still says something")
        void kickWithoutAReason() {
            PlayerAdmin admin = admin();
            admin.kick(ALICE, "  ");
            assertThat(did.getLast())
                    .as("a blank disconnect screen tells a player nothing at all")
                    .isNotEqualTo("kick:  ");
        }

        @Test
        @DisplayName("fire can be put out")
        void extinguishing() {
            assertThat(admin().extinguish(ALICE)).isEqualTo(Outcome.DONE);
            assertThat(did).contains("extinguish");
        }
    }

    // ------------------------------------------------------------------ somebody who is not there

    @Nested
    @DisplayName("somebody who is not online")
    class Absent {

        @Test
        @DisplayName("every action answers that they are gone rather than throwing")
        void everythingIsSafe() {
            PlayerAdmin admin = admin();

            assertThat(admin.heal(GONE)).isEqualTo(Outcome.NOT_ONLINE);
            assertThat(admin.feed(GONE)).isEqualTo(Outcome.NOT_ONLINE);
            assertThat(admin.starve(GONE)).isEqualTo(Outcome.NOT_ONLINE);
            assertThat(admin.damage(GONE, 5)).isEqualTo(Outcome.NOT_ONLINE);
            assertThat(admin.kill(GONE)).isEqualTo(Outcome.NOT_ONLINE);
            assertThat(admin.speed(GONE, 2, null)).isEqualTo(Outcome.NOT_ONLINE);
            assertThat(admin.flight(GONE, true)).isEqualTo(Outcome.NOT_ONLINE);
            assertThat(admin.gamemode(GONE, "creative")).isEqualTo(Outcome.NOT_ONLINE);
            assertThat(admin.kick(GONE, "bye")).isEqualTo(Outcome.NOT_ONLINE);
            assertThat(admin.cure(GONE)).isEqualTo(Outcome.NOT_ONLINE);
            assertThat(did)
                    .as("a moderator clicking a button on somebody who logged out a second ago "
                            + "should see a sentence, not a stack trace")
                    .isEmpty();
        }

        @Test
        @DisplayName("a null player is refused the same way")
        void nobodyAtAll() {
            assertThat(admin().heal(null)).isEqualTo(Outcome.NOT_ONLINE);
        }
    }
}
