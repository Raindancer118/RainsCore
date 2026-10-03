package de.raindancer.core.testkit;

import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.tag.Tag;
import io.papermc.paper.registry.tag.TagKey;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** One registry: entries made on first use, the same object for a key ever after. */
final class FakeRegistry<T extends Keyed> implements Registry<T> {

    private final RegistryKey<?> registryKey;
    private final Class<?> element;
    // The entries the API names as constants, with the type each is declared as (often a Typed subtype).
    private final Map<NamespacedKey, Field> declared;
    // Items and blocks have exactly those entries; other registries take any key.
    private final boolean strict;
    private final Map<NamespacedKey, T> entries = new ConcurrentHashMap<>();

    FakeRegistry(RegistryKey<?> registryKey, Class<?> element, boolean strict) {
        this.registryKey = registryKey;
        this.element = element;
        this.declared = declaredIn(element);
        this.strict = strict;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T get(NamespacedKey key) {
        if (key == null) {
            return null;
        }
        if (strict && !declared.containsKey(key)) {
            return null;
        }
        T known = entries.get(key);
        if (known != null) {
            return known;
        }
        // Not computeIfAbsent: making the first item mock initialises ItemType, whose constants come
        // straight back here for their own entries.
        T made = (T) Keyeds.create(registryKey, element, declared.get(key), key);
        T raced = entries.putIfAbsent(key, made);
        return raced == null ? made : raced;
    }

    @Override
    public NamespacedKey getKey(T value) {
        return value == null ? null : value.getKey();
    }

    @Override
    public boolean hasTag(TagKey<T> key) {
        return false;
    }

    @Override
    public Tag<T> getTag(TagKey<T> key) {
        throw new IllegalArgumentException("The testkit's registries have no tags; " + key + " is not one");
    }

    @Override
    public Collection<Tag<T>> getTags() {
        return List.of();
    }

    @Override
    public Stream<T> stream() {
        return strict ? declared.keySet().stream().map(this::get) : List.copyOf(entries.values()).stream();
    }

    @Override
    public Stream<NamespacedKey> keyStream() {
        return strict ? declared.keySet().stream() : List.copyOf(entries.keySet()).stream();
    }

    @Override
    public int size() {
        return strict ? declared.size() : entries.size();
    }

    @Override
    public Iterator<T> iterator() {
        return stream().iterator();
    }

    @Override
    public String toString() {
        return "TestRegistry[" + registryKey + "]";
    }

    private static Map<NamespacedKey, Field> declaredIn(Class<?> element) {
        Map<NamespacedKey, Field> declared = new LinkedHashMap<>();
        for (Field field : element.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && element.isAssignableFrom(field.getType())) {
                declared.put(NamespacedKey.minecraft(field.getName().toLowerCase(Locale.ROOT)), field);
            }
        }
        return Collections.unmodifiableMap(declared);
    }
}
