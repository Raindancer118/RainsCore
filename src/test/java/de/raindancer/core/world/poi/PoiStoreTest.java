package de.raindancer.core.world.poi;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * One place, remembered.
 *
 * <h2>Why there is one of these instead of three</h2>
 * {@code Home}, {@code Destination} and {@code Waypoint} were three records in three plugins with
 * the same five fields, the same "the world is a name, not a World" note in their javadoc — copied
 * word for word between them — and three separate YAML stores behind them with three separate
 * answers to what happens when a world is missing. This is the one of them, so a place saved by any
 * plugin can be listed, teleported to, or drawn on a map by any other.
 *
 * <h2>Why the world is a name and not a World</h2>
 * A saved place outlives the server it was saved on. Holding the world object would keep an unloaded
 * world in the heap, and — worse — a place in a world that is not loaded <em>right now</em> would
 * have to be thrown away at load time rather than being unreachable until that world comes back. A
 * multiverse server that unloads a world for maintenance would silently lose every home in it.
 */
class PoiStoreTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    @TempDir
    Path directory;
    private PoiStore store;

    @BeforeEach
    void setUp() {
        store = new PoiStore(directory.resolve("places.yml"));
    }

    private static Poi home(String name) {
        return Poi.builder(name, "world", 120.5, 64, -310.25)
                .owner(ALICE)
                .kind("home")
                .build();
    }

    // ------------------------------------------------------------------ keeping places

    @Nested
    @DisplayName("saving a place")
    class Saving {

        @Test
        @DisplayName("it can be found again by its id")
        void savesAndFinds() {
            Poi base = home("base");
            store.save(base);
            assertThat(store.byId(base.id())).contains(base);
        }

        @Test
        @DisplayName("everything about it survives being saved")
        void keepsEveryField() {
            Poi full = Poi.builder("market", "nether", 1, 2, 3)
                    .owner(ALICE)
                    .kind("stop")
                    .icon(Material.BELL)
                    .facing(90f, -12f)
                    .label("The market")
                    .shared(true)
                    .tag("line", "north")
                    .build();
            store.save(full);

            Poi read = store.byId(full.id()).orElseThrow();
            assertThat(read.name()).isEqualTo("market");
            assertThat(read.world()).isEqualTo("nether");
            assertThat(read.x()).isEqualTo(1);
            assertThat(read.yaw()).isEqualTo(90f);
            assertThat(read.pitch()).isEqualTo(-12f);
            assertThat(read.icon()).isEqualTo(Material.BELL);
            assertThat(read.label()).isEqualTo("The market");
            assertThat(read.isShared()).isTrue();
            assertThat(read.tag("line")).contains("north");
        }

        @Test
        @DisplayName("saving the same id again replaces it rather than making a second")
        void replacesOnSave() {
            Poi base = home("base");
            store.save(base);
            store.save(base.movedTo("world", 1, 2, 3));

            assertThat(store.all()).hasSize(1);
            assertThat(store.byId(base.id()).orElseThrow().x()).isEqualTo(1);
        }

        @Test
        @DisplayName("two places can share a name if they belong to different people")
        void namesAreOnlyUniquePerOwner() {
            store.save(home("base"));
            store.save(Poi.builder("base", "world", 0, 0, 0).owner(BOB).kind("home").build());

            assertThat(store.all()).hasSize(2);
            assertThat(store.named(ALICE, "home", "base")).isPresent();
            assertThat(store.named(BOB, "home", "base")).isPresent();
        }

        @Test
        @DisplayName("a name is matched however it was capitalised")
        void namesAreCaseInsensitive() {
            store.save(home("Base"));
            assertThat(store.named(ALICE, "home", "BASE")).isPresent();
            assertThat(store.named(ALICE, "home", "base")).isPresent();
        }

        @Test
        @DisplayName("deleting takes it away")
        void deletes() {
            Poi base = home("base");
            store.save(base);
            assertThat(store.delete(base.id())).isTrue();
            assertThat(store.byId(base.id())).isEmpty();
            assertThat(store.delete(base.id()))
                    .as("deleting twice says so rather than pretending it worked")
                    .isFalse();
        }
    }

    // ------------------------------------------------------------------ finding places

    @Nested
    @DisplayName("finding places")
    class Finding {

        @BeforeEach
        void fill() {
            store.save(home("base"));
            store.save(home("mine"));
            store.save(Poi.builder("shop", "world", 0, 0, 0).owner(BOB).kind("home").build());
            store.save(Poi.builder("north", "world", 0, 0, 0).owner(ALICE).kind("stop")
                    .shared(true).build());
        }

        @Test
        @DisplayName("everything one person owns, of one kind")
        void findsByOwnerAndKind() {
            assertThat(store.owned(ALICE, "home")).extracting(Poi::name)
                    .containsExactlyInAnyOrder("base", "mine");
        }

        @Test
        @DisplayName("everything one person owns, whatever kind")
        void findsEverythingOwned() {
            assertThat(store.owned(ALICE)).hasSize(3);
        }

        @Test
        @DisplayName("everything of one kind, whoever owns it")
        void findsByKind() {
            assertThat(store.ofKind("home")).hasSize(3);
        }

        @Test
        @DisplayName("only the ones their owner has shared")
        void findsSharedOnes() {
            assertThat(store.shared()).extracting(Poi::name).containsExactly("north");
        }

        @Test
        @DisplayName("everything in a world, so a world being removed can be dealt with")
        void findsByWorld() {
            assertThat(store.inWorld("world")).hasSize(4);
            assertThat(store.inWorld("nether")).isEmpty();
        }

        @Test
        @DisplayName("how many one person has, for a limit")
        void counts() {
            assertThat(store.count(ALICE, "home")).isEqualTo(2);
            assertThat(store.count(BOB, "home")).isEqualTo(1);
        }

        @Test
        @DisplayName("asking for something that is not there is empty, not an exception")
        void missingIsEmpty() {
            assertThat(store.byId("nope")).isEmpty();
            assertThat(store.named(ALICE, "home", "nowhere")).isEmpty();
            assertThat(store.owned(null)).isEmpty();
            assertThat(store.ofKind(null)).isEmpty();
        }
    }

    // ------------------------------------------------------------------ persistence

    @Nested
    @DisplayName("across a restart")
    class Persistence {

        @Test
        @DisplayName("everything saved is still there")
        void roundTrips() {
            Poi base = home("base");
            Poi market = Poi.builder("market", "nether", 1, 2, 3).owner(BOB).kind("stop")
                    .icon(Material.BELL).label("The market").shared(true).tag("line", "north")
                    .build();
            store.save(base);
            store.save(market);
            store.flush();

            PoiStore reopened = new PoiStore(directory.resolve("places.yml"));
            reopened.load();

            assertThat(reopened.all()).hasSize(2);
            assertThat(reopened.byId(base.id())).contains(base);
            assertThat(reopened.byId(market.id())).contains(market);
        }

        @Test
        @DisplayName("a file that was never written loads as empty rather than failing")
        void survivesAMissingFile() {
            PoiStore fresh = new PoiStore(directory.resolve("never-written.yml"));
            assertThatCode(fresh::load).doesNotThrowAnyException();
            assertThat(fresh.all()).isEmpty();
        }

        @Test
        @DisplayName("one unreadable entry is skipped and the rest still load")
        void skipsBadEntries() throws Exception {
            java.nio.file.Files.writeString(directory.resolve("places.yml"), """
                    places:
                      good:
                        name: base
                        world: world
                        x: 1.0
                        y: 2.0
                        z: 3.0
                        owner: %s
                        kind: home
                      broken:
                        name: nowhere
                    """.formatted(ALICE));

            PoiStore reopened = new PoiStore(directory.resolve("places.yml"));
            reopened.load();

            assertThat(reopened.all()).extracting(Poi::name).containsExactly("base");
            assertThat(reopened.problems())
                    .as("a skipped entry must be reported, not silently dropped")
                    .hasSize(1);
        }

        @Test
        @DisplayName("a place whose world has gone is kept, not thrown away")
        void keepsPlacesInMissingWorlds() throws Exception {
            store.save(Poi.builder("old", "a-world-that-was-deleted", 1, 2, 3)
                    .owner(ALICE).kind("home").build());
            store.flush();

            PoiStore reopened = new PoiStore(directory.resolve("places.yml"));
            reopened.load();

            assertThat(reopened.all())
                    .as("a world unloaded for maintenance must not cost every home in it")
                    .hasSize(1);
        }

        @Test
        @DisplayName("nothing is written until something changed")
        void doesNotWriteWithoutChanges() {
            store.load();
            assertThat(store.isDirty()).isFalse();
            store.flush();
            assertThat(directory.resolve("places.yml")).doesNotExist();

            store.save(home("base"));
            assertThat(store.isDirty()).isTrue();
            store.flush();
            assertThat(directory.resolve("places.yml")).exists();
            assertThat(store.isDirty()).isFalse();
        }
    }

    // ------------------------------------------------------------------ the value itself

    @Nested
    @DisplayName("a place")
    class TheValue {

        @Test
        @DisplayName("reads its coordinates the way a person would")
        void readsItsCoordinates() {
            assertThat(home("base").coordinates()).isEqualTo("121, 64, -310");
        }

        @Test
        @DisplayName("can be renamed and moved without losing its identity")
        void keepsItsIdThroughChanges() {
            Poi base = home("base");
            assertThat(base.renamedTo("home").id()).isEqualTo(base.id());
            assertThat(base.movedTo("nether", 1, 2, 3).id()).isEqualTo(base.id());
            assertThat(base.renamedTo("home").name()).isEqualTo("home");
        }

        @Test
        @DisplayName("two places are never given the same id")
        void idsAreUnique() {
            assertThat(home("base").id()).isNotEqualTo(home("base").id());
        }

        @Test
        @DisplayName("needs a name, a world and an owner")
        void refusesNonsense() {
            assertThatCode(() -> Poi.builder(null, "world", 0, 0, 0).build())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> Poi.builder("x", null, 0, 0, 0).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a label falls back to the name, so something is always shown")
        void labelFallsBackToName() {
            assertThat(home("base").label()).isEqualTo("base");
        }
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    @DisplayName("saving from many threads at once loses nothing")
    void isSafeFromEveryThread() throws Exception {
        int threads = 8;
        int each = 100;
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int thread = 0; thread < threads; thread++) {
                int id = thread;
                pool.submit(() -> {
                    go.await();
                    for (int index = 0; index < each; index++) {
                        store.save(Poi.builder("place-" + id + "-" + index, "world", 0, 0, 0)
                                .owner(ALICE).kind("home").build());
                    }
                    return null;
                });
            }
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(store.all()).hasSize(threads * each);
        assertThat(store.count(ALICE, "home")).isEqualTo(threads * each);
    }

    @Test
    @DisplayName("the list handed back cannot be used to change the store behind its back")
    void handsBackCopies() {
        store.save(home("base"));
        List<Poi> all = store.all();
        assertThatCode(() -> all.add(home("sneaky"))).isInstanceOf(UnsupportedOperationException.class);
        assertThat(store.all()).hasSize(1);
    }

    @Test
    @DisplayName("a place can be looked up without the server being asked about worlds")
    void doesNotNeedAServer() {
        // Every assertion in this class has run without a Bukkit server, which is the point: the
        // world is a name until somebody actually asks for a Location.
        Optional<Poi> found = store.byId("nothing");
        assertThat(found).isEmpty();
    }
}
