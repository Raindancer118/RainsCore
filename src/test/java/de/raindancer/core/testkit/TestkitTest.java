package de.raindancer.core.testkit;

import de.raindancer.core.content.items.TaggedItems;
import de.raindancer.core.ui.menu.Icons;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestkitTest {

    private static final NamespacedKey KEY = new NamespacedKey("test", "kind");

    @Nested
    @DisplayName("registries")
    class Registries {

        @Test
        @DisplayName("materials know whether they are items, blocks or air, as on a server")
        void materials() {
            assertThat(Material.COMPASS.isItem()).isTrue();
            assertThat(Material.WATER.isItem()).as("a block with no item").isFalse();
            assertThat(Material.STONE.isBlock()).isTrue();
            assertThat(Material.CAVE_AIR.isAir()).isTrue();
            assertThat(Material.STONE.isAir()).isFalse();
        }

        @Test
        @DisplayName("registry constants resolve, and are the same object every time")
        void constants() {
            assertThat(Attribute.ARMOR.getKey()).isEqualTo(NamespacedKey.minecraft("armor"));
            assertThat(Enchantment.SHARPNESS).isSameAs(org.bukkit.Registry.ENCHANTMENT.get(NamespacedKey.minecraft("sharpness")));
        }

        @Test
        @DisplayName("stack sizes follow vanilla's rules")
        void stackSizes() {
            assertThat(Material.DIAMOND_SWORD.getMaxStackSize()).isEqualTo(1);
            assertThat(Material.ENDER_PEARL.getMaxStackSize()).isEqualTo(16);
            assertThat(Material.OAK_SIGN.getMaxStackSize()).isEqualTo(16);
            assertThat(Material.COBBLESTONE.getMaxStackSize()).isEqualTo(64);
        }
    }

    @Nested
    @DisplayName("items")
    class Items {

        @Test
        @DisplayName("new ItemStack and ItemStack.of work in code under test, with the right kind of meta")
        void creating() {
            ItemStack made = new ItemStack(Material.COMPASS, 3);
            ItemStack of = ItemStack.of(Material.COMPASS);

            assertThat(made.getType()).isEqualTo(Material.COMPASS);
            assertThat(made.getAmount()).isEqualTo(3);
            assertThat(made.getItemMeta()).isInstanceOf(CompassMeta.class).isInstanceOf(Damageable.class);
            assertThat(TestItems.isTestStack(made)).isTrue();
            assertThat(made.isSimilar(of)).isTrue();
            assertThat(made).isNotEqualTo(of).as("amounts differ");
            assertThat(made).isEqualTo(made.clone());
        }

        @Test
        @DisplayName("meta is a copy: nothing changes on the item until it is set back")
        void metaIsACopy() {
            ItemStack stack = TestItems.of(Material.DIAMOND);
            ItemMeta meta = stack.getItemMeta();
            meta.displayName(Component.text("Shiny"));

            assertThat(stack.getItemMeta().hasDisplayName()).as("forgot setItemMeta").isFalse();
            stack.setItemMeta(meta);
            assertThat(stack.getItemMeta().displayName()).isEqualTo(Component.text("Shiny"));
            assertThat(stack.hasItemMeta()).isTrue();
        }

        @Test
        @DisplayName("names, lore, flags, enchantments and plain properties all stay")
        void metaContents() {
            ItemStack stack = TestItems.of(Material.DIAMOND_SWORD, meta -> {
                meta.setDisplayName("§aBlade");
                meta.lore(List.of(Component.text("one"), Component.text("two")));
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                meta.addEnchant(Enchantment.SHARPNESS, 5, true);
                meta.setUnbreakable(true);
                meta.setCustomModelData(7);
                ((Damageable) meta).setDamage(12);
            });
            ItemMeta meta = stack.getItemMeta();

            assertThat(PlainTextComponentSerializer.plainText().serialize(meta.displayName())).isEqualTo("Blade");
            assertThat(stack.lore()).hasSize(2);
            assertThat(stack.getLore()).containsExactly("one", "two");
            assertThat(stack.hasItemFlag(ItemFlag.HIDE_ENCHANTS)).isTrue();
            assertThat(stack.getEnchantmentLevel(Enchantment.SHARPNESS)).isEqualTo(5);
            assertThat(meta.isUnbreakable()).isTrue();
            assertThat(meta.hasCustomModelData()).isTrue();
            assertThat(meta.getCustomModelData()).isEqualTo(7);
            assertThat(((Damageable) meta).getDamage()).isEqualTo(12);
            assertThat(TestItems.of(Material.DIAMOND_SWORD).isSimilar(stack)).isFalse();
        }

        @Test
        @DisplayName("attribute modifiers: added, read per attribute and slot, refused twice, removed")
        void attributes() {
            AttributeModifier bonus = new AttributeModifier(new NamespacedKey("test", "bonus"), 4,
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND);
            ItemStack stack = TestItems.of(Material.IRON_SWORD, meta -> meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, bonus));
            ItemMeta meta = stack.getItemMeta();

            assertThat(meta.hasAttributeModifiers()).isTrue();
            assertThat(meta.getAttributeModifiers(Attribute.ATTACK_DAMAGE)).containsExactly(bonus);
            assertThat(meta.getAttributeModifiers(EquipmentSlot.HAND).size()).isEqualTo(1);
            assertThat(meta.getAttributeModifiers(EquipmentSlot.HEAD).isEmpty()).isTrue();
            assertThatThrownBy(() -> meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, bonus))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(meta.removeAttributeModifier(Attribute.ATTACK_DAMAGE)).isTrue();
            assertThat(meta.getAttributeModifiers()).isNull();
        }

        @Test
        @DisplayName("quantity helpers, empties and air")
        void quantities() {
            ItemStack stack = TestItems.of(Material.ARROW, 10);

            assertThat(stack.asOne().getAmount()).isEqualTo(1);
            assertThat(stack.asQuantity(5).getAmount()).isEqualTo(5);
            assertThat(stack.subtract(10).isEmpty()).isTrue();
            assertThat(TestItems.air().isEmpty()).isTrue();
            assertThat(TestItems.air().getItemMeta()).isNull();
            assertThatThrownBy(() -> TestItems.of(Material.WATER)).hasMessageContaining("isn't an item");
        }

        @Test
        @DisplayName("what needs a server says so instead of making something up")
        void unsupported() {
            assertThatThrownBy(() -> TestItems.of(Material.STONE).serializeAsBytes())
                    .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("serializeAsBytes");
            assertThatThrownBy(() -> TestItems.of(Material.STONE).setItemMeta(org.mockito.Mockito.mock(ItemMeta.class)))
                    .hasMessageContaining("not made by the testkit");
        }

        @Test
        @DisplayName("Core's own item code runs unchanged: icons and tagged items")
        void coreCodeRuns() {
            ItemStack icon = Icons.of(Material.EMERALD, "<green>Buy", "<gray>For 3 coins");
            assertThat(PlainTextComponentSerializer.plainText().serialize(icon.getItemMeta().displayName())).isEqualTo("Buy");
            assertThat(icon.lore()).hasSize(1);

            TaggedItems trackers = TaggedItems.of(KEY).onlyOn(Material.COMPASS);
            ItemStack compass = trackers.tag(TestItems.of(Material.COMPASS), "hunter");
            assertThat(trackers.is(compass, "hunter")).isTrue();
            assertThat(trackers.is(TestItems.of(Material.COMPASS))).isFalse();
        }
    }

    @Nested
    @DisplayName("persistent data")
    class Data {

        @Test
        @DisplayName("values come back as stored, typed; the wrong type is refused as on a server")
        void typed() {
            PersistentDataContainer data = TestItems.data();
            data.set(KEY, PersistentDataType.INTEGER, 3);
            data.set(new NamespacedKey("test", "flag"), PersistentDataType.BOOLEAN, true);

            assertThat(data.get(KEY, PersistentDataType.INTEGER)).isEqualTo(3);
            assertThat(data.has(KEY, PersistentDataType.STRING)).isFalse();
            assertThat(data.get(new NamespacedKey("test", "flag"), PersistentDataType.BYTE)).isEqualTo((byte) 1);
            assertThatThrownBy(() -> data.get(KEY, PersistentDataType.STRING)).isInstanceOf(IllegalArgumentException.class);
            assertThat(data.getOrDefault(new NamespacedKey("test", "none"), PersistentDataType.STRING, "x")).isEqualTo("x");
        }

        @Test
        @DisplayName("lists, arrays and nested containers are copies, and survive a byte round trip")
        void nested() throws Exception {
            PersistentDataContainer data = TestItems.data();
            PersistentDataContainer inner = data.getAdapterContext().newPersistentDataContainer();
            inner.set(KEY, PersistentDataType.STRING, "deep");
            data.set(KEY, PersistentDataType.TAG_CONTAINER, inner);
            int[] numbers = {1, 2};
            data.set(new NamespacedKey("test", "numbers"), PersistentDataType.INTEGER_ARRAY, numbers);
            data.set(new NamespacedKey("test", "names"), PersistentDataType.LIST.strings(), List.of("a", "b"));
            numbers[0] = 99;

            assertThat(data.get(new NamespacedKey("test", "numbers"), PersistentDataType.INTEGER_ARRAY)).containsExactly(1, 2);
            assertThat(data.get(new NamespacedKey("test", "names"), PersistentDataType.LIST.strings())).containsExactly("a", "b");
            assertThat(data.has(new NamespacedKey("test", "names"), PersistentDataType.LIST.integers())).isFalse();

            PersistentDataContainer back = TestItems.data();
            back.readFromBytes(data.serializeToBytes());
            assertThat(back).isEqualTo(data);
            assertThat(back.get(KEY, PersistentDataType.TAG_CONTAINER).get(KEY, PersistentDataType.STRING)).isEqualTo("deep");
        }

        @Test
        @DisplayName("an item's data reads through its view and is changed through edit")
        void onItems() {
            ItemStack stack = TestItems.tagged(Material.PAPER, KEY, "note");
            assertThat(stack.getPersistentDataContainer().get(KEY, PersistentDataType.STRING)).isEqualTo("note");
            assertThat(stack.getPersistentDataContainer()).isNotInstanceOf(PersistentDataContainer.class);
            assertThat(stack.isSimilar(TestItems.of(Material.PAPER))).isFalse();
            assertThat(stack.isSimilar(TestItems.tagged(Material.PAPER, KEY, "note"))).isTrue();
        }
    }

    @Nested
    @DisplayName("inventories")
    class Inventories {

        @Test
        @DisplayName("addItem fills similar stacks first, then empty slots, and hands back what does not fit")
        void adding() {
            Inventory chest = TestInventories.chest(2);
            chest.setItem(1, TestItems.of(Material.ARROW, 60));

            Map<Integer, ItemStack> left = chest.addItem(TestItems.of(Material.ARROW, 100));

            assertThat(chest.getItem(1).getAmount()).isEqualTo(64);
            assertThat(chest.getItem(0).getAmount()).isEqualTo(64);
            assertThat(left.get(0).getAmount()).isEqualTo(32);
        }

        @Test
        @DisplayName("removeItem, contains, first and all answer from the contents")
        void reading() {
            Inventory chest = TestInventories.chest(9);
            chest.addItem(TestItems.of(Material.BREAD, 10), TestItems.of(Material.APPLE, 3));

            assertThat(chest.contains(Material.BREAD, 10)).isTrue();
            assertThat(chest.contains(Material.BREAD, 11)).isFalse();
            assertThat(chest.containsAtLeast(TestItems.of(Material.APPLE), 3)).isTrue();
            assertThat(chest.first(Material.APPLE)).isEqualTo(1);
            assertThat(chest.removeItem(TestItems.of(Material.BREAD, 4))).isEmpty();
            assertThat(chest.getItem(0).getAmount()).isEqualTo(6);
            assertThat(chest.removeItem(TestItems.of(Material.APPLE, 5)).get(0).getAmount()).isEqualTo(2);
            assertThat(chest.all(Material.APPLE)).isEmpty();
            assertThat(TestInventories.stacksIn(chest)).hasSize(1);
        }

        @Test
        @DisplayName("getItem hands out the stored stack, so changing it changes the slot; setItem stores a copy")
        void mirror() {
            Inventory chest = TestInventories.chest(1);
            ItemStack given = TestItems.of(Material.STONE, 5);
            chest.setItem(0, given);
            given.setAmount(1);
            assertThat(chest.getItem(0).getAmount()).isEqualTo(5);

            chest.getItem(0).setAmount(2);
            assertThat(chest.getItem(0).getAmount()).isEqualTo(2);

            ListIterator<ItemStack> slots = chest.iterator();
            slots.next();
            slots.set(null);
            assertThat(chest.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a player's hands and armour sit where the server puts them")
        void player() {
            PlayerInventory inventory = TestInventories.player();
            inventory.setHeldItemSlot(2);
            inventory.setItemInMainHand(TestItems.of(Material.BOW));
            inventory.setHelmet(TestItems.of(Material.IRON_HELMET));

            assertThat(inventory.getItem(2).getType()).isEqualTo(Material.BOW);
            assertThat(inventory.getItem(39).getType()).isEqualTo(Material.IRON_HELMET);
            assertThat(inventory.getItem(EquipmentSlot.HEAD).getType()).isEqualTo(Material.IRON_HELMET);
            assertThat(inventory.getItemInOffHand().isEmpty()).as("an empty hand is air, not null").isTrue();
            assertThat(inventory.getStorageContents()).hasSize(36);
            assertThat(inventory.getArmorContents()[3].getType()).isEqualTo(Material.IRON_HELMET);
            assertThatThrownBy(() -> inventory.setHeldItemSlot(9)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("players")
    class Players {

        @Test
        @DisplayName("an id from the name, an inventory, a cursor, permissions and what they were told")
        void player() {
            Player alex = TestPlayers.player("Alex", "game.join");

            assertThat(alex.getName()).isEqualTo("Alex");
            assertThat(alex.getUniqueId()).isEqualTo(TestPlayers.player("Alex").getUniqueId());
            assertThat(alex.getInventory().getHolder()).isSameAs(alex);
            assertThat(alex.hasPermission("game.join")).isTrue();
            assertThat(alex.hasPermission("game.admin")).isFalse();
            TestPlayers.op(alex, true);
            assertThat(alex.hasPermission("game.admin")).isTrue();

            alex.setItemOnCursor(TestItems.of(Material.STICK));
            assertThat(alex.getItemOnCursor().getType()).isEqualTo(Material.STICK);

            alex.sendMessage(Component.text("Hello"));
            alex.sendRichMessage("<red>Careful");
            assertThat(TestPlayers.said(alex)).containsExactly("Hello", "Careful");
        }

        @Test
        @DisplayName("Core code that gives and takes items works against them")
        void coreCodeRuns() {
            Player alex = TestPlayers.player("Alex");
            TaggedItems trackers = TaggedItems.of(KEY);
            alex.getInventory().addItem(trackers.tag(TestItems.of(Material.COMPASS), "hunter"), TestItems.of(Material.BREAD));
            alex.setItemOnCursor(trackers.tag(TestItems.of(Material.COMPASS), "hunter"));

            assertThat(trackers.carries(alex, "hunter"::equals)).isTrue();
            assertThat(trackers.removeAll(alex, value -> true)).isEqualTo(2);
            assertThat(trackers.carries(alex, value -> true)).isFalse();
            assertThat(alex.getInventory().contains(Material.BREAD)).isTrue();
        }
    }
}
