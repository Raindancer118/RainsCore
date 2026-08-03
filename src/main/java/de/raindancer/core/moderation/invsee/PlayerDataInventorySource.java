package de.raindancer.core.moderation.invsee;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.data.nbt.Nbt;
import de.raindancer.core.data.nbt.Tag;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

/**
 * What somebody who is logged out is carrying, read out of the file the server saved them in.
 *
 * <h2>Why this exists</h2>
 * Because {@code OfflinePlayer} has no {@code getInventory()}, and the players a moderator most
 * needs to look at are precisely the ones who logged out — somebody reported after they left, a
 * duplication being traced through the people who received the items, a returning player whose
 * belongings need giving back. "Only while they are online" is not a small gap in an invsee; it is
 * the half that matters.
 *
 * <h2>Why this is safe to write, in three parts</h2>
 * <ol>
 *   <li><b>The file is not written while its owner might be joining.</b> That is
 *       {@link OfflineEdits}, and it is the whole reason this feature is not a trap: the server
 *       rewrites the file on join and on quit, and an edit made across either is discarded without
 *       a word.</li>
 *   <li><b>A file from a different version is not written at all.</b> A player who has not logged in
 *       since a Minecraft update has a file the server would upgrade on their next join, and putting
 *       items from this version into it mixes two formats in one file. Refused, with a reason a
 *       moderator can act on — "they must log in once first" — rather than written hopefully.</li>
 *   <li><b>The old file is kept.</b> One copy, before the first change, next to it. A move that goes
 *       wrong is an inventory; this is the cheapest insurance there is.</li>
 * </ol>
 *
 * <p>Every actual byte of item goes through the server's own reader — see {@link ItemBytes}. Nothing
 * here knows what an item is.
 */
public final class PlayerDataInventorySource implements InventorySource {

    private static final LogChannel log = Log.of("invsee");

    /** Kept beside the file before the first edit. Not {@code .dat}, so the server ignores it. */
    public static final String BACKUP_SUFFIX = ".rains-backup.nbt";

    private final Path playerData;
    private final ItemBytes items;

    /**
     * @param playerData the {@code playerdata} folder of the main world
     * @param items      how an item becomes bytes and back — the server's own reader
     */
    public PlayerDataInventorySource(Path playerData, ItemBytes items) {
        this.playerData = playerData;
        this.items = items;
    }

    /** Where somebody's file is, whether or not it exists. */
    public Path fileFor(UUID who) {
        return playerData.resolve(who + ".dat");
    }

    /** Whether this server has ever saved them. */
    public boolean has(UUID who) {
        return who != null && Files.isRegularFile(fileFor(who));
    }

    @Override
    public Optional<Carried<ItemStack>> read(UUID who) {
        if (!has(who)) {
            return Optional.empty();
        }
        try {
            Carried<byte[]> saved = PlayerDataCodec.read(Nbt.read(fileFor(who)));
            return Optional.of(saved.map(bytes -> items.fromBytes(bytes).orElse(null)));
        } catch (IOException | RuntimeException unreadable) {
            log.error(unreadable, "The saved inventory of {} could not be read.", who);
            return Optional.empty();
        }
    }

    /**
     * One slot, written straight to disk.
     *
     * <p>Correct, and the wrong thing to call for every click: it reads and rewrites the whole file
     * each time. The window edits a snapshot and calls {@link #write} once when it closes.
     */
    @Override
    public boolean set(UUID who, Section section, int indexWithin, ItemStack item) {
        Optional<Carried<ItemStack>> carried = read(who);
        return carried.isPresent()
                && write(who, carried.get().with(section, indexWithin, item));
    }

    @Override
    public boolean write(UUID who, Carried<ItemStack> carried) {
        if (!has(who) || carried == null) {
            return false;
        }
        Path file = fileFor(who);
        try {
            Tag.Compound root = Nbt.read(file);
            int fileVersion = PlayerDataCodec.dataVersionOf(root);
            if (fileVersion != items.dataVersion()) {
                log.warn("The saved inventory of {} was written by data version {} and this server "
                        + "is {}. Refusing to write: they have not logged in since the game was "
                        + "updated, and mixing two versions of the item format in one file is how "
                        + "an inventory is lost. They need to join once first.",
                        who, fileVersion, items.dataVersion());
                return false;
            }
            keepACopy(file);
            Nbt.write(file, PlayerDataCodec.write(root, carried.map(items::toBytes)));
            log.info("Wrote the saved inventory of {} ({} items).", who, carried.count());
            return true;
        } catch (IOException | RuntimeException failed) {
            log.error(failed, "The saved inventory of {} could not be written.", who);
            return false;
        }
    }

    /**
     * One copy of the file as it was, before this ever changes it.
     *
     * <p>Only the first time: the point is the state before a moderator started, not the state
     * before the most recent of several edits — by then the mistake being undone has usually been
     * copied into the backup too.
     */
    private void keepACopy(Path file) throws IOException {
        Path backup = file.resolveSibling(file.getFileName() + BACKUP_SUFFIX);
        if (!Files.exists(backup)) {
            Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
            log.debug("Kept a copy of {} before editing it.", file.getFileName());
        }
    }

    @Override
    public String describe() {
        return "their save file";
    }
}
