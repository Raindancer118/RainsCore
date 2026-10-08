package de.raindancer.core.data.stash;

import de.raindancer.core.testkit.TestItems;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stash: what goes in, where it lands, and that nothing is ever counted twice or lost.
 */
class StashTest {

    private static ItemStack dirt(int amount) {
        return TestItems.of(Material.DIRT, amount);
    }

    @Nested
    @DisplayName("putting things in")
    class Depositing {

        @Test
        @DisplayName("similar stacks are topped up before a new one is started")
        void merges() {
            Stash stash = new Stash(10);

            assertThat(stash.deposit(dirt(30))).isEqualTo(30);
            assertThat(stash.deposit(dirt(40))).isEqualTo(40);

            assertThat(stash.items()).extracting(ItemStack::getAmount).containsExactly(64, 6);
        }

        @Test
        @DisplayName("a named stack does not merge into an unnamed one")
        void onlySimilarMerges() {
            Stash stash = new Stash(10);
            stash.deposit(dirt(10));
            ItemStack named = TestItems.of(Material.DIRT, meta -> meta.customName(Component.text("Special")));

            stash.deposit(named);

            assertThat(stash.items()).hasSize(2);
        }

        @Test
        @DisplayName("a full stash takes what fits and says how much that was")
        void respectsCapacity() {
            Stash stash = new Stash(1);
            stash.deposit(dirt(60));

            assertThat(stash.deposit(dirt(10))).as("only four more fit on the one stack").isEqualTo(4);
            assertThat(stash.deposit(TestItems.of(Material.STONE, 1))).isZero();
            assertThat(stash.items()).extracting(ItemStack::getAmount).containsExactly(64);
        }

        @Test
        @DisplayName("the stack handed in is never changed — the caller takes away what was accepted")
        void doesNotTouchTheArgument() {
            Stash stash = new Stash(10);
            ItemStack given = dirt(12);

            stash.deposit(given);
            given.setAmount(1);

            assertThat(given.getAmount()).isEqualTo(1);
            assertThat(stash.items().getFirst().getAmount()).isEqualTo(12);
        }

