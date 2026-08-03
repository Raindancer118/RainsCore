package de.raindancer.core.data.nbt;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * An item as one line of text, for the files a human can open.
 *
 * <p>Base64 over {@link ItemBytes} and nothing else — there is one item serialiser on this server and this
 * is only an encoding on top of it. Writing a second one would be the usual way an item written by one
 * subsystem stops being readable by another.
 *
 * <h2>Why not the map form</h2>
 * Bukkit's {@code ConfigurationSerializable} map is the obvious thing to put in YAML and it is the wrong
 * thing. 1.20.5 moved every item's contents from {@code tag} to {@code components}, and the map form does
 * not reliably carry items with components across that. {@link ItemBytes} speaks the game's own NBT,
 * carries a {@code DataVersion}, and the server upgrades an older item on the way in without being asked.
 *
 * <h2>Nulls rather than exceptions</h2>
 * Both directions answer {@code null} for anything that is not an item. The callers are loaders reading a
 * file somebody may have edited, and one unreadable stack in a chest is a slot that shows empty — not a
 * claim that fails to load and land that is left unprotected.
 */
public final class ItemText {

    private static final ItemText OF_THE_SERVER = new ItemText(new ItemBytes.OfTheServer());

    private final ItemBytes bytes;

    public ItemText(ItemBytes bytes) {
        this.bytes = Objects.requireNonNull(bytes, "an item codec needs something to serialise with");
    }

    /** The real one, for the loaders. Held once; it is stateless. */
    public static ItemText ofTheServer() {
        return OF_THE_SERVER;
    }

    /** @return the encoded item, or null for nothing and for air */
    public String write(ItemStack item) {
        if (bytes.isNothing(item)) {
            return null;
        }
        byte[] raw = bytes.toBytes(item);
        return raw == null ? null : Base64.getEncoder().encodeToString(raw);
    }

    /** @return the item, or null when the text was not one this server can make */
    public ItemStack read(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(encoded.strip());
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
        try {
            return bytes.fromBytes(raw).orElse(null);
        } catch (RuntimeException refused) {
            return null;
        }
    }

    /** Skips whatever could not be written, so a list of ten items never becomes a list of ten nulls. */
    public List<String> writeAll(List<ItemStack> items) {
        List<String> encoded = new ArrayList<>();
        if (items == null) {
            return encoded;
        }
        for (ItemStack item : items) {
            String line = write(item);
            if (line != null) {
                encoded.add(line);
            }
        }
        return encoded;
    }

    /** The counterpart, skipping anything unreadable rather than leaving a hole in the list. */
    public List<ItemStack> readAll(List<String> encoded) {
        List<ItemStack> items = new ArrayList<>();
        if (encoded == null) {
            return items;
        }
        for (String line : encoded) {
            ItemStack item = read(line);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    // ------------------------------------------------------------------ the static form, for loaders

    /**
     * The same, using the server's serialiser.
     *
     * <p>Here because the callers are static loader methods reading a file, and threading a codec through
     * every one of them would be ceremony for a class with no state. The instance form above is what a
     * test uses, and both go through the same one implementation.
     */
    public static String encode(ItemStack item) {
        return ofTheServer().write(item);
    }

    public static ItemStack decode(String encoded) {
        return ofTheServer().read(encoded);
    }

    public static List<String> encodeAll(List<ItemStack> items) {
        return ofTheServer().writeAll(items);
    }

    public static List<ItemStack> decodeAll(List<String> encoded) {
        return ofTheServer().readAll(encoded);
    }
}
