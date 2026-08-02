package de.raindancer.core;

import de.raindancer.core.actionbar.ActionBars;
import de.raindancer.core.banner.Banner;
import de.raindancer.core.bossbar.BossBars;
import de.raindancer.core.chat.Brand;
import de.raindancer.core.chat.Chat;
import de.raindancer.core.chat.ChatButtons;
import de.raindancer.core.chat.ClickActions;
import de.raindancer.core.chat.ClickResult;
import de.raindancer.core.chat.Style;
import de.raindancer.core.log.Log;
import de.raindancer.core.log.LogChannel;
import de.raindancer.core.platform.BukkitActionBarSink;
import de.raindancer.core.platform.BukkitAudiences;
import de.raindancer.core.platform.BukkitBarViewers;
import de.raindancer.core.scoreboard.FastBoardFactory;
import de.raindancer.core.scoreboard.Scoreboards;
import de.raindancer.core.settings.SettingsSchema;
import de.raindancer.core.settings.SettingsStore;
import de.raindancer.core.util.Scheduling;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rain's Core, as Paper loads it.
 *
 * <h2>What this class is allowed to do</h2>
 * Wiring, and nothing else. Every rule worth getting right lives behind a seam in a class that can
 * be tested without a server — {@link ActionBars} takes a sink and a clock, {@link Chat} takes an
 * {@link de.raindancer.core.chat.Audiences}, {@link ClickActions} takes a clock — and this is where
 * the real implementations of those seams are plugged in. If logic starts appearing here, it is in
 * the wrong place.
 */
public final class RainsCorePlugin extends JavaPlugin implements RainsCore, Listener {

    private static final LogChannel log = Log.of("core");

    /** How often the action bar is repainted. Four times a second: well inside the client's fade. */
    private static final long ACTION_BAR_PERIOD_TICKS = 5L;

    /** How often expired chat buttons are swept. They are cheap; once a minute is plenty. */
    private static final long SWEEP_PERIOD_TICKS = 20L * 60L;

    private static volatile RainsCorePlugin instance;

    /** The command a chat button points at. Namespaced, so it cannot collide with anybody's. */
    private static final String CLICK_COMMAND = "click";

    private SettingsStore<CoreConfig> settings;
    private Chat chat;
    private ActionBars actionBars;
    private ClickActions clickActions;
    private ChatButtons buttons;
    private Scoreboards scoreboards;
    private BossBars bossBars;

    /** Every plugin's settings, so the combined GUI can find them. Keyed by the schema's id. */
    private final Map<String, SettingsStore<?>> stores = new ConcurrentHashMap<>();

    static RainsCorePlugin instance() {
        return instance;
    }

    @Override
    public void onEnable() {
        long startedAt = System.nanoTime();
        instance = this;

        // Settings first: everything after this reads them.
        settings = settingsFor(SettingsSchema.of(CoreConfig.class, CoreConfig.DEFAULTS),
                getDataFolder().toPath().resolve("config.yml"));

        startLogging();
        settings.onChange(config -> startLogging());

        Style.configure(this::colourFor);

        actionBars = new ActionBars(new BukkitActionBarSink(), System::currentTimeMillis);
        scoreboards = new Scoreboards(new FastBoardFactory());
        bossBars = new BossBars(new BukkitBarViewers());
        clickActions = new ClickActions(System::currentTimeMillis);
        // Namespaced deliberately: /rainscore:click always resolves to this plugin's command
        // whatever else a server has installed, and a button that resolved to somebody else's
        // command would be a button that did something nobody intended.
        String namespaced = getName().toLowerCase(Locale.ROOT) + ":" + CLICK_COMMAND;
        buttons = new ChatButtons(clickActions, namespaced);
        chat = chatFor("Core");
        registerClickCommand();

        getServer().getPluginManager().registerEvents(this, this);
        // The scheduler hands the task its own handle, which neither of these wants.
        Scheduling.globalTimer(this, ACTION_BAR_PERIOD_TICKS, ACTION_BAR_PERIOD_TICKS,
                task -> actionBars.tick());
        Scheduling.globalTimer(this, SWEEP_PERIOD_TICKS, SWEEP_PERIOD_TICKS,
                task -> clickActions.sweep());

        Banner banner = Banner.of(getName(), "core utils for Raindancer118's plugins")
                .version(getPluginMeta().getVersion())
                .by("Raindancer118")
                .fact("Settings", settings.schema().settings().size() + " across "
                        + settings.schema().topics().visibleRoots().size() + " topics")
                .fact("Logs", getDataFolder().toPath().resolve("logs").toString())
                .fact("Scheduler", Scheduling.isFolia() ? "Folia, regionised" : "Paper");
        for (String problem : settings.problems()) {
            log.warn("config.yml: {}", problem);
            banner.warning("config.yml: " + problem);
        }
        banner.took(java.time.Duration.ofNanos(System.nanoTime() - startedAt))
                .print(getComponentLogger());
    }

    @Override
    public void onDisable() {
        // The logfile last: everything above may want to say something on the way out.
        instance = null;
        if (bossBars != null) {
            bossBars.shutdown();
        }
        if (scoreboards != null) {
            // Before the settings are written: a board left behind survives a /reload and there is
            // then nothing holding it to take it away.
            scoreboards.shutdown();
        }
        if (settings != null) {
            settings.save();
        }
        log.info("Rain's Core is going down.");
        Log.shutdown();
    }

