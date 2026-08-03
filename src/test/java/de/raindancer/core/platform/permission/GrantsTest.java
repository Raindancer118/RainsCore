package de.raindancer.core.platform.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Permissions this server has granted somebody, remembered.
 *
 * <h2>Why this is in Core rather than in whichever plugin needed it first</h2>
 * Because the plugin that needed it first was a moderation module handing out presets that include
 * <em>land claim</em> permissions. A grant store living inside the moderation module would be a
 * moderation module that the claims module depends on — which is the cycle the whole module
 * arrangement exists to prevent. "If two plugins could want it, it is not module code", and two
 * plugins wanted this before it was even written.
 *
 * <h2>What it is not</h2>
 * Not a permissions plugin. It has no groups, no inheritance, no contexts and no wildcards, and it
 * should never grow them — LuckPerms exists and is better at all of that. This is the small thing a
 * server without one needs: a list of nodes granted to a named person, applied when they join, and
 * still there after a restart.
 *
 * <p>Which is exactly why every question below is asked without a server. The applying needs Bukkit;
 * the remembering, the merging and the persisting are ordinary code, and that is the half that goes
 * wrong.
 */
class GrantsTest {

    private final UUID ayla = UUID.randomUUID();
    private final UUID bram = UUID.randomUUID();

    @Nested
    @DisplayName("granting")
    class Granting {

        @Test
        @DisplayName("a granted node is held")
        void granted(@TempDir Path folder) {
            Grants grants = new Grants(folder);

            grants.grant(ayla, "rains.moderation.mute");

            assertThat(grants.has(ayla, "rains.moderation.mute")).isTrue();
            assertThat(grants.nodesFor(ayla)).containsExactly("rains.moderation.mute");
        }

        @Test
        @DisplayName("a node nobody granted is not held")
        void notGranted(@TempDir Path folder) {
            Grants grants = new Grants(folder);

            assertThat(grants.has(ayla, "rains.moderation.ban")).isFalse();
            assertThat(grants.has(null, "rains.moderation.ban")).isFalse();
            assertThat(grants.has(ayla, null)).isFalse();
            assertThat(grants.nodesFor(ayla)).isEmpty();
            assertThat(grants.nodesFor(null)).isEmpty();
        }

        @Test
        @DisplayName("granting twice is granting once")
        void idempotent(@TempDir Path folder) {
            Grants grants = new Grants(folder);

            grants.grant(ayla, "rains.moderation.mute");
            grants.grant(ayla, "rains.moderation.mute");

            assertThat(grants.nodesFor(ayla)).hasSize(1);
        }

        @Test
        @DisplayName("a node can be taken back")
        void revoked(@TempDir Path folder) {
            Grants grants = new Grants(folder);
            grants.grant(ayla, "rains.moderation.mute");

            assertThat(grants.revoke(ayla, "rains.moderation.mute")).isTrue();
            assertThat(grants.has(ayla, "rains.moderation.mute")).isFalse();
            assertThat(grants.revoke(ayla, "rains.moderation.mute"))
                    .as("taking back what was not granted changed nothing, and should say so")
                    .isFalse();
        }

        @Test
        @DisplayName("one person's grants are not another's")
        void notMixedUp(@TempDir Path folder) {
            Grants grants = new Grants(folder);

            grants.grant(ayla, "rains.moderation.mute");

            assertThat(grants.has(bram, "rains.moderation.mute")).isFalse();
        }

        @Test
        @DisplayName("a blank node is refused rather than stored")
        void blankNodes(@TempDir Path folder) {
            Grants grants = new Grants(folder);

            grants.grant(ayla, "   ");
            grants.grant(ayla, null);

            assertThat(grants.nodesFor(ayla)).isEmpty();
        }

        @Test
        @DisplayName("a node is stored as it was written, because Bukkit matches it literally")
        void nodesAreNotNormalised(@TempDir Path folder) {
            // Deliberately not lower-cased. Bukkit compares permission strings literally, so
            // "helpfully" changing the case here would silently grant a node that does not exist.
            Grants grants = new Grants(folder);

            grants.grant(ayla, "Rains.Moderation.Mute");

            assertThat(grants.nodesFor(ayla)).containsExactly("Rains.Moderation.Mute");
            assertThat(grants.has(ayla, "rains.moderation.mute")).isFalse();
        }
    }

    @Nested
    @DisplayName("setting a whole set at once")
    class Replacing {

        @Test
        @DisplayName("a set replaces what was there")
        void replaced(@TempDir Path folder) {
            // What applying a preset does. Replacing rather than adding is the point: somebody moved
            // from Moderator down to Helper must *lose* what Helper does not have, and the version
            // that only added would have made every demotion a no-op.
            Grants grants = new Grants(folder);
            grants.grant(ayla, "rains.moderation.ban");

            grants.set(ayla, List.of("rains.moderation.mute", "rains.moderation.kick"));

            assertThat(grants.nodesFor(ayla))
                    .containsExactlyInAnyOrder("rains.moderation.mute", "rains.moderation.kick");
            assertThat(grants.has(ayla, "rains.moderation.ban")).isFalse();
        }

        @Test
        @DisplayName("an empty set takes everything away, and leaves no entry behind")
        void emptied(@TempDir Path folder) {
            // The map must not keep an empty entry per player who was ever staff — the same leak a
            // listener that never forgets a player has.
            Grants grants = new Grants(folder);
            grants.grant(ayla, "rains.moderation.mute");

            grants.set(ayla, List.of());

            assertThat(grants.nodesFor(ayla)).isEmpty();
            assertThat(grants.everybody()).isEmpty();
        }

