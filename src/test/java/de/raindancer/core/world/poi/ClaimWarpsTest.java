package de.raindancer.core.world.poi;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A claim's warp point and a player's main home: written by the claims plugin, read by the warps plugin,
 * kept in Core's place store so neither has to know the other is installed.
 */
class ClaimWarpsTest {

    private static final UUID LILLY = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BEN = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    Path directory;

    private Database database;
    private PoiStore places;
    private ClaimWarps warps;

    @BeforeEach
    void setUp() {
        database = Database.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        places = new PoiStore(database);
        warps = new ClaimWarps(places);
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    @DisplayName("a claim has one warp point: setting it again moves it, keeping it the main home if it was")
    void onePerClaim() {
        warps.set("c1", "Lilly's Farm", LILLY, "world", 1, 64, 1, 0, 0);
        warps.markMain(LILLY, "c1");
        warps.set("c1", "Lilly's Farm", LILLY, "world", 9, 70, 9, 90, 0);

        assertThat(warps.all()).hasSize(1);
        Poi point = warps.forClaim("c1").orElseThrow();
        assertThat(point.x()).isEqualTo(9);
        assertThat(ClaimWarps.isMain(point)).isTrue();
        assertThat(ClaimWarps.claimOf(point)).isEqualTo("c1");
    }

    @Test
    @DisplayName("a player has at most one main home, and only among their own claims")
    void oneMainHome() {
        warps.set("c1", "Farm", LILLY, "world", 1, 64, 1, 0, 0);
        warps.set("c2", "Tower", LILLY, "world", 2, 64, 2, 0, 0);
        warps.set("c3", "Ben's", BEN, "world", 3, 64, 3, 0, 0);

        assertThat(warps.markMain(LILLY, "c1")).isTrue();
        assertThat(warps.markMain(LILLY, "c2")).isTrue();
        assertThat(warps.markMain(LILLY, "c3")).as("Ben's claim is not hers to make her home").isFalse();
        assertThat(warps.markMain(LILLY, "nope")).as("a claim with no warp point").isFalse();

        assertThat(warps.mainOf(LILLY).map(ClaimWarps::claimOf)).contains("c2");
        assertThat(warps.ownedBy(LILLY)).hasSize(2);
        assertThat(warps.mainOf(BEN)).isEmpty();
    }

    @Test
    @DisplayName("renaming the claim renames its warp; a new owner starts without it as their main home")
    void followsTheClaim() {
        warps.set("c1", "Farm", LILLY, "world", 1, 64, 1, 0, 0);
        warps.markMain(LILLY, "c1");

        warps.rename("c1", "Big Farm");
        warps.reassign("c1", BEN);

        Poi point = warps.forClaim("c1").orElseThrow();
        assertThat(point.name()).isEqualTo("Big Farm");
        assertThat(point.owner()).isEqualTo(BEN);
        assertThat(ClaimWarps.isMain(point)).isFalse();
        assertThat(warps.mainOf(LILLY)).isEmpty();
    }

    @Test
    @DisplayName("removing it — or the claim going — takes the warp and the main home with it")
    void removing() {
        warps.set("c1", "Farm", LILLY, "world", 1, 64, 1, 0, 0);
        warps.markMain(LILLY, "c1");

        assertThat(warps.remove("c1")).isTrue();
        assertThat(warps.forClaim("c1")).isEmpty();
        assertThat(warps.mainOf(LILLY)).isEmpty();
        assertThat(warps.remove("c1")).isFalse();
    }

    @Test
    @DisplayName("they are not homes or warps: other kinds of place are left alone")
    void ownKind() {
        places.save(Poi.builder("home", "world", 0, 64, 0).kind("home").owner(LILLY).build());
        warps.set("c1", "Farm", LILLY, "world", 1, 64, 1, 0, 0);

        assertThat(warps.all()).extracting(Poi::kind).containsOnly(ClaimWarps.KIND);
    }
}
