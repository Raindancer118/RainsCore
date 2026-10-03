package de.raindancer.core.testkit;

import io.papermc.paper.persistence.PersistentDataContainerView;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import static org.mockito.Mockito.withSettings;

/**
 * An item stack that lives without a server: type, amount, meta (name, lore, enchantments, flags,
 * attribute modifiers, persistent data and any other plain property), similarity, cloning and the
 * quantity helpers all behave as on a server. What it cannot do — data components, serialising,
 * tooltips — throws {@link UnsupportedOperationException} saying so, rather than returning nonsense.
 */
final class TestItemStack extends ItemStack {

    private static final Field DELEGATE;

    static {
        try {
            DELEGATE = ItemStack.class.getDeclaredField("craftDelegate");
            DELEGATE.setAccessible(true);
        } catch (NoSuchFieldException changed) {
            throw new ExceptionInInitializerError("Paper's ItemStack no longer has its craftDelegate field — "
                    + "the testkit needs updating for this API version");
        }
    }

    private Material type;
    private int amount;
    private MetaState meta;

    TestItemStack(Material type, int amount, Class<? extends ItemMeta> metaType) {
        this(type, amount, new MetaState(metaType));
    }

    private TestItemStack(Material type, int amount, MetaState meta) {
        this.type = Objects.requireNonNull(type, "type");
        this.amount = amount;
        this.meta = meta;
        try {
            DELEGATE.set(this, Unsupported.STACK);
        } catch (IllegalAccessException unreachable) {
            throw new IllegalStateException(unreachable);
        }
    }

    // Whatever is not overridden here falls through to ItemStack's delegate: make that say why it fails.
    private static final class Unsupported {
        static final ItemStack STACK = Mockito.mock(ItemStack.class, withSettings().defaultAnswer(invocation -> {
            throw new UnsupportedOperationException("ItemStack#" + invocation.getMethod().getName()
                    + " needs a server; the testkit's stacks cover type, amount, meta, enchantments, "
                    + "flags, lore, persistent data and similarity");
        }));
    }

    /** The testkit stack behind this one — itself, or what a {@code new ItemStack(...)} wraps. */
    static TestItemStack unwrap(ItemStack stack) {
        if (stack instanceof TestItemStack test) {
            return test;
        }
        if (stack == null || stack.getClass() != ItemStack.class) {
            return null;
        }
        try {
            return DELEGATE.get(stack) instanceof TestItemStack test ? test : null;
        } catch (IllegalAccessException unreachable) {
            throw new IllegalStateException(unreachable);
        }
    }

    MetaState state() {
        return meta;
    }

