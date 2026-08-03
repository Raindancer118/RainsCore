package de.raindancer.core.world.warp;

import de.raindancer.core.world.poi.Poi;
import de.raindancer.core.world.poi.PoiStore;
import org.bukkit.Material;
import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Warps.
 *
 * <h2>Why there is no warp store</h2>
 * Because there already is one. A warp is a place with a name, a world and coordinates, which is
 * exactly what {@link Poi} is — so a warp is a POI of kind {@code warp}, and everything about
 * persistence, worlds that are not loaded, atomic writes and "is this reachable" is already solved
 * and already tested. What is left, and what is here, is the part warps actually add: who may use
 * one, what it costs in time, and how they are listed.
 *
 * <p>That reuse is not only tidiness. It means a ghast line can fly somebody to a warp, a menu can
 * list warps beside homes, and a world being deleted takes its warps with it — none of which would
 * work if warps were a second store that happened to look the same.
 */
class WarpsTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private Database openedDatabase;

    /** One database for the test, opened on first use so @TempDir is already there. */
    private Database database() {
        if (openedDatabase == null || !openedDatabase.isUsable()) {
            openedDatabase = Database.open(directory.resolve("core.db"), CoreSchema.CORE,
                    () -> false);
        }
        return openedDatabase;
    }

    @AfterEach
    void closeDatabase() {
        if (openedDatabase != null) {
            openedDatabase.close();
        }
    }

    @TempDir
    Path directory;
    private AtomicLong clock;
    private PoiStore places;
    private Warps warps;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(1_000_000L);
        places = new PoiStore(database());
        places.load();
        // Every world exists except the one the "world is gone" test asks about. Injected rather
        // than asking Bukkit, which is not there.
        warps = new Warps(places, clock::get,
                world -> !"a-world-that-was-deleted".equals(world));
    }

    private void advance(Duration by) {
        clock.addAndGet(by.toMillis());
    }

    // ------------------------------------------------------------------ making them

    @Nested
    @DisplayName("creating a warp")
    class Creating {

        @Test
        @DisplayName("it can be found by name, however it was capitalised")
        void createsAndFinds() {
            warps.create("Spawn", "world", 0, 64, 0, ALICE);
            assertThat(warps.byName("spawn")).isPresent();
            assertThat(warps.byName("SPAWN")).isPresent();
        }

        @Test
        @DisplayName("it is stored as a place, so everything else can already see it")
        void isAPlace() {
            warps.create("spawn", "world", 1, 2, 3, ALICE);
            assertThat(places.ofKind(Warps.KIND))
                    .as("a ghast line flying to a warp is why this is not a second store")
                    .hasSize(1);
        }

        @Test
        @DisplayName("creating one that exists replaces it rather than making a second")
        void replaces() {
            warps.create("spawn", "world", 0, 64, 0, ALICE);
            warps.create("spawn", "world", 100, 64, 100, BOB);

            assertThat(warps.all()).hasSize(1);
            assertThat(warps.byName("spawn").orElseThrow().poi().x()).isEqualTo(100);
        }

        @Test
        @DisplayName("a name with nothing in it is refused")
        void refusesABlankName() {
            assertThat(warps.create(" ", "world", 0, 64, 0, ALICE)).isEmpty();
            assertThat(warps.create(null, "world", 0, 64, 0, ALICE)).isEmpty();
        }

        @Test
        @DisplayName("deleting takes it away")
        void deletes() {
            warps.create("spawn", "world", 0, 64, 0, ALICE);
            assertThat(warps.delete("spawn")).isTrue();
            assertThat(warps.byName("spawn")).isEmpty();
            assertThat(warps.delete("spawn")).isFalse();
        }
    }

    // ------------------------------------------------------------------ who may use one

    @Nested
    @DisplayName("who may use a warp")
    class Access {

        @Test
        @DisplayName("anybody, by default")
        void publicByDefault() {
            Warp warp = warps.create("spawn", "world", 0, 64, 0, ALICE).orElseThrow();
            assertThat(warp.permission()).isEmpty();
            assertThat(warps.visibleTo(BOB, permission -> false)).hasSize(1);
        }

        @Test
        @DisplayName("only those with its permission, when it has one")
        void canBeRestricted() {
            warps.create("staff", "world", 0, 64, 0, ALICE);
            warps.setPermission("staff", "rainscore.warp.staff");

            assertThat(warps.visibleTo(BOB, permission -> false)).isEmpty();
            assertThat(warps.visibleTo(BOB, "rainscore.warp.staff"::equals)).hasSize(1);
        }

        @Test
        @DisplayName("a restricted warp cannot be used by somebody without it")
        void refusesWithoutPermission() {
            warps.create("staff", "world", 0, 64, 0, ALICE);
            warps.setPermission("staff", "rainscore.warp.staff");

            assertThat(warps.mayUse(BOB, "staff", permission -> false)).isFalse();
            assertThat(warps.mayUse(BOB, "staff", "rainscore.warp.staff"::equals)).isTrue();
        }

        @Test
        @DisplayName("a permission can be taken off again")
        void canBeOpenedUp() {
            warps.create("staff", "world", 0, 64, 0, ALICE);
            warps.setPermission("staff", "rainscore.warp.staff");
            warps.setPermission("staff", null);
            assertThat(warps.mayUse(BOB, "staff", permission -> false)).isTrue();
        }
    }

    // ------------------------------------------------------------------ categories

    @Nested
    @DisplayName("categories")
    class Categories {

        @Test
        @DisplayName("a warp can be filed under one, and listed by it")
        void groupsByCategory() {
            warps.create("shop", "world", 0, 64, 0, ALICE);
            warps.create("mine", "world", 0, 64, 0, ALICE);
            warps.setCategory("shop", "town");
            warps.setCategory("mine", "resources");

            assertThat(warps.inCategory("town")).hasSize(1);
            assertThat(warps.categories()).containsExactlyInAnyOrder("town", "resources");
        }

        @Test
        @DisplayName("one with no category is not lost")
        void keepsUncategorised() {
            warps.create("spawn", "world", 0, 64, 0, ALICE);
            assertThat(warps.all()).hasSize(1);
            assertThat(warps.inCategory(null)).hasSize(1);
        }

        @Test
        @DisplayName("an icon can be set, for the menu")
        void carriesAnIcon() {
            warps.create("spawn", "world", 0, 64, 0, ALICE);
            warps.setIcon("spawn", Material.LODESTONE);
            assertThat(warps.byName("spawn").orElseThrow().poi().icon())
                    .isEqualTo(Material.LODESTONE);
        }
    }

    // ------------------------------------------------------------------ cooldowns

    @Nested
    @DisplayName("the cooldown")
    class Cooldowns {

        @BeforeEach
        void aWarpExists() {
            warps.create("spawn", "world", 0, 64, 0, ALICE);
            warps.cooldown(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("the first use is allowed")
        void firstUseIsFree() {
            assertThat(warps.use(BOB, "spawn")).isEqualTo(WarpUse.WENT);
        }

        @Test
        @DisplayName("a second use straight away is not")
        void blocksASecondUse() {
            warps.use(BOB, "spawn");
            assertThat(warps.use(BOB, "spawn")).isEqualTo(WarpUse.ON_COOLDOWN);
            assertThat(warps.remaining(BOB)).contains(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("it wears off")
        void expires() {
            warps.use(BOB, "spawn");
            advance(Duration.ofSeconds(31));
            assertThat(warps.use(BOB, "spawn")).isEqualTo(WarpUse.WENT);
        }

        @Test
        @DisplayName("it is per player")
        void isPerPlayer() {
            warps.use(BOB, "spawn");
            assertThat(warps.use(ALICE, "spawn")).isEqualTo(WarpUse.WENT);
        }

        @Test
        @DisplayName("it is one cooldown for all warps, not one each")
        void isSharedAcrossWarps() {
            warps.create("mine", "world", 0, 64, 0, ALICE);
            warps.use(BOB, "spawn");
            assertThat(warps.use(BOB, "mine"))
                    .as("otherwise hopping between two warps costs nothing at all")
                    .isEqualTo(WarpUse.ON_COOLDOWN);
        }

        @Test
        @DisplayName("no cooldown means no waiting")
        void canBeSwitchedOff() {
            warps.cooldown(null);
            warps.use(BOB, "spawn");
            assertThat(warps.use(BOB, "spawn")).isEqualTo(WarpUse.WENT);
        }

        @Test
        @DisplayName("a player who leaves takes their cooldown with them")
        void forgetsPlayers() {
            warps.use(BOB, "spawn");
            warps.forget(BOB);
            assertThat(warps.remaining(BOB)).isEmpty();
        }
    }

    // ------------------------------------------------------------------ using one

    @Nested
    @DisplayName("using a warp")
    class Using {

        @Test
        @DisplayName("one that does not exist says so")
        void unknownWarp() {
            assertThat(warps.use(BOB, "nowhere")).isEqualTo(WarpUse.UNKNOWN);
        }

        @Test
        @DisplayName("one whose world is gone says that, rather than failing silently")
        void unloadedWorld() {
            warps.create("old", "a-world-that-was-deleted", 0, 64, 0, ALICE);
            assertThat(warps.use(BOB, "old")).isEqualTo(WarpUse.WORLD_MISSING);
        }

        @Test
        @DisplayName("a refused use does not start the cooldown")
        void refusalsAreFree() {
            warps.cooldown(Duration.ofSeconds(30));
            warps.use(BOB, "nowhere");
            warps.create("spawn", "world", 0, 64, 0, ALICE);
            assertThat(warps.use(BOB, "spawn"))
                    .as("a typo must not cost thirty seconds")
                    .isEqualTo(WarpUse.WENT);
        }
    }

    // ------------------------------------------------------------------ persistence

    @Test
    @DisplayName("warps survive a restart, because the place store already did")
    void roundTrips() {
        warps.create("spawn", "world", 1, 2, 3, ALICE);
        warps.setPermission("spawn", "rainscore.warp.spawn");
        warps.setCategory("spawn", "town");
        places.flush();

        // Closed and reopened over the same file, because that is what a restart is: a connection
        // that stayed open would prove only that the in-memory copy is still there.
        openedDatabase.close();
        PoiStore reopened = new PoiStore(database());
        reopened.load();
        Warps again = new Warps(reopened, clock::get, world -> true);

        Warp warp = again.byName("spawn").orElseThrow();
        assertThat(warp.permission()).contains("rainscore.warp.spawn");
        assertThat(warp.category()).contains("town");
        assertThat(warp.poi().x()).isEqualTo(1);
    }

    @Test
    @DisplayName("nulls do not throw")
    void survivesNulls() {
        assertThatCode(() -> {
            warps.byName(null);
            warps.delete(null);
            warps.use(null, null);
            warps.setPermission(null, null);
            warps.forget(null);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("warps are listed in alphabetical order, for a command and a menu")
    void listsInOrder() {
        warps.create("zoo", "world", 0, 64, 0, ALICE);
        warps.create("arena", "world", 0, 64, 0, ALICE);
        assertThat(warps.all()).extracting(warp -> warp.name())
                .containsExactly("arena", "zoo");
        assertThat(warps.names()).containsExactly("arena", "zoo");
    }

    @Test
    @DisplayName("a list of every warp name is offered for tab completion")
    void offersNames() {
        warps.create("spawn", "world", 0, 64, 0, ALICE);
        assertThat(warps.names()).containsExactly("spawn");
    }

    private static List<String> names(List<Warp> warps) {
        return warps.stream().map(Warp::name).toList();
    }
}
