package de.raindancer.core.testkit;

import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;

import static org.mockito.Mockito.withSettings;

/**
 * Inventories for unit tests that keep what is put in them: {@code addItem} stacks onto similar items up
 * to the stack size and returns what did not fit, {@code removeItem} takes from wherever it is,
 * {@code contains}, {@code first}, {@code all} and the hands and armour slots answer from the contents.
 *
 * <pre>{@code
 * PlayerInventory inventory = TestInventories.player();
 * inventory.addItem(TestItems.of(Material.ARROW, 100));
 * assertThat(inventory.getItem(0).getAmount()).isEqualTo(64);
 * assertThat(inventory.getItem(1).getAmount()).isEqualTo(36);
 * }</pre>
 *
 * <p>As on a server, {@code setItem} stores a copy and {@code getItem} returns the stored stack itself,
 * so changing the amount of what {@code getItem} returned changes the inventory; an empty slot reads
 * null, an empty hand reads air. Slots follow the server's player layout: 0–8 hotbar, 9–35 the rest,
 * 36–39 boots to helmet, 40 off hand. They are mocks: anything else can be stubbed as usual.
 */
public final class TestInventories {

    /** A player inventory's size: 36 storage, 4 armour, 1 off hand. */
    public static final int PLAYER_SIZE = 41;
    private static final int STORAGE = 36;
    private static final int BOOTS = 36;
    private static final int LEGGINGS = 37;
    private static final int CHESTPLATE = 38;
    private static final int HELMET = 39;
    private static final int OFF_HAND = 40;

    private TestInventories() {
    }

    /** A player inventory with nobody holding it. */
    public static PlayerInventory player() {
        return player(null);
    }

    /** A player inventory belonging to this player (or other human). */
    public static PlayerInventory player(HumanEntity holder) {
        return Mockito.mock(PlayerInventory.class, withSettings().name("PlayerInventory")
                .defaultAnswer(new Contents(InventoryType.PLAYER, PLAYER_SIZE, STORAGE, holder)));
    }

    /** A chest-like inventory of this many slots. */
    public static Inventory chest(int size) {
        return of(InventoryType.CHEST, size, null);
    }

    /** An inventory of this type and size, held by this holder (may be null). */
    public static Inventory of(InventoryType type, int size, InventoryHolder holder) {
        if (size <= 0) {
            throw new IllegalArgumentException("An inventory needs at least one slot, not " + size);
        }
        return Mockito.mock(Inventory.class, withSettings().name(type + " inventory")
                .defaultAnswer(new Contents(type, size, size, holder)));
    }

    private static final class Contents implements Answer<Object> {
        private final InventoryType type;
        private final ItemStack[] slots;
        private final int storage;
        private final InventoryHolder holder;
        private int maxStackSize = 99;
        private int held;

        Contents(InventoryType type, int size, int storage, InventoryHolder holder) {
            this.type = type;
            this.slots = new ItemStack[size];
            this.storage = storage;
            this.holder = holder;
        }

