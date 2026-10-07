package de.raindancer.core.ui.effect;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

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
    /** Whose schedulers a sound or a burst is run through; null plays on the caller's thread. */
    private final Plugin plugin;

    /** Plays wherever it is called — right only on the thread that owns the player or the place. */
    public BukkitEffectSink() {
        this(null);
    }

    /**
     * Plays on the thread that owns the player or the place, wherever the call came from — a menu
     * click on another region, or a delayed layer of a sound arriving on an async thread.
     */
    public BukkitEffectSink(Plugin plugin) {
        this.plugin = plugin;
    }

    private void onPlayer(Player online, Runnable what) {
        if (plugin == null) {
            what.run();
        } else {
            Scheduling.onOwner(plugin, online, what);
        }
    }

    private void atRegion(Location where, Runnable what) {
        if (plugin == null) {
            what.run();
        } else {
            Scheduling.region(plugin, where, what);
        }
    }

    @Override
    public void toPlayer(UUID player, SoundCue sound) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            // By key rather than by Sound: a resource pack's own sound is then no different from a
            // vanilla one, which is the whole point of contributing packs in the first place.
            onPlayer(online, () ->
                    online.playSound(online.getLocation(), sound.key(), sound.volume(), sound.pitch()));
        }
    }

    @Override
    public void toPlayer(UUID player, ParticleCue particles) {
        Player online = Bukkit.getPlayer(player);
        Particle particle = particleOf(particles.particle());
        if (online != null && particle != null) {
            Object data = dataFor(particle, particles);
            if (data == MISSING) {
                return;
            }
            onPlayer(online, () -> online.spawnParticle(particle, online.getLocation().add(0, 1, 0),
                    particles.count(), particles.spreadX(), particles.spreadY(), particles.spreadZ(),
                    particles.speed(), data));
        }
    }

    @Override
    public void atPlace(String world, double x, double y, double z, SoundCue sound) {
        World found = Bukkit.getWorld(world);
        if (found != null) {
            Location where = new Location(found, x, y, z);
            atRegion(where, () -> found.playSound(where, sound.key(), sound.volume(), sound.pitch()));
        }
    }

    @Override
    public void atPlace(String world, double x, double y, double z, ParticleCue particles) {
        World found = Bukkit.getWorld(world);
        Particle particle = particleOf(particles.particle());
        if (found != null && particle != null) {
            Object data = dataFor(particle, particles);
            if (data == MISSING) {
                return;
            }
            Location where = new Location(found, x, y, z);
            atRegion(where, () -> found.spawnParticle(particle, where, particles.count(),
                    particles.spreadX(), particles.spreadY(), particles.spreadZ(), particles.speed(),
                    data));
        }
    }

    @Override
    public void stopForPlayer(UUID player, String soundKey) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            onPlayer(online, () -> online.stopSound(soundKey));
        }
    }

    @Override
    public void stopAllForPlayer(UUID player) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            onPlayer(online, online::stopAllSounds);
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
        return Sound.class.isEnum() && Registry.SOUNDS.get(
                NamespacedKey.minecraft(key.replace("minecraft:", ""))) != null;
    }
    /** Particles already complained about, so a cue on a timer is one log line rather than a flood. */
    private static final Set<String> WITHOUT_DATA =
            ConcurrentHashMap.newKeySet();

    /**
     * Whether this particle cannot be spawned without extra data.
     *
     * <p>Some of them — dust, block and item particles, falling dust, sculk charge — require a second
     * argument saying which colour or which block. Bukkit does not treat the data-less call as "no
     * data, then": it throws {@code IllegalArgumentException}. So an admin who names one of those in a
     * cue would get an exception every time the cue played, from inside a scheduled task, and no
     * particle either.
     *
     * <p>Skipped with one line in the log instead. The alternative is inventing a colour on their
     * behalf, which is a cue that works and looks wrong — harder to diagnose than one that says why
     * it did nothing.
     */
    /** A particle that needs data this cue cannot supply. */
    static final Object MISSING = new Object();

    private static Object dataFor(Particle particle, ParticleCue cue) {
        Object data = dataFor(particle.getDataType(), cue.colour());
        if (data == MISSING && WITHOUT_DATA.add(particle.name())) {
            log.warn("The particle {} needs extra data a cue cannot carry here (a colour written "
                    + "#rrggbb works for dust and tinted particles; a block does not). Cues naming it "
                    + "without that show nothing.", particle.name());
        }
        return data;
    }

    /**
     * What to hand the server as a particle's data: nothing for one that takes none, the colour for
     * one that takes a colour or a dust, and {@link #MISSING} when it needs something this cannot give.
     */
    static Object dataFor(Class<?> dataType, Integer colour) {
        return dataFor(dataType, colour, 1.0f);
    }

    /** The same, with dust drawn {@code size} big (1 is vanilla's usual). */
    static Object dataFor(Class<?> dataType, Integer colour, float size) {
        if (dataType == null || dataType == Void.class) {
            return null;
        }
        if (colour == null) {
            return MISSING;
        }
        Color rgb = Color.fromRGB(colour);
        if (dataType == Particle.DustOptions.class) {
            return new Particle.DustOptions(rgb, size);
        }
        if (dataType == Color.class) {
            return rgb;
        }
        return MISSING;
    }

}