        @Test
        @DisplayName("nothing is nothing")
        void emptyIsRefused() {
            Stash stash = new Stash(10);

            assertThat(stash.deposit(null)).isZero();
            assertThat(stash.deposit(TestItems.air())).isZero();
            assertThat(stash.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("armour")
    class Armour {

        @Test
        @DisplayName("a piece of armour goes on its own stand first, and into the stash once that is taken")
        void armourHasItsOwnSlots() {
            Stash stash = new Stash(10);

            stash.deposit(TestItems.of(Material.DIAMOND_HELMET));
            stash.deposit(TestItems.of(Material.IRON_HELMET));
            stash.deposit(TestItems.of(Material.ELYTRA));

            assertThat(stash.armour(ArmourPiece.HEAD)).get().extracting(ItemStack::getType)
                    .isEqualTo(Material.DIAMOND_HELMET);
            assertThat(stash.armour(ArmourPiece.CHEST)).get().extracting(ItemStack::getType)
                    .isEqualTo(Material.ELYTRA);
            assertThat(stash.items()).extracting(ItemStack::getType).containsExactly(Material.IRON_HELMET);
            assertThat(stash.hasArmour()).isTrue();
        }

        @Test
        @DisplayName("each piece knows its slot; anything else is not armour")
        void pieces() {
            assertThat(ArmourPiece.of(Material.TURTLE_HELMET)).contains(ArmourPiece.HEAD);
            assertThat(ArmourPiece.of(Material.NETHERITE_CHESTPLATE)).contains(ArmourPiece.CHEST);
            assertThat(ArmourPiece.of(Material.CHAINMAIL_LEGGINGS)).contains(ArmourPiece.LEGS);
            assertThat(ArmourPiece.of(Material.LEATHER_BOOTS)).contains(ArmourPiece.FEET);
            assertThat(ArmourPiece.of(Material.MACE)).isEmpty();
            assertThat(ArmourPiece.of(Material.CARVED_PUMPKIN)).isEmpty();
            assertThat(ArmourPiece.of(null)).isEmpty();
        }

        @Test
        @DisplayName("swapping a stand gives back what was on it")
        void swap() {
            Stash stash = new Stash(10);
            stash.deposit(TestItems.of(Material.DIAMOND_BOOTS));

            ItemStack before = stash.swapArmour(ArmourPiece.FEET, TestItems.of(Material.IRON_BOOTS));

            assertThat(before.getType()).isEqualTo(Material.DIAMOND_BOOTS);
            assertThat(stash.armour(ArmourPiece.FEET)).get().extracting(ItemStack::getType)
                    .isEqualTo(Material.IRON_BOOTS);
            assertThat(stash.swapArmour(ArmourPiece.FEET, null).getType()).isEqualTo(Material.IRON_BOOTS);
            assertThat(stash.hasArmour()).isFalse();
        }

        @Test
        @DisplayName("a stand only takes its own kind")
        void wrongPieceIsRefused() {
            Stash stash = new Stash(10);

            assertThat(stash.swapArmour(ArmourPiece.HEAD, TestItems.of(Material.DIAMOND_BOOTS)))
                    .as("refused, so handed straight back").extracting(ItemStack::getType)
                    .isEqualTo(Material.DIAMOND_BOOTS);
            assertThat(stash.armour(ArmourPiece.HEAD)).isEmpty();
        }
    }

    @Nested
    @DisplayName("taking things out")
    class Taking {

        @Test
        @DisplayName("an entry comes out once, when it is still what the screen showed")
        void takeOnce() {
            Stash stash = new Stash(10);
            stash.deposit(dirt(5));
            stash.deposit(TestItems.of(Material.STONE, 3));
            ItemStack shown = stash.items().get(1);

            assertThat(stash.take(1, shown)).extracting(ItemStack::getType).isEqualTo(Material.STONE);
            assertThat(stash.take(1, shown)).as("already gone — a double click must not pay twice").isNull();
            assertThat(stash.items()).extracting(ItemStack::getType).containsExactly(Material.DIRT);
        }

        @Test
        @DisplayName("the first entry that matches comes out, and only that one")
        void takeFirstMatching() {
            Stash stash = new Stash(10);
            stash.deposit(dirt(5));
            stash.deposit(TestItems.of(Material.MACE));
            stash.deposit(TestItems.of(Material.MACE));

            assertThat(stash.takeFirst(item -> item.getType() == Material.MACE))
                    .extracting(ItemStack::getType).isEqualTo(Material.MACE);
            assertThat(stash.items()).extracting(ItemStack::getType)
                    .containsExactly(Material.DIRT, Material.MACE);
            assertThat(stash.takeFirst(item -> item.getType() == Material.STONE)).isNull();
            assertThat(stash.contains(item -> item.getType() == Material.MACE)).isTrue();
        }

        @Test
        @DisplayName("a stale screen takes nothing")
        void staleIndex() {
            Stash stash = new Stash(10);
            stash.deposit(dirt(5));
            ItemStack shown = stash.items().getFirst();
            stash.deposit(dirt(5));

            assertThat(stash.take(0, shown)).as("the stack changed since it was drawn").isNull();
            assertThat(stash.take(7, shown)).isNull();
            assertThat(stash.items()).extracting(ItemStack::getAmount).containsExactly(10);
        }

        @Test
        @DisplayName("what is handed out is a copy")
        void copies() {
            Stash stash = new Stash(10);
            stash.deposit(dirt(5));

            stash.items().getFirst().setAmount(64);
            stash.armour(ArmourPiece.HEAD).ifPresent(piece -> piece.setAmount(2));

            assertThat(stash.items().getFirst().getAmount()).isEqualTo(5);
        }

        @Test
        @DisplayName("every change moves the version on; a refusal does not")
        void versions() {
            Stash stash = new Stash(1);
            long start = stash.contents().version();

            stash.deposit(dirt(64));
            long afterDeposit = stash.contents().version();
            stash.deposit(dirt(1));

            assertThat(afterDeposit).isGreaterThan(start);
            assertThat(stash.contents().version()).isEqualTo(afterDeposit);
        }
    }
}
