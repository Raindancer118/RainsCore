package de.raindancer.core.testkit;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Keyed;
import org.bukkit.Registry;
import org.bukkit.block.BlockType;
import org.bukkit.inventory.ItemType;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The registries a server would provide, so {@code Material.COMPASS.isItem()}, {@code Attribute.ARMOR},
 * {@code Enchantment.SHARPNESS}, {@code new ItemStack(Material.DIAMOND)} and {@code ItemStack.of(...)}
 * work in a plain unit test.
 *
 * <p>Found by Paper's own {@code ServiceLoader} lookup: having the testkit on the test classpath is the
 * whole setup. Items and blocks know exactly the entries the API names ({@code ItemType.COMPASS} exists,
 * {@code ItemType.WATER} does not, so {@code Material.WATER.isItem()} is false, as on a server); every
 * other registry hands out an entry for any key, the same one each time, so {@code ==} holds.
 *
 * <p>Entries are mocks that know their key: anything about them beyond that (an enchantment's max level,
 * a sound's volume) is Mockito's default unless stubbed. Items create the testkit's
 * {@linkplain TestItems stacks}.
 */
public final class TestRegistries implements RegistryAccess {

    private static final Map<RegistryKey<?>, FakeRegistry<?>> REGISTRIES = new ConcurrentHashMap<>();

    /** For ServiceLoader. */
    public TestRegistries() {
    }

    /** The registry for these entries, as the server-less API sees it. */
    @SuppressWarnings("unchecked")
    public static <T extends Keyed> Registry<T> registry(RegistryKey<T> key) {
        return (Registry<T>) REGISTRIES.computeIfAbsent(key, TestRegistries::create);
    }

    @Override
    public <T extends Keyed> Registry<T> getRegistry(RegistryKey<T> registryKey) {
        return registry(registryKey);
    }

    @SuppressWarnings({"unchecked", "removal"})
    @Deprecated
    public <T extends Keyed> Registry<T> getRegistry(Class<T> type) {
        for (Field field : RegistryKey.class.getFields()) {
            if (elementType(field) == type) {
                try {
                    return registry((RegistryKey<T>) field.get(null));
                } catch (IllegalAccessException unreachable) {
                    throw new IllegalStateException(unreachable);
                }
            }
        }
        return null;
    }

    private static FakeRegistry<?> create(RegistryKey<?> key) {
        Class<?> element = Keyed.class;
        for (Field field : RegistryKey.class.getFields()) {
            try {
                if (Modifier.isStatic(field.getModifiers()) && field.get(null) == key) {
                    element = elementType(field);
                    break;
                }
            } catch (IllegalAccessException unreachable) {
                throw new IllegalStateException(unreachable);
            }
        }
        boolean strict = element == ItemType.class || element == BlockType.class;
        return new FakeRegistry<>(key, element, strict);
    }

    private static Class<?> elementType(Field field) {
        Type type = field.getGenericType();
        if (type instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> element) {
            return element;
        }
        return Keyed.class;
    }
}
