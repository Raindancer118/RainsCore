package de.raindancer.core.data.nbt;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * Turning an item into the bytes a save file holds, and back.
 *
 * <h2>Why this is the server's job and not ours</h2>
 * The shape of an item genuinely does change between Minecraft versions — 1.20.5 moved every item's
 * contents from {@code tag} to {@code components}, and a plugin that had learnt to read the old one
 * read nothing at all afterwards. The server ships a converter for exactly this, and
 * {@code ItemStack.serializeAsBytes} and {@code ItemStack.deserializeBytes} are the public way in:
 * they speak the game's own NBT, they carry a {@code DataVersion}, and they bring an item written by
 * an older version forward without being asked.
 *
 * <p>So nothing in this package ever looks inside an item. It moves compounds about and hands them
 * here. That is what makes the offline feature survive an update rather than being a yearly rewrite,
 * and it is why none of this needs the server's internals.
 *
 * <p>An interface rather than three static calls because these are the only lines in the offline
 * path that need a running server. With them behind a seam, everything else is tested without one.
 */
public interface ItemBytes {

    /**
     * Which version of the game this server writes.
     *
     * <p>Compared against what a player file says before anything is written into it — see
     * {@link PlayerDataInventorySource}.
     */
    int dataVersion();

    /** One item as the bytes a save file would hold. */
    byte[] toBytes(ItemStack item);

    /**
     * Whether this is nothing worth writing down.
     *
     * <p>Here rather than at the call sites because answering it touches the server. {@code Material.isAir}
     * resolves through Paper's registry, so a caller that asks it directly cannot be tested without a
     * running server — and the callers are loaders and stores, which is exactly the code that has to be.
     *
     * <p>It matters that air is caught: {@code serializeAsBytes} happily encodes an air stack, so without
     * this a list of nine items and forty-five empty slots is stored as fifty-four entries.
     */
    default boolean isNothing(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    /** One item back, or empty when the bytes were not an item this server can make. */
    Optional<ItemStack> fromBytes(byte[] bytes);

    /** The real one. */
    final class OfTheServer implements ItemBytes {

        private static final LogChannel log = Log.of("nbt");

        @Override
        @SuppressWarnings("deprecation")
        public int dataVersion() {
            // Marked internal by Bukkit, and still the only way to ask. The alternative is guessing,
            // and the thing being guessed at decides whether somebody's inventory is safe to write.
            return Bukkit.getUnsafe().getDataVersion();
        }

        @Override
        public byte[] toBytes(ItemStack item) {
            return item.serializeAsBytes();
        }

        @Override
        public Optional<ItemStack> fromBytes(byte[] bytes) {
            try {
                return Optional.ofNullable(ItemStack.deserializeBytes(bytes));
            } catch (RuntimeException refused) {
                // One item the server will not make — a block from a mod that is no longer
                // installed, most likely. The rest of the inventory is still worth showing, and a
                // moderator seeing one empty slot is better than seeing an error instead of a
                // window.
                log.warn(refused, "An item in a saved inventory could not be read back and will "
                        + "show as empty.");
                return Optional.empty();
            }
        }
    }
}