    /**
     * Registers the one command this plugin owns.
     *
     * <p>Through Paper's lifecycle rather than a {@code commands:} block in the plugin file, because
     * that form also registers the bare {@code /click}, and taking a word that common out of every
     * other plugin's hands to serve an implementation detail would be rude. Here only the namespaced
     * form exists, which is the only one a button ever uses.
     */
    private void registerClickCommand() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(CLICK_COMMAND,
                        "Runs a button you clicked in chat.", new ClickCommand()));
    }

    /** The command behind every chat button. Never typed by a person on purpose. */
    private final class ClickCommand implements BasicCommand {

        @Override
        public void execute(CommandSourceStack source, String[] args) {
            CommandSender sender = source.getSender();
            if (!(sender instanceof Player clicker)) {
                chat.no(sender, "Only a player can click a button.");
                return;
            }
            if (args.length != 1) {
                // Somebody typed it by hand. There is nothing useful to offer them.
                chat.warn(clicker, "That is not something to type.");
                return;
            }
            handleClick(clicker, args[0], chat);
        }

        /**
         * Deliberately no completions.
         *
         * <p>Completing tokens would list every pending button on the server, which is exactly the
         * thing being kept out of players' reach.
         */
        @Override
        public java.util.Collection<String> suggest(CommandSourceStack source, String[] args) {
            return java.util.List.of();
        }
    }

    /**
     * Points the logger at the data folder and at whatever the settings currently say.
     *
     * <p>Called again whenever the settings change, which is how a level or a retention edited in
     * game takes effect without a restart.
     */
    private void startLogging() {
        CoreConfig config = settings.current();
        Log.configure(getDataFolder().toPath().resolve("logs"), getLogger(),
                config.consoleLevel(), config.fileLevel(), config.logRetentionDays());
    }

    /**
     * One palette colour: what the server set, or empty to let the theme decide.
     *
     * <p>{@link Style} asks for a key like {@code style.item-name}; the settings record holds the
     * same values under names of its own, so this is the one place the two vocabularies meet.
     */
    private String colourFor(String key) {
        CoreConfig config = settings.current();
        return switch (key) {
            case Style.PRESET -> config.theme().name().toLowerCase(Locale.ROOT);
            case Style.TITLE_LABEL -> config.titleLabel();
            case Style.TITLE_VALUE -> config.titleValue();
            case Style.TITLE_SEPARATOR -> config.titleSeparator();
            case Style.ITEM_NAME -> config.itemName();
            case Style.ITEM_LORE -> config.itemLore();
            case Style.OK -> config.ok();
            case Style.WARN -> config.warn();
            case Style.BAD -> config.bad();
            case Style.DANGER -> config.danger();
            case Style.BRAND_FROM -> config.brandFrom();
            case Style.BRAND_TO -> config.brandTo();
            default -> null;
        };
    }

    // ------------------------------------------------------------------------ the API

    @Override
    public Chat chatFor(String tag) {
        return chatFor(new Brand(tag));
    }

    @Override
    public Chat chatFor(Brand brand) {
        return new Chat(brand, new BukkitAudiences());
    }

    @Override
    public ActionBars actionBars() {
        return actionBars;
    }

    @Override
    public Scoreboards scoreboards() {
        return scoreboards;
    }

    @Override
    public BossBars bossBars() {
        return bossBars;
    }

    @Override
    public ChatButtons buttons() {
        return buttons;
    }

    @Override
    public ClickActions clickActions() {
        return clickActions;
    }

    @Override
    public <T> SettingsStore<T> settingsFor(Plugin plugin, Class<T> type, T defaults) {
        return settingsFor(SettingsSchema.of(type, defaults),
                plugin.getDataFolder().toPath().resolve("config.yml"));
    }

    @Override
    public <T> SettingsStore<T> settingsFor(SettingsSchema<T> schema, Path file) {
        SettingsStore<T> store = new SettingsStore<>(schema, file);
        store.load();
        // Written back at once, so a new version's settings appear in the file the first time it
        // runs rather than the first time somebody changes something.
        store.save();
        stores.put(schema.id(), store);
        return store;
    }

    /** Every plugin's settings, for the combined GUI. */
    public Map<String, SettingsStore<?>> stores() {
        return Map.copyOf(stores);
    }

    // ------------------------------------------------------------------------ housekeeping

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        actionBars.forget(player.getUniqueId());
        clickActions.forget(player.getUniqueId());
        scoreboards.forget(player.getUniqueId());
        bossBars.forget(player.getUniqueId());
    }

    /**
     * Runs the action behind a chat button.
     *
     * <p>Called by the {@code /rcore:click <token>} command. Every answer is a sentence, because a
     * button that does nothing and says nothing is a button a player clicks four more times.
     */
    public void handleClick(Player clicker, String token, Chat chat) {
        ClickResult result = clickActions.run(clicker.getUniqueId(), token);
        switch (result) {
            case RAN -> {
                // The action said whatever needed saying.
            }
            case NOT_YOURS -> chat.no(clicker, "That is not your button.");
            case SPENT -> chat.warn(clicker, "You have already answered that.");
            case EXPIRED, UNKNOWN -> chat.warn(clicker, "That is no longer on offer.");
            case FAILED -> chat.no(clicker, "That did not work. The server has written down why.");
        }
    }
}
