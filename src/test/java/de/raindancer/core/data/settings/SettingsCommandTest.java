package de.raindancer.core.data.settings;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SettingsCommandTest {

    @Test
    @DisplayName("completing a key offers those starting with it first, then those containing it")
    void completion() {
        List<String> keys = List.of("fence-style", "fence-block", "wall-style", "spawn-radius");

        assertThat(SettingsCommand.keysMatching(keys, "fe")).containsExactly("fence-style", "fence-block");
        assertThat(SettingsCommand.keysMatching(keys, "style")).containsExactly("fence-style", "wall-style");
        assertThat(SettingsCommand.keysMatching(keys, "")).hasSize(4);
    }

    @Settings(id = "walls", topics = @Topic(path = "walls", title = "Walls"))
    record Walls(@In("walls") @Title("Wall height") @Range(min = 1, max = 10) int wallHeight) {
    }

    @TempDir
    Path directory;

    @Test
    @DisplayName("a change that could not be written is reported to whoever made it, naming the file")
    void unsavedIsSaid() throws Exception {
        Path file = directory.resolve("walls.yml");
        SettingsStore<Walls> store = new SettingsStore<>(SettingsSchema.of(Walls.class, new Walls(3)), file);
        store.load();
        SettingsRegistry registry = new SettingsRegistry();
        registry.add(store);
        CommandSender admin = mock(CommandSender.class);

        SettingsSaving.saveThenTell(registry, admin);
        verify(admin, never()).sendMessage(org.mockito.ArgumentMatchers.any(Component.class));

        Files.writeString(file, "wall-height: [3");
        store.load();
        registry.set("wall-height", "5");
        SettingsSaving.saveThenTell(registry, admin);

        ArgumentCaptor<Component> said = ArgumentCaptor.forClass(Component.class);
        verify(admin).sendMessage(said.capture());
        assertThat(PlainTextComponentSerializer.plainText().serialize(said.getValue()))
                .contains("walls.yml").contains("restart");
        assertThat(Files.readString(file)).isEqualTo("wall-height: [3");
    }
}
