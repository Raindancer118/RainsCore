package de.raindancer.core.content.items;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * One kind of a plugin's own items — a tracker compass, a lobby button, a kit's starting sword —
 * recognised by a tag in the item's data rather than by its name or material, and handled the same
 * way everywhere: tagged, found, counted, taken back, handed over, kept out of death drops.
 *
 * <pre>{@code
 * TaggedItems compasses = TaggedItems.of(plugin, "tracker").onlyOn(Material.COMPASS).bound();
 *
 * ItemStack compass = compasses.tag(new ItemStack(Material.COMPASS), "hunter");
 * compasses.giveIfMissing(player, "hunter", () -> freshCompass());   // a respawn, a login
 * compasses.removeAll(player, value -> true);                        // the hunt ended
 * }</pre>
 *
 * <h2>Why a value as well as a key</h2>
 * One key per kind of item, one value per variant — the hunters' compass and the runners' compass are
 * both "tracker". Every method that asks takes the value it is looking for, or a test on it.
 *
 * <h2>Bound</h2>
 * {@link #bound()} makes every item this tags a {@link BoundItems bound item} too, so it never leaves
 * its holder's own inventory — no chest, no frame, no death drop. That is usually what a plugin's own
 * item wants; it is a choice rather than the default because a reward item is a plugin's item too.
 *
 * <h2>Threads</h2>
 * Reading an item is safe anywhere. Everything that touches a player's inventory has to run on the
 * thread that owns the player, like any other inventory change.
 */
public final class TaggedItems {

    private final NamespacedKey key;
    private final Set<Material> materials;
    private final boolean bound;

    private TaggedItems(NamespacedKey key, Set<Material> materials, boolean bound) {
        this.key = Objects.requireNonNull(key, "key");
        this.materials = materials;
        this.bound = bound;
    }

    /** A kind of item of this plugin's, tagged under {@code plugin:name}. */
    public static TaggedItems of(Plugin plugin, String name) {
        return of(new NamespacedKey(plugin, name));
    }

    /** The same, by its full key. */
    public static TaggedItems of(NamespacedKey key) {
        return new TaggedItems(key, null, false);
    }

    /**
     * Only ever these materials — so asking about every other stack is answered without opening its
     * data, which matters when a sweep looks through every inventory several times a second.
     */
    public TaggedItems onlyOn(Material first, Material... more) {
        return new TaggedItems(key, EnumSet.of(first, more), bound);
    }

    /** Every item this tags is also a {@link BoundItems bound item}. */
    public TaggedItems bound() {
        return new TaggedItems(key, materials, true);
    }

    public NamespacedKey key() {
        return key;
    }

    public boolean isBound() {
        return bound;
    }

    // ---------------------------------------------------------------------------- tagging

    /** Tags a stack as one of these, with {@code value}, and hands it back for chaining into a give. */
    public ItemStack tag(ItemStack stack, String value) {
        if (stack == null) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        tag(meta, value);
        stack.setItemMeta(meta);
        return bound ? BoundItems.bind(stack) : stack;
    }

    /** Tags meta that is still being built. Binding, if asked for, happens in {@link #tag(ItemStack, String)}. */
    public void tag(ItemMeta meta, String value) {
        if (meta != null) {
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, value == null ? "" : value);
        }
    }

    // ---------------------------------------------------------------------------- recognising

    /**
     * The value this stack was tagged with, or empty for anything that is not one of these. Read
     * through the stack's own data view, which does not copy its meta.
     */
    public Optional<String> valueOf(ItemStack stack) {
        if (stack == null || (materials != null && !materials.contains(stack.getType()))) {
            return Optional.empty();
        }
        return Optional.ofNullable(stack.getPersistentDataContainer().get(key, PersistentDataType.STRING));
    }

    /** Whether it is one of these at all. */
    public boolean is(ItemStack stack) {
        return valueOf(stack).isPresent();
    }

    /** Whether it is the one tagged {@code value}. */
    public boolean is(ItemStack stack, String value) {
        return valueOf(stack).filter(found -> found.equals(value)).isPresent();
    }

    /** Whether it is one of these whose value passes {@code which}. */
    public boolean is(ItemStack stack, Predicate<String> which) {
        return valueOf(stack).filter(which == null ? found -> true : which).isPresent();
    }

    // ---------------------------------------------------------------------------- in an inventory

    /** The first slot holding one, if any. */
    public OptionalInt slotOf(Inventory inventory, Predicate<String> which) {
        ItemStack[] contents = contentsOf(inventory);
        for (int slot = 0; slot < contents.length; slot++) {
            if (is(contents[slot], which)) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    /** How many there are, counting each stack's amount. */
    public int count(Inventory inventory, Predicate<String> which) {
        int found = 0;
        for (ItemStack stack : contentsOf(inventory)) {
            if (is(stack, which)) {
                found += stack.getAmount();
            }
        }
        return found;
    }

    /** Whether they carry one — anywhere, the cursor included. */
    public boolean carries(Player player, Predicate<String> which) {
        return player != null && (slotOf(player.getInventory(), which).isPresent()
                || is(player.getItemOnCursor(), which));
    }

    /** Whether they hold one in either hand. */
    public boolean inHand(Player player, Predicate<String> which) {
        if (player == null) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        return is(inventory.getItemInMainHand(), which) || is(inventory.getItemInOffHand(), which);
    }

    /**
     * Takes every one of these out of an inventory.
     *
     * @return how many stacks were taken
     */
    public int removeAll(Inventory inventory, Predicate<String> which) {
        if (inventory == null) {
            return 0;
        }
        ItemStack[] contents = inventory.getContents();
        int taken = 0;
        for (int slot = 0; slot < contents.length; slot++) {
            if (is(contents[slot], which)) {
                inventory.setItem(slot, null);
                taken++;
            }
        }
        return taken;
    }

    /** The same for a player, the item on their cursor included — an open window does not hide one. */
    public int removeAll(Player player, Predicate<String> which) {
        if (player == null) {
            return 0;
        }
        int taken = removeAll(player.getInventory(), which);
        if (is(player.getItemOnCursor(), which)) {
            player.setItemOnCursor(null);
            taken++;
        }
        return taken;
    }

    /** Takes these out of a death's drops, so they never lie on the ground for somebody else. */
    public int removeFromDrops(List<ItemStack> drops, Predicate<String> which) {
        if (drops == null) {
            return 0;
        }
        int taken = 0;
        for (Iterator<ItemStack> each = drops.iterator(); each.hasNext(); ) {
            if (is(each.next(), which)) {
                each.remove();
                taken++;
            }
        }
        return taken;
    }

    // ---------------------------------------------------------------------------- handing over

    /** What became of a hand-over. */
    public record HandOver(int placed, List<Item> dropped) {

        /** Whether all of it went into the inventory. */
        public boolean fitted() {
            return dropped.isEmpty();
        }
    }

    /**
     * Into the inventory, or at their feet when it does not fit — never thrown away. {@code addItem}
     * answers with what it could not place rather than failing, and ignoring that answer is how an
     * item quietly vanishes. What has to be dropped belongs to them, so nobody else can pick it up.
     */
    public static HandOver handTo(Player player, ItemStack... stacks) {
        if (player == null || stacks == null || stacks.length == 0) {
            return new HandOver(0, List.of());
        }
        List<ItemStack> given = new ArrayList<>();
        int amount = 0;
        for (ItemStack stack : stacks) {
            if (stack != null) {
                given.add(stack);
                amount += stack.getAmount();
            }
        }
        Map<Integer, ItemStack> left = player.getInventory().addItem(given.toArray(ItemStack[]::new));
        List<Item> dropped = new ArrayList<>();
        int leftover = 0;
        for (ItemStack rest : left.values()) {
            leftover += rest.getAmount();
            Item item = player.getWorld().dropItem(player.getLocation(), rest);
            if (item != null) {
                item.setOwner(player.getUniqueId());
                dropped.add(item);
            }
        }
        return new HandOver(amount - leftover, List.copyOf(dropped));
    }

    /**
     * Hands one over only when they have none tagged {@code value} — what a respawn, a login or a
     * settings change wants, where handing over blindly gives a second copy for every repeat.
     *
     * @param make builds a fresh one; it is tagged here, so it need not be
     * @return whether one was handed over
     */
    public boolean giveIfMissing(Player player, String value, Supplier<ItemStack> make) {
        if (player == null || make == null || carries(player, found -> found.equals(value))) {
            return false;
        }
        ItemStack fresh = make.get();
        if (fresh == null) {
            return false;
        }
        if (!is(fresh, value)) {
            fresh = tag(fresh, value);
        }
        handTo(player, fresh);
        return true;
    }

    private static ItemStack[] contentsOf(Inventory inventory) {
        return inventory == null ? new ItemStack[0] : inventory.getContents();
    }
}
