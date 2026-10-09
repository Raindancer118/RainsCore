package de.raindancer.core.moderation.invsee;

import de.raindancer.core.data.nbt.ItemBytes;
import de.raindancer.core.data.nbt.Nbt;
import de.raindancer.core.data.nbt.Tag;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A saved player file as a live server leaves it: older, broken, missing — never made worse. */
class PlayerDataInventorySourceTest {

    @TempDir
    Path folder;

    private final UUID who = UUID.randomUUID();

    private static final class Server implements ItemBytes {
        private final int version;

        Server(int version) {
            this.version = version;
        }

        @Override
        public int dataVersion() {
            return version;
        }

        @Override
        public byte[] toBytes(ItemStack item) {
            return new byte[0];
        }

        @Override
        public boolean isNothing(ItemStack item) {
            return item == null;
        }

        @Override
        public Optional<ItemStack> fromBytes(byte[] bytes) {
            return Optional.empty();
        }
    }

    private Path saveFile(int dataVersion) throws IOException {
        Map<String, Tag> values = new LinkedHashMap<>();
        values.put("DataVersion", new Tag.Int(dataVersion));
        values.put("Health", new Tag.Float(17f));
        values.put("Inventory", Tag.List_.of(List.of()));
        values.put("EnderItems", Tag.List_.of(List.of()));
        Path file = folder.resolve(who + ".dat");
        Nbt.write(file, new Tag.Compound(values));
        return file;
    }

    @Test
    @DisplayName("a file from another game version is not written, and is left byte for byte as it was")
    void anotherVersionIsRefused() throws IOException {
        Path file = saveFile(4189);
        byte[] before = Files.readAllBytes(file);

        boolean written = new PlayerDataInventorySource(folder, new Server(4440)).write(who, Carried.empty());

        assertThat(written).as("they must join once so the server upgrades it first").isFalse();
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    @Test
    @DisplayName("the first write keeps a copy of the file as it was, and a later one does not replace it")
    void oneBackupBeforeTheFirstChange() throws IOException {
        Path file = saveFile(4440);
        byte[] original = Files.readAllBytes(file);
        PlayerDataInventorySource source = new PlayerDataInventorySource(folder, new Server(4440));

        assertThat(source.write(who, Carried.empty())).isTrue();
        assertThat(source.write(who, Carried.empty())).isTrue();

        Path backup = file.resolveSibling(file.getFileName() + PlayerDataInventorySource.BACKUP_SUFFIX);
        assertThat(Files.readAllBytes(backup)).isEqualTo(original);
        assertThat(Nbt.read(file).get("Health")).as("the rest of the player is carried through")
                .isPresent();
    }

    @Test
    @DisplayName("a corrupt file reads as nothing and is never written over")
    void aCorruptFileIsLeftAlone() throws IOException {
        Path file = folder.resolve(who + ".dat");
        byte[] rubbish = "not gzip, not nbt".getBytes();
        Files.write(file, rubbish);
        PlayerDataInventorySource source = new PlayerDataInventorySource(folder, new Server(4440));

        assertThat(source.read(who)).isEmpty();
        assertThat(source.write(who, Carried.empty())).isFalse();
        assertThat(Files.readAllBytes(file)).isEqualTo(rubbish);
    }

    @Test
    @DisplayName("somebody the server never saved is nobody, not an error")
    void neverSaved() {
        PlayerDataInventorySource source = new PlayerDataInventorySource(folder, new Server(4440));

        assertThat(source.has(who)).isFalse();
        assertThat(source.read(who)).isEmpty();
        assertThat(source.write(who, Carried.empty())).isFalse();
    }

    @Test
    @DisplayName("the save file is looked for in every folder it can be in, each time — not decided once at startup")
    void foldersAreCheckedEveryTime() throws IOException {
        Path modern = folder.resolve("players").resolve("data");
        Path legacy = folder.resolve("playerdata");
        // Neither exists yet, as on a fresh world when the server starts.
        PlayerDataInventorySource source = new PlayerDataInventorySource(List.of(modern, legacy), new Server(4440));
        assertThat(source.has(who)).isFalse();

        java.nio.file.Files.createDirectories(modern);
        Path written = saveFile(4440);
        java.nio.file.Files.move(written, modern.resolve(who + ".dat"));

        assertThat(source.has(who)).isTrue();
        assertThat(source.fileFor(who)).isEqualTo(modern.resolve(who + ".dat"));
        assertThat(source.read(who)).isPresent();
    }

    @Test
    @DisplayName("an old world's file is found in the old folder")
    void legacyFolder() throws IOException {
        Path legacy = folder.resolve("playerdata");
        java.nio.file.Files.createDirectories(legacy);
        java.nio.file.Files.move(saveFile(4440), legacy.resolve(who + ".dat"));
        PlayerDataInventorySource source = new PlayerDataInventorySource(
                List.of(folder.resolve("players").resolve("data"), legacy), new Server(4440));
        assertThat(source.has(who)).isTrue();
        assertThat(source.fileFor(who)).isEqualTo(legacy.resolve(who + ".dat"));
    }
}
