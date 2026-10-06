package de.raindancer.core.ui.effect;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import de.raindancer.core.platform.util.Scheduling;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * A particle worn by a player: drawn in a {@link ParticleShape} around them, every time it is asked,
 * to whoever nearby wants to see it.
 *
 * <p>Not {@link Effects}: that plays named cues and spaces repeats of a cue apart, which is right for a
 * sound on a click and wrong for something drawn several times a second for as long as it is worn.
 */
public final class ParticleShows {

    private ParticleShows() {
    }

    /** Whether this particle can be drawn with nothing but, at most, a colour. */
    public static boolean canShow(String particle) {
        Particle found = particleOf(particle);
        if (found == null) {
            return false;
        }
        Class<?> data = found.getDataType();
        return data == Void.class || data == Particle.DustOptions.class || data == Color.class;
    }

    /** Whether it needs a colour to show — dust and the tinted ones. */
    public static boolean takesColour(String particle) {
        Particle found = particleOf(particle);
        return found != null && found.getDataType() != Void.class && canShow(particle);
    }

    /**
     * Draws it once. Call on the wearer's own thread ({@code Scheduling.entity}) — on Folia the
     * nearby players are only safe to look at from there.
     *
     * @param colour   for a particle that {@link #takesColour}; ignored otherwise, white if missing
     * @param range    how far away somebody may be and still see it
     * @param sees     who wants to — a player who has switched other people's particles off is left out
     */
    public static void around(Player wearer, String particle, Integer colour, int count, ParticleShape shape,
                              long tick, double range, Predicate<Player> sees) {
        Particle found = particleOf(particle);
        if (found == null || !canShow(particle)) {
            return;
        }
        Object data = BukkitEffectSink.dataFor(found.getDataType(), colour == null ? 0xFFFFFF : colour);
        Location feet = wearer.getLocation();
        List<Player> viewers = feet.getNearbyPlayers(range).stream().filter(sees).toList();
        if (viewers.isEmpty()) {
            return;
        }
        World world = wearer.getWorld();
        for (double[] offset : shape.offsets(tick, feet.getYaw())) {
            world.spawnParticle(found, viewers, wearer, feet.getX() + offset[0], feet.getY() + offset[1],
                    feet.getZ() + offset[2], count, 0.05, 0.05, 0.05, 0, data);
        }
    }

    /**
     * Shows {@code viewer} the particle in its shape a little in front of them for a few seconds, only to
     * them — drawn through an open menu, which is where somebody is choosing.
     */
    public static void preview(Plugin plugin, Player viewer, String particle, Integer colour, int count,
                               ParticleShape shape, int seconds) {
        Particle found = particleOf(particle);
        if (found == null || !canShow(particle)) {
            return;
        }
        Object data = BukkitEffectSink.dataFor(found.getDataType(), colour == null ? 0xFFFFFF : colour);
        long rounds = Math.max(1, seconds) * 10L;
        long[] drawn = {0};
        Scheduling.entityTimer(plugin, viewer, 1, 2, task -> {
            if (!viewer.isOnline() || drawn[0]++ >= rounds) {
                task.cancel();
                return;
            }
            Location eye = viewer.getEyeLocation();
            Location feet = eye.clone().add(eye.getDirection().setY(0).normalize().multiply(3)).add(0, -1.6, 0);
            for (double[] offset : shape.offsets(drawn[0], eye.getYaw() + 180)) {
                viewer.spawnParticle(found, feet.getX() + offset[0], feet.getY() + offset[1],
                        feet.getZ() + offset[2], count, 0.05, 0.05, 0.05, 0, data);
            }
        });
    }

    /** The data a preview hands the server: white for a particle that takes a colour, else nothing. */
    public static Object previewData(Particle particle) {
        return BukkitEffectSink.dataFor(particle.getDataType(), 0xFFFFFF);
    }

    static Particle particleOf(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
