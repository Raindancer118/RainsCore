package de.raindancer.core.data.loadout;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Every key of a player's persistent data in one namespace, as a side's part: written as lines
 * {@code key type base64}, one per key, of the plain types (text, numbers, arrays). Nested containers are left
 * where they are.
 */
final class NamespacePart implements SideData.Part {

    private final String namespace;

    NamespacePart(String namespace) {
        this.namespace = namespace;
    }

    @Override
    public String id() {
        return "pdc:" + namespace;
    }

    @Override
    public String capture(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        List<String> lines = new ArrayList<>();
        for (NamespacedKey key : data.getKeys()) {
            if (key.getNamespace().equals(namespace)) {
                read(data, key).ifPresent(value -> lines.add(PdcLine.write(key.getKey(), value)));
            }
        }
        return String.join("\n", lines);
    }

    @Override
    public void apply(Player player, String value) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        for (NamespacedKey key : new ArrayList<>(data.getKeys())) {
            if (key.getNamespace().equals(namespace)) {
                data.remove(key);
            }
        }
        if (value == null || value.isBlank()) {
            return;
        }
        for (String line : value.split("\n")) {
            PdcLine.read(line).ifPresent(entry -> {
                NamespacedKey key = NamespacedKey.fromString(namespace + ":" + entry.key());
                if (key != null) {
                    write(data, key, entry.value());
                }
            });
        }
    }

    private static Optional<PdcLine.Typed> read(PersistentDataContainer data, NamespacedKey key) {
        if (data.has(key, PersistentDataType.STRING)) {
            return Optional.of(new PdcLine.Typed("s", data.get(key, PersistentDataType.STRING)));
        }
        if (data.has(key, PersistentDataType.INTEGER)) {
            return Optional.of(new PdcLine.Typed("i", String.valueOf(data.get(key, PersistentDataType.INTEGER))));
        }
        if (data.has(key, PersistentDataType.LONG)) {
            return Optional.of(new PdcLine.Typed("l", String.valueOf(data.get(key, PersistentDataType.LONG))));
        }
        if (data.has(key, PersistentDataType.DOUBLE)) {
            return Optional.of(new PdcLine.Typed("d", String.valueOf(data.get(key, PersistentDataType.DOUBLE))));
        }
        if (data.has(key, PersistentDataType.FLOAT)) {
            return Optional.of(new PdcLine.Typed("f", String.valueOf(data.get(key, PersistentDataType.FLOAT))));
        }
        if (data.has(key, PersistentDataType.BYTE)) {
            return Optional.of(new PdcLine.Typed("b", String.valueOf(data.get(key, PersistentDataType.BYTE))));
        }
        if (data.has(key, PersistentDataType.SHORT)) {
            return Optional.of(new PdcLine.Typed("h", String.valueOf(data.get(key, PersistentDataType.SHORT))));
        }
        if (data.has(key, PersistentDataType.BYTE_ARRAY)) {
            return Optional.of(new PdcLine.Typed("ba",
                    Base64.getEncoder().encodeToString(data.get(key, PersistentDataType.BYTE_ARRAY))));
        }
        if (data.has(key, PersistentDataType.INTEGER_ARRAY)) {
            return Optional.of(new PdcLine.Typed("ia", PdcLine.join(data.get(key, PersistentDataType.INTEGER_ARRAY))));
        }
        if (data.has(key, PersistentDataType.LONG_ARRAY)) {
            return Optional.of(new PdcLine.Typed("la", PdcLine.join(data.get(key, PersistentDataType.LONG_ARRAY))));
        }
        return Optional.empty();
    }

    private static void write(PersistentDataContainer data, NamespacedKey key, PdcLine.Typed value) {
        try {
            switch (value.type()) {
                case "s" -> data.set(key, PersistentDataType.STRING, value.text());
                case "i" -> data.set(key, PersistentDataType.INTEGER, Integer.parseInt(value.text()));
                case "l" -> data.set(key, PersistentDataType.LONG, Long.parseLong(value.text()));
                case "d" -> data.set(key, PersistentDataType.DOUBLE, Double.parseDouble(value.text()));
                case "f" -> data.set(key, PersistentDataType.FLOAT, Float.parseFloat(value.text()));
                case "b" -> data.set(key, PersistentDataType.BYTE, Byte.parseByte(value.text()));
                case "h" -> data.set(key, PersistentDataType.SHORT, Short.parseShort(value.text()));
                case "ba" -> data.set(key, PersistentDataType.BYTE_ARRAY, Base64.getDecoder().decode(value.text()));
                case "ia" -> data.set(key, PersistentDataType.INTEGER_ARRAY, PdcLine.ints(value.text()));
                case "la" -> data.set(key, PersistentDataType.LONG_ARRAY, PdcLine.longs(value.text()));
                default -> {
                    // A type a later version wrote; left out rather than guessed.
                }
            }
        } catch (IllegalArgumentException unreadable) {
            // One unreadable value is one setting fewer, never a broken side.
        }
    }
}
