package de.raindancer.core.data.loadout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LoadoutStoreTest {

    private final UUID owner = UUID.randomUUID();

    private static Loadout sample() {
        List<String> inventory = new ArrayList<>(java.util.Collections.nCopies(Loadout.INVENTORY_SLOTS, ""));
        inventory.set(4, "c3dvcmQ=");
        inventory.set(40, "c2hpZWxk");
        List<String> ender = new ArrayList<>(java.util.Collections.nCopies(Loadout.ENDER_SLOTS, ""));
        ender.set(26, "ZGlhbW9uZHM=");
        return new Loadout(inventory, ender, 31, 0.25f, 14.5, 17, 3.5f, "SURVIVAL", true, false,
                List.of(new Loadout.Effect("minecraft:speed", 600, 1, false, true, true)),
                new Loadout.Place("world", 10.5, 64, -3.25, 90f, -10f));
    }

    @Test
    @DisplayName("a loadout comes back exactly as it went in, empty slots in their places")
    void roundTrip(@TempDir Path folder) {
        LoadoutStore store = new LoadoutStore(folder);
        assertThat(store.save(owner, "survival", sample())).isTrue();

        Loadout back = new LoadoutStore(folder).load(owner, "survival").orElseThrow();

        assertThat(back).isEqualTo(sample());
        assertThat(back.inventory()).hasSize(Loadout.INVENTORY_SLOTS);
        assertThat(back.inventory().get(4)).isEqualTo("c3dvcmQ=");
        assertThat(back.inventory().get(0)).isEmpty();
    }

    @Test
    @DisplayName("two profiles of one player are kept apart, and deleting one leaves the other")
    void profilesAreSeparate(@TempDir Path folder) {
        LoadoutStore store = new LoadoutStore(folder);
        store.save(owner, "survival", sample());
        store.save(owner, "admin", Loadout.empty("CREATIVE"));

        assertThat(store.has(owner, "survival")).isTrue();
        assertThat(store.load(owner, "admin").orElseThrow().gameMode()).isEqualTo("CREATIVE");

        assertThat(store.delete(owner, "survival")).isTrue();
        assertThat(store.has(owner, "survival")).isFalse();
        assertThat(store.load(owner, "admin")).isPresent();
        assertThat(store.delete(owner, "survival")).isFalse();
    }

    @Test
    @DisplayName("nobody's loadout yet is nothing, not an error")
    void missing(@TempDir Path folder) {
        assertThat(new LoadoutStore(folder).load(owner, "admin")).isEmpty();
        assertThat(new LoadoutStore(folder).has(owner, "admin")).isFalse();
        assertThat(new LoadoutStore(folder).readable(owner)).as("no file is a readable nothing").isTrue();
    }

    @Test
    @DisplayName("a file that cannot be read is not overwritten by the next save")
    void brokenFileIsLeftAlone(@TempDir Path folder) throws Exception {
        Path file = folder.resolve(owner + ".yml");
        Files.writeString(file, "survival: [unclosed\n  : :");

        LoadoutStore store = new LoadoutStore(folder);
        assertThat(store.load(owner, "survival")).isEmpty();
        assertThat(store.readable(owner)).as("so a caller can tell 'nothing there' from 'cannot tell'").isFalse();
        assertThat(store.save(owner, "admin", sample())).isFalse();
        assertThat(Files.readString(file)).isEqualTo("survival: [unclosed\n  : :");
    }

    @Test
    @DisplayName("a profile name cannot climb out of its section")
    void profileNamesAreTamed(@TempDir Path folder) {
        LoadoutStore store = new LoadoutStore(folder);
        store.save(owner, "a.b", sample());
        assertThat(store.load(owner, "a.b")).contains(sample());
        assertThat(store.has(owner, "a")).isFalse();
    }

    @Test
    @DisplayName("side data comes back as saved; an empty side stays empty; one saved before side data has none at all")
    void sideData(@TempDir Path folder) {
        LoadoutStore store = new LoadoutStore(folder);
        Loadout worn = sample();
        Loadout withData = new Loadout(worn.inventory(), worn.enderChest(), worn.level(), worn.exp(), worn.health(),
                worn.food(), worn.saturation(), worn.gameMode(), worn.allowFlight(), worn.flying(), worn.effects(),
                worn.place(), java.util.Map.of("pdc:rainscosmetics", "particle s ZmxhbWU=", "cosmetics:name-style", "#ffaa00"));
        store.save(owner, "survival", withData);
        store.save(owner, "admin", Loadout.empty("CREATIVE"));
        store.save(owner, "old", sample());
        LoadoutStore back = new LoadoutStore(folder);
        assertThat(back.load(owner, "survival").orElseThrow().data()).isEqualTo(withData.data());
        assertThat(back.load(owner, "admin").orElseThrow().data()).as("an empty side").isEmpty();
        assertThat(back.load(owner, "old").orElseThrow().data()).as("saved before side data").isNull();
    }
}
