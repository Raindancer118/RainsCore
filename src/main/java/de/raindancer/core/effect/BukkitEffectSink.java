package de.raindancer.core.effect;

import de.raindancer.core.log.Log;
import de.raindancer.core.log.LogChannel;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The lines that actually make a noise.
 *
 * <p>Everything worth getting right — what a cue means, whether it has just been played, whether it
 * is switched off — is on the other side of {@link EffectSink} and is tested without a server.
 *
 * <p>The one judgement here is what to do with a name the server does not know. A sound key is
 * passed through as a key, so a resource pack's own sound works exactly like a vanilla one and a
 * misspelled one is simply silent — that is the game's behaviour and it is the right one. A particle
 * has to be a real enum constant, so an unknown one is dropped and said once: a name that a future
 * version renames should be a line in the log, not a crash in whatever was happening.
 */
public final class BukkitEffectSink implements EffectSink {

    private static final LogChannel log = Log.of("effects");

    /** Particle names that turned out not to exist. Complained about once each. */
    private final Set<String> unknown = ConcurrentHashMap.newKeySet();

    @Override
    public void toPlayer(UUID player, SoundCue sound) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            // By key rather than by Sound: a resource pack's own sound is then no different from a
            // vanilla one, which is the whole point of contributing packs in the first place.
            online.playSound(online.getLocation(), sound.key(), sound.volume(), sound.pitch());
        }
    }

    @Override
    public void toPlayer(UUID player, ParticleCue particles) {
        Player online = Bukkit.getPlayer(player);
        Particle particle = particleOf(particles.particle());
        if (online != null && particle != null) {
            online.spawnParticle(particle, online.getLocation().add(0, 1, 0), particles.count(),
                    particles.spreadX(), particles.spreadY(), particles.spreadZ(),
                    particles.speed());
        }
    }

    @Override
    public void atPlace(String world, double x, double y, double z, SoundCue sound) {
        World found = Bukkit.getWorld(world);
        if (found != null) {
            found.playSound(new Location(found, x, y, z), sound.key(), sound.volume(),
                    sound.pitch());
        }
    }

    @Override
    public void atPlace(String world, double x, double y, double z, ParticleCue particles) {
        World found = Bukkit.getWorld(world);
        Particle particle = particleOf(particles.particle());
        if (found != null && particle != null) {
            found.spawnParticle(particle, new Location(found, x, y, z), particles.count(),
                    particles.spreadX(), particles.spreadY(), particles.spreadZ(),
                    particles.speed());
        }
    }

    @Override
    public void stopForPlayer(UUID player, String soundKey) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            online.stopSound(soundKey);
        }
    }

    @Override
    public void stopAllForPlayer(UUID player) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            online.stopAllSounds();
        }
    }

    /** A particle by name, or null once, loudly. */
    private Particle particleOf(String name) {
        try {
            return Particle.valueOf(name);
        } catch (IllegalArgumentException notAParticle) {
            if (unknown.add(name)) {
                log.warn("This server has no particle called '{}'; that part of the effect was "
                        + "skipped.", name);
            }
            return null;
        }
    }

    /** Whether a sound name is one the server itself knows — for a chooser, not for playing. */
    public static boolean isVanillaSound(String key) {
        return Sound.class.isEnum() && org.bukkit.Registry.SOUNDS.get(
                org.bukkit.NamespacedKey.minecraft(key.replace("minecraft:", ""))) != null;
    }
}
