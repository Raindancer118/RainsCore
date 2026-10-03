package de.raindancer.core.testkit;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import static org.mockito.Mockito.withSettings;

/**
 * What an item's meta holds. Each {@code ItemMeta} handed out is a fresh mock over a copy of this, as a
 * server hands out a copy: nothing changes on the item until it is set back.
 */
final class MetaState {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    final Class<? extends ItemMeta> type;
    final Map<String, Object> properties = new TreeMap<>();
    final Map<Enchantment, Integer> enchants = new LinkedHashMap<>();
    final Set<ItemFlag> flags = EnumSet.noneOf(ItemFlag.class);
    final Multimap<Attribute, AttributeModifier> attributes = LinkedHashMultimap.create();
    final MemoryDataContainer data;

    MetaState(Class<? extends ItemMeta> type) {
        this(type, new MemoryDataContainer());
    }

    private MetaState(Class<? extends ItemMeta> type, MemoryDataContainer data) {
        this.type = type == null ? ItemMeta.class : type;
        this.data = data;
    }

    MetaState copy() {
        MetaState copy = new MetaState(type, data.copy());
        properties.forEach((name, value) -> copy.properties.put(name, value instanceof List<?> list ? List.copyOf(list) : value));
        copy.enchants.putAll(enchants);
        copy.flags.addAll(flags);
        copy.attributes.putAll(attributes);
        return copy;
    }

    /** The same contents in another meta type — what a server does when an item's type changes. */
    MetaState as(Class<? extends ItemMeta> other) {
        MetaState copy = new MetaState(other, data.copy());
        copy.properties.putAll(copy().properties);
        copy.enchants.putAll(enchants);
        copy.flags.addAll(flags);
        copy.attributes.putAll(attributes);
        return copy;
    }

    boolean isBlank() {
        return properties.isEmpty() && enchants.isEmpty() && flags.isEmpty() && attributes.isEmpty() && data.isEmpty();
    }

    /** A meta to hand out. Every real meta is also Damageable and Repairable, so these are too. */
    ItemMeta toMeta() {
        List<Class<?>> extra = new ArrayList<>();
        for (Class<?> always : List.of(Damageable.class, Repairable.class)) {
            if (!always.isAssignableFrom(type)) {
                extra.add(always);
            }
        }
        return Mockito.mock(type, withSettings().name("ItemMeta " + this)
                .extraInterfaces(extra.toArray(Class<?>[]::new))
                .defaultAnswer(new MetaAnswer(this)));
    }

    /** The state behind a meta the testkit made, or null for one it did not. */
    static MetaState of(ItemMeta meta) {
        if (meta == null || !Mockito.mockingDetails(meta).isMock()) {
            return null;
        }
        return Mockito.mockingDetails(meta).getMockCreationSettings().getDefaultAnswer() instanceof MetaAnswer answer
                ? answer.state : null;
    }

    Component displayName() {
        return (Component) properties.get("customName");
    }

    Component itemName() {
        return (Component) properties.get("itemName");
    }

    @SuppressWarnings("unchecked")
    List<Component> lore() {
        return (List<Component>) properties.get("lore");
    }

    void lore(List<? extends Component> lines) {
        if (lines == null) {
            properties.remove("lore");
        } else {
            properties.put("lore", List.copyOf(lines));
        }
    }

    boolean addEnchant(Enchantment enchantment, int level) {
        Integer before = enchants.put(Objects.requireNonNull(enchantment, "enchantment"), level);
        return before == null || before != level;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MetaState that && type == that.type && properties.equals(that.properties)
                && enchants.equals(that.enchants) && flags.equals(that.flags)
                && attributes.equals(that.attributes) && data.equals(that.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, properties, enchants, flags, attributes, data);
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder(type.getSimpleName()).append('{');
        List<String> parts = new ArrayList<>();
        properties.forEach((name, value) -> parts.add(name + "=" + value));
        if (!enchants.isEmpty()) {
            parts.add("enchants=" + enchants);
        }
        if (!flags.isEmpty()) {
            parts.add("flags=" + flags);
        }
        if (!attributes.isEmpty()) {
            parts.add("attributes=" + attributes);
        }
        if (!data.isEmpty()) {
            parts.add("data=" + data);
        }
        return text.append(String.join(", ", parts)).append('}').toString();
    }