        @Override
        public Object answer(InvocationOnMock invocation) throws Throwable {
            Object[] args = invocation.getArguments();
            Object first = args.length == 0 ? null : args[0];
            String name = invocation.getMethod().getName();
            switch (name) {
                case "getSize":
                    return slots.length;
                case "getMaxStackSize":
                    return maxStackSize;
                case "setMaxStackSize":
                    maxStackSize = (Integer) first;
                    return null;
                case "getItem":
                    return first instanceof EquipmentSlot slot ? orAir(get(slotOf(slot))) : get((Integer) first);
                case "setItem":
                    set(first instanceof EquipmentSlot slot ? slotOf(slot) : (Integer) first, (ItemStack) args[1]);
                    return null;
                case "addItem":
                    return add((ItemStack[]) invocation.getRawArguments()[0]);
                case "removeItem", "removeItemAnySlot":
                    return remove((ItemStack[]) invocation.getRawArguments()[0],
                            name.equals("removeItemAnySlot") ? slots.length : storage);
                case "getContents":
                    return slots.clone();
                case "setContents":
                    fill((ItemStack[]) invocation.getRawArguments()[0], 0, slots.length);
                    return null;
                case "getStorageContents":
                    return Arrays.copyOf(slots, storage);
                case "setStorageContents":
                    fill((ItemStack[]) invocation.getRawArguments()[0], 0, storage);
                    return null;
                case "getArmorContents":
                    return slots.length == PLAYER_SIZE ? Arrays.copyOfRange(slots, BOOTS, HELMET + 1) : new ItemStack[0];
                case "setArmorContents":
                    fill((ItemStack[]) invocation.getRawArguments()[0], BOOTS, 4);
                    return null;
                case "getExtraContents":
                    return slots.length == PLAYER_SIZE ? new ItemStack[]{slots[OFF_HAND]} : new ItemStack[0];
                case "setExtraContents":
                    fill((ItemStack[]) invocation.getRawArguments()[0], OFF_HAND, 1);
                    return null;
                case "getHelmet":
                    return get(HELMET);
                case "getChestplate":
                    return get(CHESTPLATE);
                case "getLeggings":
                    return get(LEGGINGS);
                case "getBoots":
                    return get(BOOTS);
                case "setHelmet":
                    set(HELMET, (ItemStack) first);
                    return null;
                case "setChestplate":
                    set(CHESTPLATE, (ItemStack) first);
                    return null;
                case "setLeggings":
                    set(LEGGINGS, (ItemStack) first);
                    return null;
                case "setBoots":
                    set(BOOTS, (ItemStack) first);
                    return null;
                case "getItemInMainHand", "getItemInHand":
                    return orAir(get(held));
                case "setItemInMainHand", "setItemInHand":
                    set(held, (ItemStack) first);
                    return null;
                case "getItemInOffHand":
                    return orAir(get(OFF_HAND));
                case "setItemInOffHand":
                    set(OFF_HAND, (ItemStack) first);
                    return null;
                case "getHeldItemSlot":
                    return held;
                case "setHeldItemSlot":
                    int slot = (Integer) first;
                    if (slot < 0 || slot > 8) {
                        throw new IllegalArgumentException("Slot is not between 0 and 8 inclusive");
                    }
                    held = slot;
                    return null;
                case "contains":
                    return contains(args);
                case "containsAtLeast":
                    return first != null && amountSimilarTo((ItemStack) first) >= (Integer) args[1];
                case "all":
                    return all(first);
                case "first":
                    for (int i = 0; i < storage; i++) {
                        if (first instanceof Material material ? slots[i] != null && slots[i].getType() == material
                                : Objects.equals(slots[i], first)) {
                            return i;
                        }
                    }
                    return -1;
                case "firstEmpty":
                    return firstEmpty();
                case "isEmpty":
                    return Arrays.stream(slots).allMatch(Objects::isNull);
                case "remove":
                    for (int i = 0; i < storage; i++) {
                        if (first instanceof Material material ? slots[i] != null && slots[i].getType() == material
                                : Objects.equals(slots[i], first)) {
                            slots[i] = null;
                        }
                    }
                    return null;
                case "clear":
                    if (args.length == 1) {
                        slots[(Integer) first] = null;
                    } else {
                        Arrays.fill(slots, null);
                    }
                    return null;
                case "close":
                    return 0;
                case "getViewers":
                    return new ArrayList<HumanEntity>();
                case "getType":
                    return type;
                case "getHolder":
                    return holder;
                case "iterator":
                    return iterator(invocation, args.length == 1 ? (Integer) first : 0);
                case "getLocation":
                    return null;
                case "toString":
                    return type + Arrays.toString(slots);
                default:
                    return Mockito.RETURNS_DEFAULTS.answer(invocation);
            }
        }

        private ItemStack get(int slot) {
            return slots[slot];
        }

        private void set(int slot, ItemStack stack) {
            if (slot < 0 || slot >= slots.length) {
                throw new ArrayIndexOutOfBoundsException("Slot " + slot + " is outside 0.." + (slots.length - 1));
            }
            slots[slot] = isEmpty(stack) ? null : copy(stack);
        }

        private void fill(ItemStack[] stacks, int from, int count) {
            if (stacks.length > count) {
                throw new IllegalArgumentException("Invalid inventory size (" + stacks.length + "); expected " + count
                        + " or less");
            }
            for (int i = 0; i < count; i++) {
                set(from + i, i < stacks.length ? stacks[i] : null);
            }
        }

        private int slotOf(EquipmentSlot slot) {
            return switch (slot) {
                case HAND -> held;
                case OFF_HAND -> OFF_HAND;
                case FEET -> BOOTS;
                case LEGS -> LEGGINGS;
                case CHEST -> CHESTPLATE;
                case HEAD -> HELMET;
                default -> throw new IllegalArgumentException(slot + " is not a slot of a player's inventory");
            };
        }

        private HashMap<Integer, ItemStack> add(ItemStack[] stacks) {
            HashMap<Integer, ItemStack> leftOver = new HashMap<>();
            for (int index = 0; index < stacks.length; index++) {
                ItemStack stack = Objects.requireNonNull(stacks[index], "Item cannot be null");
                if (isEmpty(stack)) {
                    continue;
                }
                int left = stack.getAmount();
                int max = Math.min(maxStackSize, stack.getMaxStackSize() <= 0 ? 64 : stack.getMaxStackSize());
                for (int i = 0; i < storage && left > 0; i++) {
                    if (slots[i] != null && slots[i].isSimilar(stack) && slots[i].getAmount() < max) {
                        int moved = Math.min(left, max - slots[i].getAmount());
                        slots[i].setAmount(slots[i].getAmount() + moved);
                        left -= moved;
                    }
                }
                while (left > 0) {
                    int empty = firstEmpty();
                    if (empty < 0) {
                        leftOver.put(index, withAmount(stack, left));
                        break;
                    }
                    int placed = Math.min(left, max);
                    slots[empty] = withAmount(stack, placed);
                    left -= placed;
                }
            }
            return leftOver;
        }

