package de.raindancer.core.loot;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Weighted loot: what comes out of a chest, and how often.
 *
 * <h2>Why the randomness is injected</h2>
 * A loot table is nothing but a probability distribution, and "it seemed about right when I opened
 * twenty chests" is not a test. Every roll here goes through a {@link Random} the test supplies, so
 * the weighting can be checked exactly — including the boundaries, which is where a weighted pick is
 * always wrong: the last entry unreachable, or the first one taking a share it should not.
 *
 * <h2>What this is modelled on</h2>
 * {@code TheHungerGames}' loot config: pools of weighted entries per container type, a fill
 * percentage per type, and entries that may be a plain material or one of the custom items. Its
 * shape is right; what it lacks is tiers and any way to test the distribution.
 */
class LootTableTest {

    @TempDir
    Path directory;
    private LootTables tables;

    @BeforeEach
    void setUp() {
        tables = new LootTables(directory.resolve("loot.yml"));
    }

    private static LootTable pool() {
        return LootTable.builder("hg", "chest")
                .fillPercent(30)
                .entry(LootEntry.of(Material.BREAD, 70).amount(1, 3))
                .entry(LootEntry.of(Material.IRON_SWORD, 25))
                .entry(LootEntry.of(Material.DIAMOND_SWORD, 5))
                .build();
    }

    // ------------------------------------------------------------------ weighting

    @Nested
    @DisplayName("picking by weight")
    class Weighting {

        private final LootTable table = pool();

        /**
         * A weighted pick is a cumulative sum, and the bugs are always at the edges: a roll of 0
         * belonging to the first entry, and the highest possible roll still landing inside the last.
         */
        @Test
        @DisplayName("a roll of zero picks the first entry")
        void lowestRoll() {
            assertThat(table.pick(fixed(0)).orElseThrow().material()).isEqualTo(Material.BREAD);
        }

        @Test
        @DisplayName("the highest possible roll picks the last, not nothing")
        void highestRoll() {
            assertThat(table.pick(fixed(99)).orElseThrow().material())
                    .as("an off-by-one here makes the rarest item unobtainable, which nobody "
                            + "notices for months")
                    .isEqualTo(Material.DIAMOND_SWORD);
        }

        @Test
        @DisplayName("each entry owns exactly its share of the range")
        void boundaries() {
            assertThat(table.pick(fixed(69)).orElseThrow().material()).isEqualTo(Material.BREAD);
            assertThat(table.pick(fixed(70)).orElseThrow().material()).isEqualTo(Material.IRON_SWORD);
            assertThat(table.pick(fixed(94)).orElseThrow().material()).isEqualTo(Material.IRON_SWORD);
            assertThat(table.pick(fixed(95)).orElseThrow().material())
                    .isEqualTo(Material.DIAMOND_SWORD);
        }

        @Test
        @DisplayName("over many rolls the shares come out about right")
        void distribution() {
            Map<Material, Integer> counts = new HashMap<>();
            Random random = new Random(1234);
            for (int roll = 0; roll < 10_000; roll++) {
                table.pick(random).ifPresent(entry ->
                        counts.merge(entry.material(), 1, Integer::sum));
            }
            assertThat(counts.get(Material.BREAD)).isBetween(6_700, 7_300);
            assertThat(counts.get(Material.IRON_SWORD)).isBetween(2_200, 2_800);
            assertThat(counts.get(Material.DIAMOND_SWORD)).isBetween(350, 650);
        }

        @Test
        @DisplayName("an empty table gives nothing rather than throwing")
        void emptyTable() {
            LootTable empty = LootTable.builder("hg", "empty").build();
            assertThat(empty.pick(new Random())).isEmpty();
            assertThat(empty.totalWeight()).isZero();
        }

        @Test
        @DisplayName("an entry with no weight is never picked, but does not break the others")
        void zeroWeight() {
            LootTable table = LootTable.builder("hg", "chest")
                    .entry(LootEntry.of(Material.BREAD, 1))
                    .entry(LootEntry.of(Material.BEDROCK, 0))
                    .build();
            for (int roll = 0; roll < 100; roll++) {
                assertThat(table.pick(new Random(roll)).orElseThrow().material())
                        .isEqualTo(Material.BREAD);
            }
        }
    }

