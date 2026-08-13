package de.raindancer.core.world.speedrun;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.messages.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The compass's screen: the current end conditions while the lobby is
 * {@link SpeedrunLobbyState#READY}, and a status page otherwise.
 *
 * <h2>Who may change what, and when</h2>
 * Any participant, but only while {@link SpeedrunLobbyState#READY} — the edit buttons only exist on
 * that branch of {@link #render()}. There is nothing to grey during a run: a page with no editable
 * buttons on it is a stronger guarantee than one whose buttons refuse a click, and it means a player
 * who opens this mid-race sees the race, not a form they cannot submit.
 */
public final class SpeedrunLobbyMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SpeedrunLobby lobby;
    private final Messages messages;

    public SpeedrunLobbyMenu(SpeedrunLobby lobby, Messages messages, Brand brand, Player viewer,
                             Menu parent) {
        super(viewer, brand, parent);
        this.lobby = lobby;
        this.messages = messages;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Speedrun");
    }

    @Override
    public String breadcrumb() {
        return "Speedrun";
    }

    @Override
    protected void render() {
        switch (lobby.state()) {
            case READY -> renderReady();
            case COUNTDOWN -> renderCountdown();
            case RUNNING -> renderInProgress("Running");
            case PAUSED -> renderInProgress("Paused — nobody is here");
            case FINISHED -> renderFinished();
        }
    }

    private void renderCountdown() {
        band(MenuLayout.WHO, 4, Icons.of(Material.CLOCK, "<white>Starting…",
                "<gray>Everybody is frozen until it begins."));
    }

    private void renderReady() {
        SpeedrunSettings config = lobby.config();
        band(MenuLayout.WHO, 3,
                Icons.of(Material.WRITABLE_BOOK, "<white>Goal: " + goalLabel(config), advancementLore(config)),
                click -> new SpeedrunAdvancementChooser(lobby, messages, brand(), viewer, this).open());
        band(MenuLayout.WHO, 5,
                Icons.of(deathIcon(config.deathPolicy()), "<white>Death policy: " + config.deathPolicy(),
                        deathLore(config)),
                click -> {
                    lobby.settings().cycle("death-policy");
                    refresh();
                });
    }

    private void renderInProgress(String label) {
        SpeedrunSession session = lobby.session().orElse(null);
        if (session == null) {
            return;
        }
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + session.participants().size() + " racing.");
        lore.add("<gray>" + formatted(session.elapsed()));
        band(MenuLayout.WHO, 4, Icons.of(Material.CLOCK, "<white>" + label, lore));
    }

    private void renderFinished() {
        SpeedrunSession session = lobby.session().orElse(null);
        if (session == null) {
            return;
        }
        SpeedrunOutcome outcome = session.outcome().orElse(null);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Ended by: " + (outcome == null ? "?" : outcome.reason()));
        lore.add("<gray>Time: " + formatted(session.elapsed()));
        lore.add("");
        lore.add("<dark_gray>Resets once everybody here has left.");
        band(MenuLayout.WHO, 4, Icons.of(Material.NETHER_STAR, "<white>Finished!", lore));
    }

    private static String formatted(java.time.Duration elapsed) {
        long seconds = elapsed.getSeconds();
        return "%d:%02d".formatted(seconds / 60, seconds % 60);
    }

    private static Material deathIcon(SpeedrunDeathPolicy policy) {
        return policy == SpeedrunDeathPolicy.OFF ? Material.TOTEM_OF_UNDYING : Material.SKELETON_SKULL;
    }

    /** What the button's own name says — the whole point being that this is visible without a click. */
    private static String goalLabel(SpeedrunSettings config) {
        return config.hasAdvancementGoal()
                ? SpeedrunAdvancementChooser.friendlyName(config.advancementKey())
                : "<gray>None";
    }

    private static List<String> advancementLore(SpeedrunSettings config) {
        if (!config.hasAdvancementGoal()) {
            return List.of("<gray>None set.", "<gray>Click to pick one.");
        }
        return List.of("<gray>" + config.advancementKey(), "", "<gray>Click to change it.");
    }

    private static List<String> deathLore(SpeedrunDeathPolicy policy) {
        return switch (policy) {
            case OFF -> List.of("<gray>A death does not end the run.", "<gray>Click to cycle.");
            case ANY -> List.of("<gray>The first death ends it for everybody.", "<gray>Click to cycle.");
            case ALL -> List.of("<gray>Ends once every racer has died.", "<gray>Click to cycle.");
        };
    }

    private static List<String> deathLore(SpeedrunSettings config) {
        return deathLore(config.deathPolicy());
    }
}
