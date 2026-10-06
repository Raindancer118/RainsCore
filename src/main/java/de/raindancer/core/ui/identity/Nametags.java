package de.raindancer.core.ui.identity;

import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The name above a player's head, drawn from {@link Identities#nametag} — so a gradient, decorations and
 * a nickname show there too, which the vanilla nametag cannot do.
 *
 * <p>A text display rides each player and the vanilla name is hidden through Core's teams
 * ({@link VanillaNametags}). Off by default; whoever offers styled names switches it on. A player in
 * another plugin's team keeps their vanilla name and gets no display — two names would be worse.
 *
 * <p>Displays are not persistent, so a crash leaves nothing behind in the world.
 */
public final class Nametags implements Listener {

    private static final LogChannel log = Log.of("nametags");
    private static final String OWN_TEAM = "rc-nametag";
    private static final String CORE_TEAMS = "rc-";
    /** How often an animated name is redrawn. */
    private static final long EVERY_TICKS = 2;
    /** Every how many of those the slow work runs — teams, spawning, who may see whom. */
    private static final long FULL_PASS_EVERY = 5;
    /** As far as a nametag could be seen from; a viewer further away is not asked about. */
    private static final double SIGHT = 128;

    private final Plugin plugin;
    private final Identities identities;
    private final Vanish vanish;
    private final NametagRule rule = new NametagRule();
    private final Map<UUID, TextDisplay> displays = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private long ticks;
    private ScheduledTask timer;

    public Nametags(Plugin plugin, Identities identities, Vanish vanish) {
        this.plugin = plugin;
        this.identities = identities;
        this.vanish = vanish;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Switches styled nametags on or off for the whole server. */
    public synchronized void enabled(boolean on) {
        if (on == enabled) {
            return;
        }
        enabled = on;
        VanillaNametags.hidden(on);
        if (on) {
            timer = Scheduling.globalTimer(plugin, 1, EVERY_TICKS, task -> tick());
        } else {
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
            Scheduling.global(plugin, this::restoreTeams);
            for (Player player : Bukkit.getOnlinePlayers()) {
                Scheduling.entity(plugin, player, () -> remove(player.getUniqueId()));
            }
        }
    }

    /** At shutdown, on the server thread: every display gone, every vanilla name back. */
    public void shutdown() {
        enabled = false;
        VanillaNametags.hidden(false);
        if (timer != null) {
            timer.cancel();
        }
        displays.values().forEach(display -> {
            try {
                display.remove();
            } catch (RuntimeException gone) {
                log.debug("A nametag could not be removed at shutdown: {}", gone.toString());
            }
        });
        displays.clear();
        restoreTeams();
    }

    // ------------------------------------------------------------------ the timer

    /**
     * On the global thread. Every pass redraws the names that move; every {@link #FULL_PASS_EVERY}th
     * also does the teams, spawning and visibility for everybody.
     */
    private void tick() {
        boolean full = ticks++ % FULL_PASS_EVERY == 0;
        Scoreboard board = full ? Bukkit.getScoreboardManager().getMainScoreboard() : null;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (full) {
                boolean foreign = hideVanillaName(board, player);
                Scheduling.entity(plugin, player, () -> update(player, foreign));
            } else if (displays.containsKey(player.getUniqueId()) && identities.isAnimated(player.getUniqueId())) {
                Scheduling.entity(plugin, player, () -> redraw(player));
            }
        }
    }

    /** Just the text, for a name that moves. Leaves spawning and removing to the full pass. */
    private void redraw(Player player) {
        TextDisplay display = displays.get(player.getUniqueId());
        if (!enabled || display == null || !display.isValid()) {
            return;
        }
        Component text = rule.text(identities.nametag(player.getUniqueId(), player.getName()),
                identities.subtitle(player.getUniqueId()));
        if (!text.equals(display.text())) {
            display.text(text);
        }
    }

    /**
     * The name floats where its wearer is, so it must not show to anybody the server hides the wearer
     * from — another plugin's {@code hidePlayer}, a minigame's spectators. Otherwise it gives away a
     * position the hiding was meant to keep secret.
     */
    private void matchVisibility(Player wearer, TextDisplay display) {
        for (Player viewer : wearer.getLocation().getNearbyPlayers(SIGHT)) {
            if (viewer.equals(wearer)) {
                continue;
            }
            if (viewer.canSee(wearer)) {
                viewer.showEntity(plugin, display);
            } else {
                viewer.hideEntity(plugin, display);
            }
        }
    }

