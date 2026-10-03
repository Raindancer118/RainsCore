package de.raindancer.core.testkit;

import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.BlockType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.inventory.meta.ItemMeta;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import static org.mockito.Mockito.withSettings;

/** Registry entries: mocks that know their key, and for items and blocks a little more. */
final class Keyeds {

    private static final Set<String> AIRS = Set.of("air", "cave_air", "void_air");

    private Keyeds() {
    }

    static Object create(RegistryKey<?> registry, Class<?> element, Field declaredAs, NamespacedKey key) {
        if (element == ItemType.class) {
            return Mockito.mock(ItemType.Typed.class, withSettings().name("ItemType " + key)
                    .defaultAnswer(new ItemTypeAnswer(key, metaClassOf(declaredAs))));
        }
        if (element == BlockType.class) {
            return Mockito.mock(BlockType.Typed.class, withSettings().name("BlockType " + key)
                    .defaultAnswer(new BlockTypeAnswer(key)));
        }
        Class<?> shape = declaredAs != null && element.isAssignableFrom(declaredAs.getType()) ? declaredAs.getType() : element;
        return Mockito.mock(shape, withSettings().name(registry.key().value() + " " + key)
                .defaultAnswer(new KeyedAnswer(key, registry.key().value())));
    }

    /** How many fit in a stack, by vanilla's rules — tools, armour and the like 1, pearls and signs 16. */
    static int maxStack(String path) {
        if (path.endsWith("_sword") || path.endsWith("_pickaxe") || path.endsWith("_axe")
                || path.endsWith("_shovel") || path.endsWith("_hoe") || path.endsWith("_helmet")
                || path.endsWith("_chestplate") || path.endsWith("_leggings") || path.endsWith("_boots")
                || path.endsWith("_horse_armor") || path.endsWith("_boat") || path.endsWith("_raft")
                || path.endsWith("minecart") || path.endsWith("_bed") || path.endsWith("shulker_box")
                || path.startsWith("music_disc_") || path.endsWith("_bucket") || path.endsWith("potion")
                || path.endsWith("_spear") || path.endsWith("_harness") || path.endsWith("_bundle")) {
            return 1;
        }
        return switch (path) {
            case "bow", "crossbow", "trident", "shield", "elytra", "mace", "fishing_rod", "shears",
                 "flint_and_steel", "carrot_on_a_stick", "warped_fungus_on_a_stick", "saddle",
                 "totem_of_undying", "enchanted_book", "writable_book", "knowledge_book", "brush",
                 "spyglass", "goat_horn", "bundle", "debug_stick", "turtle_helmet", "wolf_armor",
                 "cake", "mushroom_stew", "rabbit_stew", "beetroot_soup", "suspicious_stew" -> 1;
            case "ender_pearl", "snowball", "egg", "blue_egg", "brown_egg", "bucket", "honey_bottle",
                 "armor_stand", "written_book", "wind_charge" -> 16;
            default -> path.endsWith("_sign") || path.endsWith("_hanging_sign") || path.endsWith("_banner") ? 16 : 64;
        };
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends ItemMeta> metaClassOf(Field declaredAs) {
        if (declaredAs != null && declaredAs.getGenericType() instanceof ParameterizedType typed
                && typed.getActualTypeArguments()[0] instanceof Class<?> meta && ItemMeta.class.isAssignableFrom(meta)) {
            return (Class<? extends ItemMeta>) meta;
        }
        return ItemMeta.class;
    }

    private static Material material(NamespacedKey key) {
        return Material.getMaterial(key.getKey().toUpperCase(Locale.ROOT));
    }

    /** What every entry answers: its key, its name, and Mockito's defaults for the rest. */
    static class KeyedAnswer implements Answer<Object> {
        final NamespacedKey key;
        private final String kind;

        KeyedAnswer(NamespacedKey key, String kind) {
            this.key = key;
            this.kind = kind;
        }

        @Override
        public Object answer(InvocationOnMock invocation) throws Throwable {
            String name = invocation.getMethod().getName();
            int arguments = invocation.getArguments().length;
            if (arguments == 0) {
                switch (name) {
                    case "getKey", "key", "getKeyOrThrow", "getKeyOrNull" -> {
                        return key;
                    }
                    case "isRegistered" -> {
                        return true;
                    }
                    case "translationKey", "getTranslationKey" -> {
                        return kind + "." + key.getNamespace() + "." + key.getKey();
                    }
                    case "name" -> {
                        return key.getKey().toUpperCase(Locale.ROOT);
                    }
                    case "toString" -> {
                        return key.toString();
                    }
                    default -> {
                    }
                }
            }
            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        }
    }

    private static final class ItemTypeAnswer extends KeyedAnswer {
        private final Class<? extends ItemMeta> meta;

        ItemTypeAnswer(NamespacedKey key, Class<? extends ItemMeta> meta) {
            super(key, "item");
            this.meta = meta;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Object answer(InvocationOnMock invocation) throws Throwable {
            Object[] arguments = invocation.getArguments();
            switch (invocation.getMethod().getName()) {
                case "createItemStack" -> {
                    int amount = arguments.length > 0 && arguments[0] instanceof Integer count ? count : 1;
                    TestItemStack stack = new TestItemStack(material(key), amount, meta);
                    Object last = arguments.length == 0 ? null : arguments[arguments.length - 1];
                    if (last instanceof Consumer edit) {
                        stack.editMeta(edit);
                    }
                    return (ItemStack) stack;
                }
                case "getItemMetaClass" -> {
                    return meta;
                }
                case "getMaxStackSize" -> {
                    return maxStack(key.getKey());
                }
                case "asMaterial" -> {
                    return material(key);
                }
                case "hasBlockType" -> {
                    return Registry.BLOCK.get(key) != null;
                }
                case "getBlockType" -> {
                    BlockType block = Registry.BLOCK.get(key);
                    if (block == null) {
                        throw new IllegalStateException(key + " is not a block");
                    }
                    return block;
                }
                case "typed" -> {
                    return invocation.getMock();
                }
                case "translationKey", "getTranslationKey" -> {
                    return (Registry.BLOCK.get(key) != null ? "block." : "item.") + key.getNamespace() + "." + key.getKey();
                }
                default -> {
                    return super.answer(invocation);
                }
            }
        }
    }

    private static final class BlockTypeAnswer extends KeyedAnswer {

        BlockTypeAnswer(NamespacedKey key) {
            super(key, "block");
        }

        @Override
        public Object answer(InvocationOnMock invocation) throws Throwable {
            switch (invocation.getMethod().getName()) {
                case "isAir" -> {
                    return AIRS.contains(key.getKey());
                }
                case "hasItemType" -> {
                    return Registry.ITEM.get(key) != null;
                }
                case "getItemType" -> {
                    ItemType item = Registry.ITEM.get(key);
                    if (item == null) {
                        throw new IllegalStateException(key + " has no item");
                    }
                    return item;
                }
                case "asMaterial" -> {
                    return material(key);
                }
                case "typed" -> {
                    return invocation.getMock();
                }
                default -> {
                    return super.answer(invocation);
                }
            }
        }
    }
}