        @Test
        @DisplayName("everybody with anything granted can be listed")
        void listed(@TempDir Path folder) {
            Grants grants = new Grants(folder);
            grants.grant(ayla, "rains.moderation.mute");
            grants.grant(bram, "rains.moderation.ban");

            assertThat(grants.everybody()).containsExactlyInAnyOrder(ayla, bram);
        }

        @Test
        @DisplayName("the set handed out is a copy")
        void listsAreCopies(@TempDir Path folder) {
            Grants grants = new Grants(folder);
            grants.grant(ayla, "rains.moderation.mute");

            grants.nodesFor(ayla).clear();

            assertThat(grants.nodesFor(ayla)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("across a restart")
    class Persisting {

        @Test
        @DisplayName("grants survive being written and read")
        void aRoundTrip(@TempDir Path folder) {
            // The whole reason this is a store and not a map. A permission that vanishes on restart is
            // a moderator who cannot work until somebody notices and promotes them again.
            Grants first = new Grants(folder);
            first.set(ayla, List.of("rains.moderation.mute", "rec.admin"));
            first.flush();

            Grants afterRestart = new Grants(folder);
            afterRestart.load();

            assertThat(afterRestart.nodesFor(ayla))
                    .containsExactlyInAnyOrder("rains.moderation.mute", "rec.admin");
        }

        @Test
        @DisplayName("a revoked node does not come back")
        void revocationPersists(@TempDir Path folder) {
            Grants first = new Grants(folder);
            first.grant(ayla, "rains.moderation.ban");
            first.flush();
            first.revoke(ayla, "rains.moderation.ban");
            first.flush();

            Grants afterRestart = new Grants(folder);
            afterRestart.load();

            assertThat(afterRestart.has(ayla, "rains.moderation.ban")).isFalse();
        }

        @Test
        @DisplayName("nothing on disk is nobody granted anything rather than a failure")
        void nothingYet(@TempDir Path folder) {
            Grants grants = new Grants(folder);
            grants.load();

            assertThat(grants.everybody()).isEmpty();
        }

        @Test
        @DisplayName("an entry that is not a player id is skipped and the rest still load")
        void oneBadEntry(@TempDir Path folder) throws Exception {
            Grants first = new Grants(folder);
            first.grant(ayla, "rains.moderation.mute");
            first.flush();

            String yaml = java.nio.file.Files.readString(first.file());
            java.nio.file.Files.writeString(first.file(),
                    yaml + System.lineSeparator() + "  not-a-uuid:" + System.lineSeparator()
                            + "  - rains.moderation.ban" + System.lineSeparator());

            Grants afterRestart = new Grants(folder);
            afterRestart.load();

            assertThat(afterRestart.has(ayla, "rains.moderation.mute"))
                    .as("one unreadable id must not cost the server everybody else's permissions")
                    .isTrue();
        }

        @Test
        @DisplayName("loading twice does not double anything")
        void loadIsNotCumulative(@TempDir Path folder) {
            Grants first = new Grants(folder);
            first.grant(ayla, "rains.moderation.mute");
            first.flush();

            Grants afterRestart = new Grants(folder);
            afterRestart.load();
            afterRestart.load();

            assertThat(afterRestart.nodesFor(ayla)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("what the attachment is told")
    class Applying {

        @Test
        @DisplayName("everything granted is handed over, once each")
        void handedOver(@TempDir Path folder) {
            // The one thing the applying half does that is worth testing without a server: which nodes
            // it would set. The PermissionAttachment itself is Bukkit's and needs one.
            Grants grants = new Grants(folder);
            grants.set(ayla, List.of("rains.moderation.mute", "rec.admin"));

            Map<String, Boolean> applied = new java.util.LinkedHashMap<>();
            grants.applyTo(ayla, applied::put);

            assertThat(applied).containsOnlyKeys("rains.moderation.mute", "rec.admin");
            assertThat(applied.values()).containsOnly(true);
        }

        @Test
        @DisplayName("somebody with nothing granted has nothing applied")
        void nothingToApply(@TempDir Path folder) {
            Grants grants = new Grants(folder);

            Map<String, Boolean> applied = new java.util.LinkedHashMap<>();
            grants.applyTo(ayla, applied::put);
            grants.applyTo(null, applied::put);

            assertThat(applied).isEmpty();
        }
    }

    @Test
    @DisplayName("grants can be read while they are being changed")
    void safeFromAnyThread(@TempDir Path folder) throws InterruptedException {
        // Read from a permission check, which happens on every command and inside render loops;
        // written from a promote command and from a menu click. Never the same thread twice.
        Grants grants = new Grants(folder);
        AtomicReference<Throwable> broke = new AtomicReference<>();
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        pool.execute(() -> {
            try {
                go.await();
                for (int i = 0; i < 500; i++) {
                    grants.grant(ayla, "node." + i);
                    grants.revoke(ayla, "node." + (i - 1));
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
                for (int i = 0; i < 500; i++) {
                    grants.has(ayla, "node.5");
                    grants.nodesFor(ayla).forEach(node -> assertThat(node).isNotBlank());
                    grants.everybody();
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
            throw new AssertionError("a concurrent read of the grants threw", broke.get());
        }
    }
}
