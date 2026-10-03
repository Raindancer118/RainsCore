package de.raindancer.core.content.items;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaggedItemsTest {

    private static final NamespacedKey KEY = new NamespacedKey("manhunt", "tracker");
    private final TaggedItems compasses = TaggedItems.of(KEY).onlyOn(Material.COMPASS);

    private static ItemStack tagged(Material type, String value) {
        ItemStack stack = mock(ItemStack.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(stack.getType()).thenReturn(type);
        when(stack.getAmount()).thenReturn(1);
        when(stack.getPersistentDataContainer()).thenReturn(data);
        when(data.get(KEY, PersistentDataType.STRING)).thenReturn(value);
        return stack;
    }

    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final ItemStack hunters = tagged(Material.COMPASS, "hunter");
    private final ItemStack runners = tagged(Material.COMPASS, "runner");
    private final ItemStack plainCompass = tagged(Material.COMPASS, null);
    private final ItemStack bread = tagged(Material.BREAD, "hunter");

    @BeforeEach
    void setUp() {
        when(player.getInventory()).thenReturn(inventory);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(inventory.getContents()).thenReturn(new ItemStack[]{bread, null, plainCompass, runners, hunters});
    }

    @Nested
    @DisplayName("recognising")
    class Recognising {

        @Test
        @DisplayName("by tag and value, never by name or by material alone")
        void byTag() {
            assertThat(compasses.valueOf(hunters)).contains("hunter");
            assertThat(compasses.is(hunters, "hunter")).isTrue();
            assertThat(compasses.is(hunters, "runner")).isFalse();
            assertThat(compasses.is(plainCompass)).as("an ordinary compass").isFalse();
            assertThat(compasses.is(null)).isFalse();
        }

        @Test
        @DisplayName("a material it is never on is answered without opening the item's data")
        void materialFilter() {
            assertThat(compasses.is(bread)).isFalse();
            verify(bread, never()).getPersistentDataContainer();
            assertThat(TaggedItems.of(KEY).is(bread)).as("without a material filter").isTrue();
        }
    }

    @Nested
    @DisplayName("in an inventory")
    class InAnInventory {

        @Test
        @DisplayName("found, counted, and taken back — only the ones asked for")
        void findCountRemove() {
            assertThat(compasses.slotOf(inventory, "hunter"::equals)).hasValue(4);
            assertThat(compasses.count(inventory, value -> true)).isEqualTo(2);

            assertThat(compasses.removeAll(inventory, "runner"::equals)).isEqualTo(1);
            verify(inventory).setItem(3, null);
            verify(inventory, never()).setItem(eq(4), any());
        }

        @Test
        @DisplayName("one on the cursor of an open window is carried, and taken back with the rest")
        void cursorCounts() {
            when(inventory.getContents()).thenReturn(new ItemStack[0]);
            when(player.getItemOnCursor()).thenReturn(hunters);

            assertThat(compasses.carries(player, "hunter"::equals)).isTrue();
            assertThat(compasses.removeAll(player, value -> true)).isEqualTo(1);
            verify(player).setItemOnCursor(null);
        }

        @Test
        @DisplayName("in either hand")
        void inHand() {
            when(inventory.getItemInMainHand()).thenReturn(bread);
            when(inventory.getItemInOffHand()).thenReturn(hunters);

            assertThat(compasses.inHand(player, "hunter"::equals)).isTrue();
            assertThat(compasses.inHand(player, "runner"::equals)).isFalse();
        }

        @Test
        @DisplayName("kept out of a death's drops")
        void drops() {
            List<ItemStack> drops = new ArrayList<>(List.of(hunters, plainCompass, runners));

            assertThat(compasses.removeFromDrops(drops, value -> true)).isEqualTo(2);
            assertThat(drops).containsExactly(plainCompass);
        }
    }

    @Nested
    @DisplayName("handing over")
    class HandingOver {

        @Test
        @DisplayName("what does not fit lands at their feet, owned by them, rather than vanishing")
        void overflowIsDroppedAndOwned() {
            World world = mock(World.class);
            Item onTheGround = mock(Item.class);
            when(player.getWorld()).thenReturn(world);
            when(player.getLocation()).thenReturn(mock(Location.class));
            HashMap<Integer, ItemStack> left = new HashMap<>();
            left.put(0, hunters);
            when(inventory.addItem(any(ItemStack[].class))).thenReturn(left);
            when(world.dropItem(any(), eq(hunters))).thenReturn(onTheGround);

            TaggedItems.HandOver result = TaggedItems.handTo(player, hunters);

            assertThat(result.fitted()).isFalse();
            assertThat(result.placed()).isZero();
            verify(onTheGround).setOwner(player.getUniqueId());
        }

        @Test
        @DisplayName("given only when they have none, so a respawn or a login never doubles it")
        void giveIfMissing() {
            when(inventory.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());

            assertThat(compasses.giveIfMissing(player, "hunter", () -> hunters)).isFalse();
            assertThat(compasses.giveIfMissing(player, "spectator", () -> tagged(Material.COMPASS, "spectator")))
                    .isTrue();
            verify(inventory).addItem(any(ItemStack[].class));
        }
    }

    @Test
    @DisplayName("tagging writes the value, and a bound kind binds the item too")
    void tagging() {
        ItemStack stack = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(data);

        assertThat(compasses.bound().tag(stack, "hunter")).isSameAs(stack);

        verify(data).set(KEY, PersistentDataType.STRING, "hunter");
        verify(data).set(BoundItems.KEY, PersistentDataType.BYTE, (byte) 1);
        assertThat(compasses.isBound()).as("the original kind is unchanged").isFalse();
    }
}
