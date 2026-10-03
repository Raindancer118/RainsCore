package de.raindancer.core.testkit;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Item stacks for unit tests, no server needed.
 *
 * <pre>{@code
 * ItemStack compass = TestItems.of(Material.COMPASS);
 * compass.editMeta(meta -> meta.displayName(Component.text("Tracker")));
 * trackers.tag(compass, "hunter");
 * assertThat(trackers.is(compass, "hunter")).isTrue();
 * }</pre>
 *
 * <p>With the testkit on the test classpath the code under test needs nothing either:
 * {@code new ItemStack(Material.X)}, {@code ItemStack.of(...)}, {@code getItemMeta()/setItemMeta()},
 * {@code editMeta}, persistent data, enchantments, lore, flags and attribute modifiers all work and
 * remember what was set. Meta is handed out as a copy and only lands on the item when set back, exactly
 * as on a server — so a test catches a forgotten {@code setItemMeta}. Similar stacks are
 * {@link ItemStack#isSimilar similar} and equal stacks {@code equals}.
 *
 * <p>What still needs a server (data components, serialising an item, tooltips) throws
 * {@link UnsupportedOperationException} naming the method, so a test fails loudly rather than reading
 * a made-up value. Max stack sizes follow vanilla's rules by name (tools and armour 1, pearls and signs
 * 16, the rest 64). An empty stack is {@link #air()}; {@code new ItemStack(Material.AIR)} and
 * {@code ItemStack.empty()} still need a server.
 */
public final class TestItems {

    private TestItems() {
    }

    /** One of this item. */
    public static ItemStack of(Material material) {
        return of(material, 1);
    }

    /** This many of this item. */
    public static ItemStack of(Material material, int amount) {
        Objects.requireNonNull(material, "material");
        if (!material.isItem()) {
            throw new IllegalArgumentException(material + " isn't an item");
        }
        return new TestItemStack(material, amount, TestItemStack.metaTypeOf(material));
    }

    /** One of this item, its meta edited first. */
    public static ItemStack of(Material material, Consumer<? super ItemMeta> edit) {
        ItemStack stack = of(material);
        stack.editMeta(edit);
        return stack;
    }

    /** One of this item carrying a text value under a key — the usual way a plugin marks its items. */
    public static ItemStack tagged(Material material, NamespacedKey key, String value) {
        ItemStack stack = of(material);
        stack.editPersistentDataContainer(data -> data.set(key, PersistentDataType.STRING, value));
        return stack;
    }

    /** An empty stack, as an empty hand or slot reads. */
    public static ItemStack air() {
        return new TestItemStack(Material.AIR, 0, ItemMeta.class);
    }

    /**
     * A meta of the kind this item has (a {@code CompassMeta} for a compass), not on any item — to set
     * on one with {@code setItemMeta}, or to hand to code that takes a meta.
     */
    public static ItemMeta meta(Material material) {
        return new MetaState(TestItemStack.metaTypeOf(material)).toMeta();
    }

    /** A meta of this type, not on any item. */
    public static <M extends ItemMeta> M meta(Class<M> type) {
        return type.cast(new MetaState(type).toMeta());
    }

    /** A persistent data container on its own — for an entity, a chunk or a block's state. */
    public static PersistentDataContainer data() {
        return new MemoryDataContainer();
    }

    /** Whether this stack, or what it wraps, is one of the testkit's. */
    public static boolean isTestStack(ItemStack stack) {
        return TestItemStack.unwrap(stack) != null;
    }

    /** How many of this item fit in a stack in tests. */
    public static int maxStackOf(Material material) {
        return Keyeds.maxStack(material.getKey().getKey());
    }
}