    /**
     * Answers the meta's methods from the state. The named ones are done properly; any other
     * {@code getX/isX/hasX/setX} — or the {@code x()/x(value)} style — is a stored property, so a
     * CompassMeta's lodestone or a SkullMeta's owner just work. Anything else is Mockito's default and
     * can be stubbed as on any mock.
     */
    static final class MetaAnswer implements Answer<Object> {
        final MetaState state;

        MetaAnswer(MetaState state) {
            this.state = state;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object answer(InvocationOnMock invocation) throws Throwable {
            Method method = invocation.getMethod();
            String name = method.getName();
            Object[] args = invocation.getArguments();
            switch (name) {
                case "getPersistentDataContainer":
                    return state.data;
                case "clone":
                    return state.copy().toMeta();
                case "toString", "getAsString", "getAsComponentString":
                    return state.toString();
                case "hashCode":
                    return System.identityHashCode(invocation.getMock());
                case "equals":
                    return args[0] == invocation.getMock();
                case "hasDisplayName", "hasCustomName":
                    return state.properties.containsKey("customName");
                case "displayName", "customName":
                    if (args.length == 0) {
                        return state.displayName();
                    }
                    put("customName", args[0]);
                    return null;
                case "getDisplayName":
                    return state.displayName() == null ? "" : LEGACY.serialize(state.displayName());
                case "setDisplayName":
                    put("customName", args[0] == null ? null : LEGACY.deserialize((String) args[0]));
                    return null;
                case "itemName":
                    if (args.length == 0) {
                        return state.itemName();
                    }
                    put("itemName", args[0]);
                    return null;
                case "getItemName":
                    return state.itemName() == null ? "" : LEGACY.serialize(state.itemName());
                case "setItemName":
                    put("itemName", args[0] == null ? null : LEGACY.deserialize((String) args[0]));
                    return null;
                case "hasLore":
                    return state.lore() != null && !state.lore().isEmpty();
                case "lore":
                    if (args.length == 0) {
                        return state.lore() == null ? null : new ArrayList<>(state.lore());
                    }
                    state.lore((List<? extends Component>) args[0]);
                    return null;
                case "getLore":
                    return state.lore() == null ? null : new ArrayList<>(state.lore().stream().map(LEGACY::serialize).toList());
                case "setLore":
                    state.lore(args[0] == null ? null : ((List<String>) args[0]).stream()
                            .map(line -> (Component) LEGACY.deserialize(line)).toList());
                    return null;
                case "hasEnchants":
                    return !state.enchants.isEmpty();
                case "hasEnchant":
                    return state.enchants.containsKey((Enchantment) args[0]);
                case "getEnchantLevel":
                    return state.enchants.getOrDefault((Enchantment) args[0], 0);
                case "getEnchants":
                    return Map.copyOf(state.enchants);
                case "addEnchant":
                    return state.addEnchant((Enchantment) args[0], (Integer) args[1]);
                case "removeEnchant":
                    return state.enchants.remove((Enchantment) args[0]) != null;
                case "removeEnchantments":
                    state.enchants.clear();
                    return null;
                case "hasConflictingEnchant":
                    return false;
                case "addItemFlags":
                    Collections.addAll(state.flags, (ItemFlag[]) invocation.getRawArguments()[0]);
                    return null;
                case "removeItemFlags":
                    for (ItemFlag flag : (ItemFlag[]) invocation.getRawArguments()[0]) {
                        state.flags.remove(flag);
                    }
                    return null;
                case "getItemFlags":
                    return Collections.unmodifiableSet(new LinkedHashSet<>(state.flags));
                case "hasItemFlag":
                    return state.flags.contains((ItemFlag) args[0]);
                case "hasAttributeModifiers":
                    return !state.attributes.isEmpty();
                case "getAttributeModifiers":
                    return attributes(args);
                case "addAttributeModifier":
                    return addAttribute((Attribute) args[0], (AttributeModifier) args[1]);
                case "setAttributeModifiers":
                    state.attributes.clear();
                    if (args[0] != null) {
                        ((Multimap<Attribute, AttributeModifier>) args[0]).forEach(this::addAttribute);
                    }
                    return null;
                case "removeAttributeModifier":
                    return removeAttribute(args);
                case "serialize":
                    throw new UnsupportedOperationException("Serialising item meta needs a server");
                default:
                    return property(invocation, method, name, args);
            }
        }

        private Object attributes(Object[] args) {
            if (args.length == 0) {
                return state.attributes.isEmpty() ? null : ImmutableMultimap.copyOf(state.attributes);
            }
            if (args[0] instanceof Attribute attribute) {
                Collection<AttributeModifier> modifiers = state.attributes.get(attribute);
                return modifiers.isEmpty() ? null : List.copyOf(modifiers);
            }
            EquipmentSlot slot = (EquipmentSlot) args[0];
            ImmutableMultimap.Builder<Attribute, AttributeModifier> inSlot = ImmutableMultimap.builder();
            state.attributes.forEach((attribute, modifier) -> {
                if (modifier.getSlotGroup().test(slot)) {
                    inSlot.put(attribute, modifier);
                }
            });
            return inSlot.build();
        }

        private boolean addAttribute(Attribute attribute, AttributeModifier modifier) {
            Objects.requireNonNull(attribute, "attribute");
            Objects.requireNonNull(modifier, "modifier");
            for (AttributeModifier present : state.attributes.get(attribute)) {
                if (present.getKey().equals(modifier.getKey())) {
                    throw new IllegalArgumentException("Cannot register AttributeModifier. Modifier is already applied! "
                            + modifier);
                }
            }
            return state.attributes.put(attribute, modifier);
        }

        private boolean removeAttribute(Object[] args) {
            if (args.length == 2) {
                Collection<AttributeModifier> modifiers = state.attributes.get((Attribute) args[0]);
                AttributeModifier gone = (AttributeModifier) args[1];
                return modifiers.removeIf(modifier -> modifier.getKey().equals(gone.getKey()));
            }
            if (args[0] instanceof Attribute attribute) {
                return !state.attributes.removeAll(attribute).isEmpty();
            }
            EquipmentSlot slot = (EquipmentSlot) args[0];
            return state.attributes.entries().removeIf(entry -> entry.getValue().getSlotGroup().test(slot));
        }

        private void put(String property, Object value) {
            if (value == null) {
                state.properties.remove(property);
            } else {
                state.properties.put(property, value);
            }
        }

        private Object property(InvocationOnMock invocation, Method method, String name, Object[] args) throws Throwable {
            Class<?> returns = method.getReturnType();
            if (args.length == 1 && name.startsWith("set") && name.length() > 3) {
                put(decapitalise(name.substring(3)), args[0]);
                return returns == boolean.class ? Boolean.TRUE : Mockito.RETURNS_DEFAULTS.answer(invocation);
            }
            if (args.length == 0 && name.startsWith("has") && name.length() > 3 && returns == boolean.class) {
                Object value = state.properties.get(decapitalise(name.substring(3)));
                return value != null && !(value instanceof Collection<?> c && c.isEmpty());
            }
            String property = null;
            if (args.length == 0 && name.startsWith("get") && name.length() > 3) {
                property = decapitalise(name.substring(3));
            } else if (args.length == 0 && name.startsWith("is") && name.length() > 2) {
                property = decapitalise(name.substring(2));
            } else if (args.length == 0 && returns != void.class) {
                property = name;
            } else if (args.length == 1 && returns == void.class) {
                put(name, args[0]);
                return null;
            }
            if (property != null) {
                Object value = state.properties.get(property);
                if (value != null && box(returns).isInstance(value)) {
                    return value instanceof List<?> list ? new ArrayList<>(list)
                            : value instanceof Set<?> set ? new LinkedHashSet<>(set) : value;
                }
            }
            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        }

        private static String decapitalise(String text) {
            return text.substring(0, 1).toLowerCase(Locale.ROOT) + text.substring(1);
        }

        private static Class<?> box(Class<?> type) {
            if (!type.isPrimitive()) {
                return type;
            }
            return switch (type.getName()) {
                case "boolean" -> Boolean.class;
                case "int" -> Integer.class;
                case "long" -> Long.class;
                case "double" -> Double.class;
                case "float" -> Float.class;
                case "short" -> Short.class;
                case "byte" -> Byte.class;
                case "char" -> Character.class;
                default -> Void.class;
            };
        }
    }
}
