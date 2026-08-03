package de.raindancer.core.land;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

/** Single-file storage for the no-claim zones; there are never many of them. */
public final class ZoneStorage {

    private static final LogChannel log = Log.of("land");

    private final Path file;

    public ZoneStorage(Path dataFolder) {
        this.file = dataFolder.resolve("no-claim-zones.yml");
    }

    public List<NoClaimZone> loadAll() {
        List<NoClaimZone> zones = new ArrayList<>();
        if (!Files.exists(file)) {
            return zones;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        ConfigurationSection root = yaml.getConfigurationSection("zones");
        if (root == null) {
            return zones;
        }
        for (String name : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(name);
            if (section == null) {
                continue;
            }
            try {
                List<ClaimPoint> vertices = new ArrayList<>();
                for (String raw : section.getStringList("vertices")) {
                    vertices.add(ClaimPoint.deserialize(raw));
                }
                if (vertices.size() < 3) {
                    log.warn("No-claim zone '" + name + "' has a degenerate shape — skipping.");
                    continue;
                }
                ClaimShape shape = new ClaimShape(vertices, section.getInt("min-y"), section.getInt("max-y"));
                zones.add(new NoClaimZone(name,
                        UUID.fromString(section.getString("world-id", UUID.randomUUID().toString())),
                        section.getString("world-name", "unknown"),
                        shape,
                        section.getLong("created-at", System.currentTimeMillis())));
            } catch (RuntimeException exception) {
                log.error(exception, "Could not load no-claim zone '" + name + "'");
            }
        }
        return zones;
    }

    public void saveAll(Collection<NoClaimZone> zones) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        for (NoClaimZone zone : zones) {
            String base = "zones." + zone.name();
            List<String> vertices = new ArrayList<>();
            for (ClaimPoint point : zone.shape().vertices()) {
                vertices.add(point.serialize());
            }
            yaml.set(base + ".world-id", zone.worldId().toString());
            yaml.set(base + ".world-name", zone.worldName());
            yaml.set(base + ".vertices", vertices);
            yaml.set(base + ".min-y", zone.shape().minY());
            yaml.set(base + ".max-y", zone.shape().maxY());
            yaml.set(base + ".created-at", zone.createdAt());
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml.saveToString());
    }
}