    @Override
    public Material getType() {
        return type;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void setType(Material type) {
        this.type = Objects.requireNonNull(type, "type");
        this.meta = meta.as(metaTypeOf(type));
    }

    @Override
    public ItemStack withType(Material type) {
        TestItemStack copy = clone();
        copy.setType(type);
        return copy;
    }

    @Override
    public int getAmount() {
        return amount;
    }

    @Override
    public void setAmount(int amount) {
        this.amount = amount;
    }

    @Override
    public int getMaxStackSize() {
        Object own = meta.properties.get("maxStackSize");
        return own instanceof Integer size ? size : Keyeds.maxStack(type.getKey().getKey());
    }

    @Override
    public boolean isEmpty() {
        return type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR || amount <= 0;
    }

    @Override
    public ItemMeta getItemMeta() {
        return isEmpty() ? null : meta.copy().toMeta();
    }

    @Override
    public boolean hasItemMeta() {
        return !isEmpty() && !meta.isBlank();
    }

    @Override
    public boolean setItemMeta(ItemMeta itemMeta) {
        if (itemMeta == null) {
            meta = new MetaState(meta.type);
            return true;
        }
        MetaState given = MetaState.of(itemMeta);
        if (given == null) {
            throw new IllegalArgumentException("That ItemMeta was not made by the testkit — take it from "
                    + "getItemMeta() or TestItems.meta(...), not mock(ItemMeta.class)");
        }
        meta = given.as(meta.type);
        return true;
    }

    @Override
    public boolean editMeta(Consumer<? super ItemMeta> consumer) {
        return editMeta(ItemMeta.class, consumer);
    }

    @Override
    public <M extends ItemMeta> boolean editMeta(Class<M> metaClass, Consumer<? super M> consumer) {
        ItemMeta current = getItemMeta();
        if (!metaClass.isInstance(current)) {
            return false;
        }
        consumer.accept(metaClass.cast(current));
        return setItemMeta(current);
    }

    @Override
    public PersistentDataContainerView getPersistentDataContainer() {
        return meta.data.view();
    }

    @Override
    public boolean editPersistentDataContainer(Consumer<PersistentDataContainer> consumer) {
        if (isEmpty()) {
            return false;
        }
        consumer.accept(meta.data);
        return true;
    }

    @Override
    public boolean containsEnchantment(Enchantment enchant) {
        return meta.enchants.containsKey(enchant);
    }

    @Override
    public int getEnchantmentLevel(Enchantment enchant) {
        return meta.enchants.getOrDefault(enchant, 0);
    }

    @Override
    public Map<Enchantment, Integer> getEnchantments() {
        return Map.copyOf(meta.enchants);
    }

    @Override
    public void addEnchantments(Map<Enchantment, Integer> enchantments) {
        enchantments.forEach(this::addEnchantment);
    }

    @Override
    public void addEnchantment(Enchantment enchantment, int level) {
        meta.addEnchant(enchantment, level);
    }

    @Override
    public void addUnsafeEnchantments(Map<Enchantment, Integer> enchantments) {
        enchantments.forEach(this::addEnchantment);
    }

    @Override
    public void addUnsafeEnchantment(Enchantment enchantment, int level) {
        meta.addEnchant(enchantment, level);
    }

    @Override
    public int removeEnchantment(Enchantment enchantment) {
        Integer level = meta.enchants.remove(enchantment);
        return level == null ? 0 : level;
    }

    @Override
    public void removeEnchantments() {
        meta.enchants.clear();
    }

    @Override
    public List<Component> lore() {
        return meta.lore() == null ? null : new ArrayList<>(meta.lore());
    }

    @Override
    public void lore(List<? extends Component> lore) {
        meta.lore(lore);
    }

    @Override
    @SuppressWarnings("deprecation")
    public List<String> getLore() {
        ItemMeta copy = getItemMeta();
        return copy == null ? null : copy.getLore();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void setLore(List<String> lore) {
        editMeta(edit -> edit.setLore(lore));
    }

    @Override
    public void addItemFlags(ItemFlag... itemFlags) {
        Collections.addAll(meta.flags, itemFlags);
    }

    @Override
    public void removeItemFlags(ItemFlag... itemFlags) {
        for (ItemFlag flag : itemFlags) {
            meta.flags.remove(flag);
        }
    }

    @Override
    public Set<ItemFlag> getItemFlags() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(meta.flags));
    }

    @Override
    public boolean hasItemFlag(ItemFlag flag) {
        return meta.flags.contains(flag);
    }

    @Override
    public Component effectiveName() {
        if (meta.displayName() != null) {
            return meta.displayName();
        }
        return meta.itemName() != null ? meta.itemName() : Component.translatable(translationKey());
    }

    @Override
    public Component displayName() {
        return Component.text("[").append(effectiveName()).append(Component.text("]"));
    }

    @Override
    public String translationKey() {
        return (type.isBlock() ? "block." : "item.") + type.getKey().getNamespace() + "." + type.getKey().getKey();
    }

    @Override
    @SuppressWarnings("deprecation")
    public String getTranslationKey() {
        return translationKey();
    }

    @Override
    public ItemStack asOne() {
        return asQuantity(1);
    }

    @Override
    public ItemStack asQuantity(int quantity) {
        TestItemStack copy = clone();
        copy.amount = quantity;
        return copy;
    }

    @Override
    public ItemStack add() {
        return add(1);
    }

    @Override
    public ItemStack add(int quantity) {
        amount = Math.min(getMaxStackSize(), amount + quantity);
        return this;
    }

    @Override
    public ItemStack subtract() {
        return subtract(1);
    }

    @Override
    public ItemStack subtract(int quantity) {
        amount = Math.max(0, amount - quantity);
        return this;
    }

    @Override
    public boolean isSimilar(ItemStack stack) {
        TestItemStack other = unwrap(stack);
        if (other == null) {
            return false;
        }
        return other == this || type == other.type && meta.equals(other.meta);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ItemStack stack && isSimilar(stack) && amount == stack.getAmount();
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, amount, meta);
    }

    @Override
    public TestItemStack clone() {
        return new TestItemStack(type, amount, meta.copy());
    }

    @Override
    public String toString() {
        return "ItemStack{" + type + " x " + amount + (meta.isBlank() ? "" : ", " + meta) + "}";
    }

    static Class<? extends ItemMeta> metaTypeOf(Material material) {
        ItemType item = material.asItemType();
        return item == null ? ItemMeta.class : item.getItemMetaClass();
    }
}
