package de.raindancer.core.content.items;

import de.raindancer.core.ui.messages.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The bug this whole file exists for: a right click on a custom item did nothing at all.
 *
 * <h2>What was wrong, and why nothing found it</h2>
 * {@link CustomItems} held sixteen definitions. {@link ItemFactory} could recognise any of them.
 * {@link ItemAbilities} owned the charges, the cooldowns and an atomic use. Four services in the Hunger Games
 * module registered fourteen abilities between them and counted them in the boot log. And nothing, anywhere,
 * ever called {@link ItemAbilities#use} — so every custom item on the server was the block it was made of. A
 * medikit was a melon slice. The Fiendfinder was a spyglass.
 *
 * <p>No test could fail. Each half was correct and thoroughly covered; what was missing was the sentence
 * joining them, and a missing sentence has no line number. {@link #theDispatcherIsActuallyRegistered} is
 * therefore the most important test here: it reads {@code RainsCorePlugin}'s own source and fails if the
 * listener is not registered, which is the one thing unit-testing the listener can never prove.
 */
class ClickingACustomItemDoesSomethingTest {

    private static final UUID HOLDER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final String KEY = "hungergames:medikit";

    private CustomItems items;
    private ItemAbilities abilities;
    private ItemFactory factory;
    private CustomItemListener listener;

    private Player player;
    private PlayerInventory inventory;
    private ItemStack held;
    private AtomicInteger ran;

    @BeforeEach
    void setUp() {
        items = new CustomItems(Path.of("target", "no-such-items.yml"));
        items.define(CustomItem.builder("hungergames", "medikit")
                .material(Material.GLISTERING_MELON_SLICE)
                .name("Medikit")
                // A bare id beside a namespaced item, which is how every item written so far names its
                // ability — and the exact reason CustomItem.abilityInFull exists.
                .ability("medikit")
                .build());

        abilities = new ItemAbilities(() -> 0L);
        ran = new AtomicInteger();

        factory = mock(ItemFactory.class);
        held = mock(ItemStack.class);
        when(held.getAmount()).thenReturn(1);
        when(factory.keyOf(any())).thenReturn(Optional.of("hungergames:medikit"));

        inventory = mock(PlayerInventory.class);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(HOLDER);
        when(player.getInventory()).thenReturn(inventory);

        listener = new CustomItemListener(items, factory, abilities, mock(Messages.class));
    }

    private void registerAbility(boolean succeeds, boolean consumesItem) {
        ItemAbility.Builder builder = ItemAbility.builder("hungergames", "medikit")
                .on(ItemTrigger.RIGHT_CLICK)
                .attempts(use -> {
                    ran.incrementAndGet();
                    return succeeds;
                });
        if (consumesItem) {
            builder.consumesItem();
        }
        abilities.register(builder.build());
    }

    private PlayerInteractEvent click(Action action, EquipmentSlot hand) {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getItem()).thenReturn(held);
        when(event.getAction()).thenReturn(action);
        when(event.getHand()).thenReturn(hand);
        when(event.useItemInHand()).thenReturn(Event.Result.DEFAULT);
        return event;
    }

    @Nested
    @DisplayName("a click reaches the ability")
    class ItActuallyFires {

        @Test
        @DisplayName("right-clicking air runs it")
        void inTheOpen() {
            registerAbility(true, true);

            listener.onInteract(click(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND));

            assertThat(ran)
                    .as("this is the assertion that was false for every custom item on the server")
                    .hasValue(1);
        }

        @Test
        @DisplayName("right-clicking a block runs it too")
        void besideAWall() {
            // The other half of the same bug: an item that works in the open and does nothing when its
            // holder happens to be facing a fence.
            registerAbility(true, true);

            listener.onInteract(click(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND));

            assertThat(ran).hasValue(1);
        }

        @Test
        @DisplayName("the off hand's copy of the same click does not run it a second time")
        void oneClickIsOneUse() {
            // Paper fires PlayerInteractEvent once per hand. The plugin this was ported from did not check,
            // and single-use items were spent twice for one click.
            registerAbility(true, true);

            listener.onInteract(click(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND));
            listener.onInteract(click(Action.RIGHT_CLICK_AIR, EquipmentSlot.OFF_HAND));

            assertThat(ran).hasValue(1);
        }

        @Test
        @DisplayName("a left click does not set off a right-click item")
        void theWrongTrigger() {
            registerAbility(true, true);

            listener.onInteract(click(Action.LEFT_CLICK_AIR, EquipmentSlot.HAND));

            assertThat(ran).hasValue(0);
        }

        @Test
        @DisplayName("an ordinary item is left entirely alone")
        void nothingToDoWithUs() {
            registerAbility(true, true);
            when(factory.keyOf(any())).thenReturn(Optional.empty());

            PlayerInteractEvent event = click(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND);
            listener.onInteract(event);

            assertThat(ran).hasValue(0);
            org.mockito.Mockito.verify(event, org.mockito.Mockito.never()).setCancelled(true);
        }
    }

    @Nested
    @DisplayName("what the click costs")
    class TheItemItself {

        @Test
        @DisplayName("a use that worked takes one out of the stack")
        void spent() {
            registerAbility(true, true);
            when(held.getAmount()).thenReturn(1);

            listener.onInteract(click(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND));

            org.mockito.Mockito.verify(inventory).setItemInMainHand(null);
        }

        @Test
        @DisplayName("one of a stack of three, not the whole stack")
        void oneOfSeveral() {
            registerAbility(true, true);
            when(held.getAmount()).thenReturn(3);

            listener.onInteract(click(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND));

            org.mockito.Mockito.verify(held).setAmount(2);
            org.mockito.Mockito.verify(inventory, org.mockito.Mockito.never()).setItemInMainHand(null);
        }

        @Test
        @DisplayName("a second one still works — the item is single use, the player is not")
        void aSecondMedikit() {
            // charges(1) counts per player for ever, so the first medikit somebody used spent the only
            // charge they would ever get and every one a sponsor sent afterwards was a melon slice.
            registerAbility(true, true);

            listener.onInteract(click(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND));
            listener.onInteract(click(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND));

            assertThat(ran).hasValue(2);
        }

        @Test
        @DisplayName("a use that declined costs nothing and leaves the click alone")
        void aMiss() {
            // A grappling hook aimed at the sky. Consuming it, or swallowing the click, makes a miss feel
            // like a punishment for something the player did not do.
            registerAbility(false, true);

            PlayerInteractEvent event = click(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND);
            listener.onInteract(event);

            assertThat(ran).hasValue(1);
            org.mockito.Mockito.verify(inventory, org.mockito.Mockito.never()).setItemInMainHand(null);
            org.mockito.Mockito.verify(event, org.mockito.Mockito.never()).setCancelled(true);
        }

        @Test
        @DisplayName("a use that worked cancels the vanilla behaviour")
        void noEatingTheMedikit() {
            // A medikit is a glistering melon slice and the Fiendfinder is a spyglass. Without this the
            // player eats the one and zooms with the other as well as using it.
            registerAbility(true, true);

            PlayerInteractEvent event = click(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND);
            listener.onInteract(event);

            org.mockito.Mockito.verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("an item that does not consume itself stays in the hand")
        void keptOnPurpose() {
            registerAbility(true, false);

            listener.onInteract(click(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND));

            org.mockito.Mockito.verify(inventory, org.mockito.Mockito.never()).setItemInMainHand(null);
        }
    }

    @Nested
    @DisplayName("naming the ability")
    class TheKey {

        @Test
        @DisplayName("a bare id is read as this item's own plugin's")
        void namespaceFilledIn() {
            CustomItem item = CustomItem.builder("hungergames", "medikit")
                    .material(Material.PAPER).ability("medikit").build();

            assertThat(item.abilityInFull())
                    .as("abilities.byKey(\"medikit\") finds nothing, and an ability nobody can find is an "
                            + "item that silently does nothing")
                    .contains(KEY);
        }

        @Test
        @DisplayName("a fully named one is left as it is")
        void alreadyQualified() {
            CustomItem item = CustomItem.builder("claims", "wand")
                    .material(Material.PAPER).ability("hungergames:medikit").build();

            assertThat(item.abilityInFull()).contains(KEY);
        }

        @Test
        @DisplayName("an item with no ability names none")
        void inert() {
            assertThat(CustomItem.builder("hungergames", "token").material(Material.PAPER).build()
                    .abilityInFull()).isEmpty();
        }
    }

    @Nested
    @DisplayName("which clicks count")
    class Clicks {

        @Test
        @DisplayName("air and block are the same click")
        void bothSides() {
            assertThat(ItemTrigger.forClick(Action.RIGHT_CLICK_AIR)).contains(ItemTrigger.RIGHT_CLICK);
            assertThat(ItemTrigger.forClick(Action.RIGHT_CLICK_BLOCK)).contains(ItemTrigger.RIGHT_CLICK);
            assertThat(ItemTrigger.forClick(Action.LEFT_CLICK_AIR)).contains(ItemTrigger.LEFT_CLICK);
            assertThat(ItemTrigger.forClick(Action.LEFT_CLICK_BLOCK)).contains(ItemTrigger.LEFT_CLICK);
        }

        @Test
        @DisplayName("standing on a pressure plate is not using what you are holding")
        void notPhysical() {
            assertThat(ItemTrigger.forClick(Action.PHYSICAL)).isEmpty();
            assertThat(ItemTrigger.forClick(null)).isEmpty();
        }
    }

    @Test
    @DisplayName("the dispatcher is actually registered, which is the half that was missing")
    void theDispatcherIsActuallyRegistered() throws Exception {
        // Read from the source rather than asserted about an object, because the failure being guarded
        // against is precisely "the class is perfect and nobody starts it". Every unit test above passed
        // for the whole time custom items did nothing on this server.
        String plugin = java.nio.file.Files.readString(
                Path.of("src/main/java/de/raindancer/core/RainsCorePlugin.java"));

        assertThat(plugin)
                .as("without this line every custom item on the server is the block it is made of, and "
                        + "nothing anywhere says so")
                .contains("registerEvents(\n                new CustomItemListener(");
    }
}
