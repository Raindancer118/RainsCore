package de.raindancer.core.platform.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "How many may this player have", when the answer comes from a permission.
 *
 * <h2>The bug this class exists to make unrepeatable</h2>
 * The obvious way to read {@code homes.limit.<n>} is to ask {@code hasPermission("homes.limit." + n)}
 * for each n and take the highest that answers yes. That is wrong on Bukkit, and wrong in the worst
 * direction: <b>a permission that has never been declared defaults to true for an operator</b>. So
 * every operator "held" {@code homes.limit.100} and was quietly given a hundred homes — on a server
 * where the owner had configured three.
 *
 * <p>The fix is to read what the player has actually been <em>granted</em>, which is what
 * {@code getEffectivePermissions()} answers. This class takes that set as plain strings so the rule
 * can be tested without a server, and {@link NumberedLimit#of} does the reading.
 *
 * <p>It had been written once in homes and copied once into a host that vendored them, which is two
 * copies of a subtle bug fix. Hence: Core's.
 */
class NumberedLimitTest {

    private static final String NODE = "homes.limit.";

    /** What a player has been granted, as `getEffectivePermissions` would report it. */
    private static NumberedLimit reading(String... granted) {
        return NumberedLimit.reading(NODE, Set.of(granted));
    }

    @Nested
    @DisplayName("the configured floor")
    class Floor {

        @Test
        @DisplayName("somebody granted nothing gets what the config says")
        void nothingGrantedIsTheFloor() {
            assertThat(reading().highestOf(3)).isEqualTo(3);
        }

        @Test
        @DisplayName("a node can raise the floor")
        void aNodeRaisesIt() {
            assertThat(reading("homes.limit.10").highestOf(3)).isEqualTo(10);
        }

        @Test
        @DisplayName("a node can never lower it")
        void aNodeNeverLowersIt() {
            // Otherwise granting somebody a node takes homes away from them, which is the opposite
            // of what granting a permission means to anybody reading a permissions file.
            assertThat(reading("homes.limit.1").highestOf(3)).isEqualTo(3);
        }

        @Test
        @DisplayName("the highest node held is the one that counts")
        void theHighestWins() {
            assertThat(reading("homes.limit.5", "homes.limit.25", "homes.limit.10").highestOf(3))
                    .isEqualTo(25);
        }

        @Test
        @DisplayName("a floor below zero is zero")
        void theFloorIsNotNegative() {
            assertThat(reading().highestOf(-4)).isZero();
        }
    }

    @Nested
    @DisplayName("what is not a numbered node")
    class NotANumber {

        @Test
        @DisplayName("a node with something other than a number after it is ignored")
        void rubbishIsIgnored() {
            assertThat(reading("homes.limit.lots", "homes.limit.", "homes.limit.3.5")
                    .highestOf(3))
                    .as("none of those is a number, so none of them says how many")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("a number too large to be one is ignored rather than thrown")
        void anAbsurdNumberIsIgnored() {
            // Somebody will paste a very long number into a permissions file. Refusing to start the
            // server over it, or throwing on the first /home, is worse than ignoring the line.
            assertThat(reading("homes.limit.99999999999999999999").highestOf(3)).isEqualTo(3);
        }

        @Test
        @DisplayName("a negative node is ignored")
        void negativeIsIgnored() {
            assertThat(reading("homes.limit.-5").highestOf(3)).isEqualTo(3);
        }

        @Test
        @DisplayName("another plugin's node with the same shape is ignored")
        void anotherPrefixIsIgnored() {
            assertThat(reading("warps.limit.50", "homes.limits.50", "xhomes.limit.50")
                    .highestOf(3))
                    .as("a prefix match has to be the whole prefix, or one plugin's limits set "
                            + "another's")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("the prefix on its own says nothing")
        void theBarePrefixIsIgnored() {
            assertThat(reading("homes.limit").highestOf(3)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("no limit at all")
    class Unlimited {

        @Test
        @DisplayName("the unlimited node beats every number")
        void unlimitedWins() {
            NumberedLimit limit = NumberedLimit.reading(NODE,
                    Set.of("homes.limit.5", "homes.unlimited"), "homes.unlimited");

            assertThat(limit.isUnlimited()).isTrue();
            assertThat(limit.highestOf(3)).isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("without the node nobody is unlimited")
        void otherwiseNobodyIs() {
            assertThat(reading("homes.limit.5").isUnlimited()).isFalse();
        }

        @Test
        @DisplayName("it reads as a symbol rather than as two billion")
        void itReadsAsASymbol() {
            NumberedLimit limit = NumberedLimit.reading(NODE, Set.of("homes.unlimited"),
                    "homes.unlimited");

            assertThat(limit.describe(3)).isEqualTo("∞");
            assertThat(reading().describe(3))
                    .as("2147483647 on a player's screen is a bug they will report")
                    .isEqualTo("3");
        }
    }

    @Nested
    @DisplayName("nonsense in, nothing out")
    class Robustness {

        @Test
        @DisplayName("no grants at all is the floor, not an exception")
        void nullGrantsAreEmpty() {
            assertThat(NumberedLimit.reading(NODE, null).highestOf(3)).isEqualTo(3);
        }

        @Test
        @DisplayName("a blank prefix matches nothing rather than everything")
        void aBlankPrefixIsInert() {
            // Matching everything would make the first number anywhere in anybody's permissions the
            // answer, which is the sort of thing that is only found on somebody else's server.
            assertThat(NumberedLimit.reading("", Set.of("homes.limit.50")).highestOf(3))
                    .isEqualTo(3);
            assertThat(NumberedLimit.reading(null, Set.of("homes.limit.50")).highestOf(3))
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("a grant that is null in the set is stepped over")
        void nullsInTheSetAreSkipped() {
            java.util.Set<String> withANull = new java.util.HashSet<>(List.of("homes.limit.7"));
            withANull.add(null);

            assertThat(NumberedLimit.reading(NODE, withANull).highestOf(3)).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("counting against it")
    class Counting {

        @Test
        @DisplayName("under the limit there is room")
        void roomUnder() {
            assertThat(reading().isRoomFor(2, 3)).isTrue();
        }

        @Test
        @DisplayName("at the limit there is not")
        void noRoomAt() {
            assertThat(reading().isRoomFor(3, 3)).isFalse();
        }

        @Test
        @DisplayName("unlimited always has room")
        void unlimitedAlwaysHasRoom() {
            NumberedLimit limit = NumberedLimit.reading(NODE, Set.of("homes.unlimited"),
                    "homes.unlimited");

            assertThat(limit.isRoomFor(9_000, 3)).isTrue();
        }

        @Test
        @DisplayName("a limit of zero means none, not one")
        void zeroMeansNone() {
            // A server that switched the feature off by setting the number to zero means it.
            assertThat(reading().isRoomFor(0, 0)).isFalse();
        }
    }
}