    // ------------------------------------------------------------------ amounts

    @Nested
    @DisplayName("how much of it")
    class Amounts {

        @Test
        @DisplayName("a fixed amount is always that")
        void fixedAmount() {
            LootEntry entry = LootEntry.of(Material.BREAD, 1).amount(3, 3);
            assertThat(entry.rollAmount(new Random())).isEqualTo(3);
        }

        @Test
        @DisplayName("a range covers both ends")
        void rangeIncludesBothEnds() {
            LootEntry entry = LootEntry.of(Material.BREAD, 1).amount(1, 3);
            List<Integer> seen = new ArrayList<>();
            Random random = new Random(42);
            for (int roll = 0; roll < 200; roll++) {
                seen.add(entry.rollAmount(random));
            }
            assertThat(seen).contains(1, 2, 3).doesNotContain(0, 4);
        }

        @Test
        @DisplayName("a backwards range is read the way it was obviously meant")
        void backwardsRange() {
            LootEntry entry = LootEntry.of(Material.BREAD, 1).amount(5, 2);
            assertThat(entry.rollAmount(new Random())).isBetween(2, 5);
        }

        @Test
        @DisplayName("nothing ever rolls fewer than one")
        void neverZero() {
            assertThat(LootEntry.of(Material.BREAD, 1).amount(0, 0).rollAmount(new Random()))
                    .isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ filling a container

    @Nested
    @DisplayName("filling a container")
    class Filling {

        @Test
        @DisplayName("about the share of slots the table asks for")
        void fillsItsShare() {
            LootTable table = pool();
            // 27 slots at 30% is 8.
            assertThat(table.slotsToFill(27)).isEqualTo(8);
            assertThat(table.slotsToFill(54)).isEqualTo(16);
        }

        @Test
        @DisplayName("at least one slot, so a chest is never empty")
        void neverEmpty() {
            LootTable stingy = LootTable.builder("hg", "chest").fillPercent(1)
                    .entry(LootEntry.of(Material.BREAD, 1)).build();
            assertThat(stingy.slotsToFill(9))
                    .as("an empty chest reads as a bug, not as bad luck")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("never more slots than the container has")
        void neverOverfills() {
            LootTable greedy = LootTable.builder("hg", "chest").fillPercent(100)
                    .entry(LootEntry.of(Material.BREAD, 1)).build();
            assertThat(greedy.slotsToFill(27)).isEqualTo(27);
        }

        @Test
        @DisplayName("the slots chosen are all different, so nothing is overwritten")
        void picksDistinctSlots() {
            List<Integer> slots = LootTable.chooseSlots(27, 8, new Random(7));
            assertThat(slots).hasSize(8).doesNotHaveDuplicates();
            assertThat(slots).allSatisfy(slot -> assertThat(slot).isBetween(0, 26));
        }

        @Test
        @DisplayName("asking for more slots than exist gives every slot, once")
        void cannotAskForTooMany() {
            assertThat(LootTable.chooseSlots(9, 20, new Random()))
                    .hasSize(9).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("a full roll gives one entry per slot")
        void rollsAWholeContainer() {
            List<LootRoll> rolled = pool().roll(27, new Random(99));
            assertThat(rolled).hasSize(8);
            assertThat(rolled).extracting(LootRoll::slot).doesNotHaveDuplicates();
            assertThat(rolled).allSatisfy(roll ->
                    assertThat(roll.amount()).isGreaterThanOrEqualTo(1));
        }
    }

    // ------------------------------------------------------------------ custom items

    @Nested
    @DisplayName("an entry that is one of our custom items")
    class CustomItems {

        @Test
        @DisplayName("carries the item's key rather than a material")
        void carriesAKey() {
            LootEntry entry = LootEntry.ofCustomItem("hg:medikit", 5);
            assertThat(entry.customItem()).contains("hg:medikit");
            assertThat(entry.isCustom()).isTrue();
            assertThat(entry.material()).isNull();
        }

        @Test
        @DisplayName("a plain material entry is not custom")
        void plainIsNotCustom() {
            assertThat(LootEntry.of(Material.BREAD, 1).isCustom()).isFalse();
        }

        @Test
        @DisplayName("both kinds can share a pool, which is the point")
        void mixedPool() {
            LootTable mixed = LootTable.builder("hg", "bonus")
                    .entry(LootEntry.of(Material.BREAD, 50))
                    .entry(LootEntry.ofCustomItem("hg:medikit", 50))
                    .build();
            assertThat(mixed.pick(fixed(0)).orElseThrow().isCustom()).isFalse();
            assertThat(mixed.pick(fixed(50)).orElseThrow().isCustom()).isTrue();
        }

        @Test
        @DisplayName("an entry needs something to give")
        void refusesAnEmptyEntry() {
            assertThatThrownBy(() -> LootEntry.ofCustomItem(" ", 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LootEntry.of(null, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ------------------------------------------------------------------ tiers

    /**
     * The thing their config could not express: a supply drop is not a chest with different odds,
     * it is a better chest, and saying so once beats copying a pool and changing the numbers.
     */
    @Nested
    @DisplayName("tiers")
    class Tiers {

        @Test
        @DisplayName("a table can say which tier it is, and be found by it")
        void findsByTier() {
            tables.define(LootTable.builder("hg", "common").tier(1)
                    .entry(LootEntry.of(Material.BREAD, 1)).build());
            tables.define(LootTable.builder("hg", "rare").tier(3)
                    .entry(LootEntry.of(Material.DIAMOND, 1)).build());

            assertThat(tables.ofTier(3)).extracting(LootTable::id).containsExactly("rare");
            assertThat(tables.tiers()).containsExactly(1, 3);
        }

        @Test
        @DisplayName("a table with no tier is tier one")
        void defaultsToOne() {
            assertThat(LootTable.builder("hg", "chest").build().tier()).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ persistence

    @Nested
    @DisplayName("across a restart")
    class Persistence {

        @Test
        @DisplayName("a table comes back exactly as it was")
        void roundTrips() {
            tables.define(LootTable.builder("hg", "chest")
                    .fillPercent(30)
                    .tier(2)
                    .entry(LootEntry.of(Material.BREAD, 70).amount(1, 3))
                    .entry(LootEntry.ofCustomItem("hg:medikit", 5))
                    .build());
            tables.flush();

            LootTables reopened = new LootTables(directory.resolve("loot.yml"));
            reopened.load();

            LootTable read = reopened.byKey("hg:chest").orElseThrow();
            assertThat(read.fillPercent()).isEqualTo(30);
            assertThat(read.tier()).isEqualTo(2);
            assertThat(read.entries()).hasSize(2);
            assertThat(read.totalWeight()).isEqualTo(75);
            assertThat(read.entries().get(1).customItem()).contains("hg:medikit");
        }

        @Test
        @DisplayName("an entry naming a block this server has never heard of is skipped")
        void skipsUnknownMaterials() throws Exception {
            java.nio.file.Files.writeString(directory.resolve("loot.yml"), """
                    tables:
                      hg:chest:
                        fill-percent: 30
                        entries:
                          - material: BREAD
                            weight: 10
                          - material: UNOBTAINIUM
                            weight: 10
                    """);
            LootTables reopened = new LootTables(directory.resolve("loot.yml"));
            reopened.load();

            assertThat(reopened.byKey("hg:chest").orElseThrow().entries()).hasSize(1);
            assertThat(reopened.problems()).hasSize(1);
        }

        @Test
        @DisplayName("a missing file is simply no tables")
        void survivesAMissingFile() {
            LootTables fresh = new LootTables(directory.resolve("nothing.yml"));
            assertThatCode(fresh::load).doesNotThrowAnyException();
            assertThat(fresh.all()).isEmpty();
        }

        @Test
        @DisplayName("a plugin's default does not overwrite what the owner has changed")
        void defaultsDoNotOverwriteEdits() {
            tables.define(LootTable.builder("hg", "chest").fillPercent(80)
                    .entry(LootEntry.of(Material.DIAMOND, 1)).build());
            tables.defineIfAbsent(pool());
            assertThat(tables.byKey("hg:chest").orElseThrow().fillPercent()).isEqualTo(80);
        }
    }

    /** A {@link Random} that always answers the same number — for checking a boundary exactly. */
    private static Random fixed(int value) {
        return new Random() {
            @Override
            public int nextInt(int bound) {
                return Math.min(value, bound - 1);
            }
        };
    }
}