    /** @return whether the player is in somebody else's team, which this leaves alone */
    private boolean hideVanillaName(Scoreboard board, Player player) {
        try {
            Team team = board.getEntryTeam(player.getName());
            if (team == null) {
                team = board.getTeam(OWN_TEAM);
                if (team == null) {
                    team = board.registerNewTeam(OWN_TEAM);
                }
                team.addEntry(player.getName());
            }
            if (!team.getName().startsWith(CORE_TEAMS)) {
                return true;
            }
            VanillaNametags.applyTo(team);
            return false;
        } catch (RuntimeException failure) {
            log.debug("Could not hide {}'s vanilla name: {}", player.getName(), failure.toString());
            return true;
        }
    }

    private void update(Player player, boolean foreignTeam) {
        if (!enabled || !player.isOnline()) {
            return;
        }
        UUID id = player.getUniqueId();
        boolean shows = rule.shows(player.isDead(), player.getGameMode() == GameMode.SPECTATOR,
                player.hasPotionEffect(PotionEffectType.INVISIBILITY), vanish.isVanished(id), foreignTeam);
        TextDisplay display = displays.get(id);
        if (display != null && (!display.isValid() || !player.getPassengers().contains(display))) {
            // Thrown off by a teleport, a world change or a dismount — a fresh one is simpler than
            // chasing the old one across regions.
            remove(id);
            display = null;
        }
        if (!shows) {
            remove(id);
            return;
        }
        if (display == null) {
            display = spawn(player);
        }
        Component text = rule.text(identities.nametag(id, player.getName()), identities.subtitle(id));
        if (!text.equals(display.text())) {
            display.text(text);
        }
        boolean sneaking = player.isSneaking();
        display.setSeeThrough(!sneaking);
        display.setTextOpacity(sneaking ? (byte) 0x60 : (byte) -1);
        matchVisibility(player, display);
    }

    private TextDisplay spawn(Player player) {
        TextDisplay display = player.getWorld().spawn(player.getLocation(), TextDisplay.class, made -> {
            made.setPersistent(false);
            // Shown to nobody until matchVisibility says who may — so somebody the wearer is hidden
            // from never gets even the first frame of it.
            made.setVisibleByDefault(false);
            made.setBillboard(Display.Billboard.CENTER);
            made.setDefaultBackground(true);
            made.setShadowed(false);
            made.setTransformation(new Transformation(new Vector3f(0, 0.3f, 0), new AxisAngle4f(),
                    new Vector3f(1, 1, 1), new AxisAngle4f()));
        });
        player.addPassenger(display);
        // Hidden by default, so the wearer never sees their own (vanilla does not show you yours) and
        // everybody else is let in one by one.
        matchVisibility(player, display);
        displays.put(player.getUniqueId(), display);
        return display;
    }

    private void remove(UUID player) {
        TextDisplay display = displays.remove(player);
        if (display != null && display.isValid()) {
            Scheduling.entity(plugin, display, display::remove);
        }
    }

    private void restoreTeams() {
        try {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            for (Team team : board.getTeams()) {
                if (team.getName().startsWith(CORE_TEAMS)) {
                    VanillaNametags.applyTo(team);
                }
            }
            Team own = board.getTeam(OWN_TEAM);
            if (own != null) {
                own.unregister();
            }
        } catch (RuntimeException failure) {
            log.debug("Could not give the vanilla names back: {}", failure.toString());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        remove(event.getPlayer().getUniqueId());
        try {
            Team own = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(OWN_TEAM);
            if (own != null) {
                own.removeEntry(event.getPlayer().getName());
            }
        } catch (RuntimeException ignored) {
            // On Folia the main scoreboard belongs to the global thread; the next tick tidies it.
        }
    }

    // ------------------------------------------------------------------ preview

    /**
     * Shows {@code viewer} a nametag in front of them for a few seconds — only to them, nobody else sees
     * it. For a menu that lets somebody try a style before walking about in it.
     */
    public void preview(Player viewer, Component text, int seconds) {
        Location eye = viewer.getEyeLocation();
        Location at = eye.add(eye.getDirection().setY(0).normalize().multiply(2.5)).add(0, 0.2, 0);
        TextDisplay shown = viewer.getWorld().spawn(at, TextDisplay.class, made -> {
            made.setPersistent(false);
            made.setVisibleByDefault(false);
            made.setBillboard(Display.Billboard.CENTER);
            made.setDefaultBackground(true);
            made.text(text);
        });
        viewer.showEntity(plugin, shown);
        Scheduling.entityLater(plugin, shown, Math.max(1, seconds) * 20L, shown::remove);
    }
}