        private HashMap<Integer, ItemStack> remove(ItemStack[] stacks, int within) {
            HashMap<Integer, ItemStack> notFound = new HashMap<>();
            for (int index = 0; index < stacks.length; index++) {
                ItemStack stack = Objects.requireNonNull(stacks[index], "Item cannot be null");
                int left = stack.getAmount();
                for (int i = 0; i < within && left > 0; i++) {
                    if (slots[i] != null && slots[i].isSimilar(stack)) {
                        int taken = Math.min(left, slots[i].getAmount());
                        left -= taken;
                        if (taken == slots[i].getAmount()) {
                            slots[i] = null;
                        } else {
                            slots[i].setAmount(slots[i].getAmount() - taken);
                        }
                    }
                }
                if (left > 0) {
                    notFound.put(index, withAmount(stack, left));
                }
            }
            return notFound;
        }

        private boolean contains(Object[] args) {
            Object what = args[0];
            if (what == null) {
                return false;
            }
            int wanted = args.length == 2 ? (Integer) args[1] : 1;
            if (wanted <= 0) {
                return true;
            }
            int found = 0;
            for (int i = 0; i < storage; i++) {
                ItemStack slot = slots[i];
                if (slot == null) {
                    continue;
                }
                if (what instanceof Material material) {
                    found += slot.getType() == material ? slot.getAmount() : 0;
                } else if (slot.equals(what)) {
                    // contains(stack, n): n stacks exactly like it, as on a server.
                    found += args.length == 2 ? 1 : wanted;
                }
            }
            return found >= wanted;
        }

        private int amountSimilarTo(ItemStack stack) {
            int found = 0;
            for (int i = 0; i < storage; i++) {
                if (slots[i] != null && slots[i].isSimilar(stack)) {
                    found += slots[i].getAmount();
                }
            }
            return found;
        }

        private HashMap<Integer, ItemStack> all(Object what) {
            HashMap<Integer, ItemStack> found = new HashMap<>();
            for (int i = 0; i < storage; i++) {
                if (slots[i] != null && (what instanceof Material material ? slots[i].getType() == material
                        : slots[i].equals(what))) {
                    found.put(i, slots[i]);
                }
            }
            return found;
        }

        private int firstEmpty() {
            for (int i = 0; i < storage; i++) {
                if (slots[i] == null) {
                    return i;
                }
            }
            return -1;
        }

        private ListIterator<ItemStack> iterator(InvocationOnMock invocation, int start) {
            Inventory inventory = (Inventory) invocation.getMock();
            return new ListIterator<>() {
                private int next = start;
                private int last = -1;

                @Override
                public boolean hasNext() {
                    return next < slots.length;
                }

                @Override
                public ItemStack next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    last = next;
                    return slots[next++];
                }

                @Override
                public boolean hasPrevious() {
                    return next > 0;
                }

                @Override
                public ItemStack previous() {
                    if (!hasPrevious()) {
                        throw new NoSuchElementException();
                    }
                    last = --next;
                    return slots[next];
                }

                @Override
                public int nextIndex() {
                    return next;
                }

                @Override
                public int previousIndex() {
                    return next - 1;
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException("Can't change the size of an inventory");
                }

                @Override
                public void set(ItemStack stack) {
                    if (last < 0) {
                        throw new IllegalStateException("No current item");
                    }
                    inventory.setItem(last, stack);
                }

                @Override
                public void add(ItemStack stack) {
                    throw new UnsupportedOperationException("Can't change the size of an inventory");
                }
            };
        }

        private static ItemStack orAir(ItemStack stack) {
            return stack == null ? TestItems.air() : stack;
        }

        private static boolean isEmpty(ItemStack stack) {
            if (stack == null) {
                return true;
            }
            TestItemStack test = TestItemStack.unwrap(stack);
            return test != null ? test.isEmpty() : stack.getType() == Material.AIR;
        }

        // Copies of the testkit's stacks; anything else (a plain mock) is kept as given.
        private static ItemStack copy(ItemStack stack) {
            TestItemStack test = TestItemStack.unwrap(stack);
            return test == null ? stack : test.clone();
        }

        private static ItemStack withAmount(ItemStack stack, int amount) {
            TestItemStack test = TestItemStack.unwrap(stack);
            if (test == null) {
                return stack;
            }
            TestItemStack copy = test.clone();
            copy.setAmount(amount);
            return copy;
        }
    }

    /** The stacks in an inventory, in slot order, empty slots left out — handy in assertions. */
    public static List<ItemStack> stacksIn(Inventory inventory) {
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null) {
                stacks.add(stack);
            }
        }
        return stacks;
    }
}
