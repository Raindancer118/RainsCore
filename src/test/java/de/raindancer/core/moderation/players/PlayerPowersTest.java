package de.raindancer.core.moderation.players;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who cannot be hurt, and who hurts everything in one hit.
 *
 * <h2>Why these are here rather than in a moderation plugin</h2>
 * Because they are answers to a damage event, and there must be exactly one plugin on the server
 * deciding what a damage event means. {@code Combat} already says so in its own class note: two plugins
 * listening at their own priority is how one of them silently loses. So the state lives beside
 * {@link PlayerAdmin} — which already owns healing, feeding, effects and flight — and the listener sits
 * where {@code CombatListener} can be ordered against it.
 *
 * <h2>Why neither survives a restart</h2>
 * A hidden moderator who forgets they are hidden is a small problem, and vanish deliberately survives.
 * An <em>invincible</em> player who forgets, and whom nobody remembers granting it to, is a different
 * thing: it is indistinguishable from a bug in the damage system, and somebody will spend an evening
 * looking for one. Retyping {@code /god} after a restart costs four seconds.
 */
class PlayerPowersTest {

    private final UUID ayla = UUID.randomUUID();
    private final UUID bram = UUID.randomUUID();

    @Nested
    @DisplayName("god mode")
    class God {

        @Test
        @DisplayName("nobody is invincible until somebody says so")
        void offByDefault() {
            PlayerPowers powers = new PlayerPowers();

            assertThat(powers.isInvulnerable(ayla)).isFalse();
            assertThat(powers.isInvulnerable(null)).isFalse();
            assertThat(powers.invulnerable()).isEmpty();
        }

        @Test
        @DisplayName("it can be switched on and off")
        void toggled() {
            PlayerPowers powers = new PlayerPowers();

            assertThat(powers.god(ayla, true)).isTrue();
            assertThat(powers.isInvulnerable(ayla)).isTrue();

            assertThat(powers.god(ayla, false)).isTrue();
            assertThat(powers.isInvulnerable(ayla)).isFalse();
        }

        @Test
        @DisplayName("switching it to what it already is changes nothing, and says so")
        void idempotent() {
            PlayerPowers powers = new PlayerPowers();
            powers.god(ayla, true);

            assertThat(powers.god(ayla, true))
                    .as("the caller wants to know whether to announce anything")
                    .isFalse();
        }

        @Test
        @DisplayName("toggling answers what they are now")
        void toggling() {
            PlayerPowers powers = new PlayerPowers();

            assertThat(powers.toggleGod(ayla)).isTrue();
            assertThat(powers.toggleGod(ayla)).isFalse();
        }

        @Test
        @DisplayName("one person's invulnerability is not another's")
        void notShared() {
            PlayerPowers powers = new PlayerPowers();

            powers.god(ayla, true);

            assertThat(powers.isInvulnerable(bram)).isFalse();
        }

        @Test
        @DisplayName("a null id is ignored rather than stored")
        void nulls() {
            PlayerPowers powers = new PlayerPowers();

            assertThat(powers.god(null, true)).isFalse();
            assertThat(powers.invulnerable()).isEmpty();
        }
    }

    @Nested
    @DisplayName("instakill")
    class Instakill {

        @Test
        @DisplayName("off by default, and toggled the same way")
        void toggled() {
            PlayerPowers powers = new PlayerPowers();

            assertThat(powers.killsInOneHit(ayla)).isFalse();
            assertThat(powers.toggleInstakill(ayla)).isTrue();
            assertThat(powers.killsInOneHit(ayla)).isTrue();
            assertThat(powers.toggleInstakill(ayla)).isFalse();
        }

        @Test
        @DisplayName("it is separate from god mode")
        void separate() {
            // Two different powers: an admin who wants to survive a fall while testing does not thereby
            // want to one-shot the next cow they look at.
            PlayerPowers powers = new PlayerPowers();

            powers.god(ayla, true);

            assertThat(powers.killsInOneHit(ayla)).isFalse();
        }

        @Test
        @DisplayName("how much damage it means")
        void theDamage() {
            // Enough to kill anything the game has, and finite: Double.MAX_VALUE overflows some damage
            // calculations into a negative and heals the target instead, which is a genuinely confusing
            // way for this to fail.
            assertThat(PlayerPowers.INSTAKILL_DAMAGE).isGreaterThan(2048.0);
            assertThat(PlayerPowers.INSTAKILL_DAMAGE).isFinite();
        }
    }

    @Nested
    @DisplayName("forgetting somebody")
    class Forgetting {

        @Test
        @DisplayName("leaving takes both away")
        void bothGo() {
            // Not tidiness. Held across a session, an invincible player who logged off is an invincible
            // player nobody remembers granting it to — indistinguishable from a bug in the damage
            // system, and somebody will spend an evening looking for one.
            PlayerPowers powers = new PlayerPowers();
            powers.god(ayla, true);
            powers.toggleInstakill(ayla);

            powers.forget(ayla);

            assertThat(powers.isInvulnerable(ayla)).isFalse();
            assertThat(powers.killsInOneHit(ayla)).isFalse();
        }

        @Test
        @DisplayName("forgetting somebody who had neither is harmless")
        void nothingToForget() {
            PlayerPowers powers = new PlayerPowers();

            powers.forget(ayla);
            powers.forget(null);

            assertThat(powers.invulnerable()).isEmpty();
        }

        @Test
        @DisplayName("everything can be dropped at once, for a shutdown")
        void everything() {
            PlayerPowers powers = new PlayerPowers();
            powers.god(ayla, true);
            powers.god(bram, true);
            powers.toggleInstakill(ayla);

            assertThat(powers.forgetEverybody()).isEqualTo(2);
            assertThat(powers.invulnerable()).isEmpty();
            assertThat(powers.killsInOneHit(ayla)).isFalse();
        }
    }

    @Test
    @DisplayName("the sets handed out cannot be used to change what is held")
    void listsAreCopies() {
        PlayerPowers powers = new PlayerPowers();
        powers.god(ayla, true);

        assertThat(powers.invulnerable()).isUnmodifiable();
    }

    @Test
    @DisplayName("it can be read while it is being written")
    void safeFromAnyThread() throws InterruptedException {
        // Read from a damage event, which fires on whichever region thread the fight is happening on;
        // written from a command. Never the same thread twice, and on Folia not even the same tick.
        PlayerPowers powers = new PlayerPowers();
        AtomicReference<Throwable> broke = new AtomicReference<>();
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        pool.execute(() -> {
            try {
                go.await();
                for (int i = 0; i < 2000; i++) {
                    powers.toggleGod(ayla);
                    powers.toggleInstakill(bram);
                }
            } catch (Throwable failed) {
                broke.compareAndSet(null, failed);
            } finally {
                done.countDown();
            }
        });
        pool.execute(() -> {
            try {
                go.await();
                for (int i = 0; i < 2000; i++) {
                    powers.isInvulnerable(ayla);
                    powers.killsInOneHit(bram);
                    powers.invulnerable();
                }
            } catch (Throwable failed) {
                broke.compareAndSet(null, failed);
            } finally {
                done.countDown();
            }
        });
        go.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        if (broke.get() != null) {
            throw new AssertionError("a concurrent read of the powers threw", broke.get());
        }
    }
}
