package de.raindancer.core;

import de.raindancer.core.actionbar.ActionBars;
import de.raindancer.core.achievement.Achievements;
import de.raindancer.core.banner.Banner;
import de.raindancer.core.bossbar.BossBars;
import de.raindancer.core.chat.Brand;
import de.raindancer.core.chat.Chat;
import de.raindancer.core.chat.ChatButtons;
import de.raindancer.core.chat.ClickActions;
import de.raindancer.core.chat.ClickResult;
import de.raindancer.core.chat.Style;
import de.raindancer.core.gui.MenuListener;
import de.raindancer.core.log.Log;
import de.raindancer.core.log.LogChannel;
import de.raindancer.core.items.CustomItems;
import de.raindancer.core.items.ItemAbilities;
import de.raindancer.core.items.ItemFactory;
import de.raindancer.core.loot.LootFiller;
import de.raindancer.core.loot.LootTables;
import de.raindancer.core.moderation.PunishmentGuard;
import de.raindancer.core.moderation.PunishmentKind;
import de.raindancer.core.moderation.PunishmentListener;
import de.raindancer.core.moderation.Punishments;
import de.raindancer.core.moderation.VanillaBanBridge;
import de.raindancer.core.platform.BukkitActionBarSink;
import de.raindancer.core.platform.BukkitAudiences;
import de.raindancer.core.platform.BukkitBarViewers;
import de.raindancer.core.identity.Identities;
import de.raindancer.core.poi.PoiStore;
import de.raindancer.core.prompt.ChatPrompts;
import de.raindancer.core.prompt.PromptListener;
import de.raindancer.core.scoreboard.FastBoardFactory;
import de.raindancer.core.scoreboard.Scoreboards;
import de.raindancer.core.settings.SettingsChatInput;
import de.raindancer.core.settings.SettingsCommand;
import de.raindancer.core.settings.SettingsNavigation;
import de.raindancer.core.settings.SettingsRegistry;
import de.raindancer.core.settings.SettingsSchema;
import de.raindancer.core.settings.SettingsStore;
import de.raindancer.core.tablist.TablistModel;
import de.raindancer.core.tablist.Tablists;
import de.raindancer.core.chunk.BukkitChunkLoader;
import de.raindancer.core.chunk.ChunkHolds;
import de.raindancer.core.effect.BukkitEffectSink;
import de.raindancer.core.effect.Effects;
import de.raindancer.core.invsee.InventoryViews;
import de.raindancer.core.player.BukkitPlayerAdminSink;
import de.raindancer.core.player.PlayerAdmin;
import de.raindancer.core.vanish.BukkitVanishSink;
import de.raindancer.core.vanish.Vanish;
import de.raindancer.core.vanish.VanishListener;
import de.raindancer.core.vote.Votes;
import de.raindancer.core.pack.BukkitPackSink;
import de.raindancer.core.pack.PackListener;
import de.raindancer.core.pack.PackMode;
import de.raindancer.core.pack.PackServer;
import de.raindancer.core.pack.ResourcePacks;
import de.raindancer.core.safety.BukkitBlocks;
import de.raindancer.core.safety.Safety;
import de.raindancer.core.warp.Warps;
import de.raindancer.core.world.FarmWorldPortalListener;
import de.raindancer.core.world.FarmWorldState;
import de.raindancer.core.world.FarmWorlds;
import de.raindancer.core.util.Scheduling;
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

    /** How often saved places are written out, if anything changed. */
    private static final long SAVE_PERIOD_TICKS = 20L * 60L * 2L;

    /**
     * How often the tablist is rebuilt.
     *
     * <p>Two seconds. A player's world changes on an event and their ping changes continuously, so
     * an event-only tablist shows a stale latency for ever — but nothing here is urgent, and a
     * tablist rebuilt every tick is packets nobody asked for.
     */
    private static final long TABLIST_PERIOD_TICKS = 40L;

    /** How often farm worlds are asked whether any is due. Cheap; the regeneration is not. */
    private static final long REGEN_CHECK_TICKS = 20L * 60L;

    /**
     * How long after startup the resource pack is built.
     *
     * <p>A tick after everything has enabled, not during our own onEnable: the plugins that
     * contribute assets have not been enabled yet at that point, so building then would build a
     * pack with nothing in it.
     */
    private static final long PACK_BUILD_DELAY_TICKS = 20L;

    private static volatile RainsCorePlugin instance;

    private SettingsStore<CoreConfig> settings;
    private Chat chat;
    private ActionBars actionBars;
    private ClickActions clickActions;
    private ChatButtons buttons;
    private Scoreboards scoreboards;
    private BossBars bossBars;
    private PoiStore places;
    private Identities identities;
    private Punishments punishments;
    private PunishmentGuard punishmentGuard;
    private VanillaBanBridge banBridge;
    private CustomItems items;
    private ItemFactory itemFactory;
    private ItemAbilities itemAbilities;
    private Achievements achievements;
    private LootTables lootTables;
    private LootFiller lootFiller;

    /** Every plugin's settings, so the combined GUI can find them. Keyed by the schema's id. */
    private final Map<String, SettingsStore<?>> stores = new ConcurrentHashMap<>();
    /** The same, merged into one tree for the menu and the command. */
    private final SettingsRegistry registry = new SettingsRegistry();
    private SettingsNavigation navigation;
    private Tablists tablists;
    private ChatPrompts prompts;
    private Warps warps;
    private FarmWorlds farmWorlds;
    private ResourcePacks resourcePacks;
    private ChunkHolds chunks;
    private Safety safety;
    private Effects effects;
    private Votes votes;
    private Vanish vanish;
    private PlayerAdmin players;
    private InventoryViews inventoryViews;
    private PackServer packServer;

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

        places = new PoiStore(getDataFolder().toPath().resolve("places.yml"));
        places.load();

        identities = new Identities(getDataFolder().toPath().resolve("identities.yml"));
        identities.load();

        punishments = new Punishments(getDataFolder().toPath().resolve("punishments.yml"),
                System::currentTimeMillis);
        punishments.load();
        punishmentGuard = new PunishmentGuard(punishments, System::currentTimeMillis);
        banBridge = new VanillaBanBridge(punishments);
        applyModerationSettings();
        settings.onChange(config -> applyModerationSettings());
        getServer().getPluginManager().registerEvents(
                new PunishmentListener(punishmentGuard), this);

        items = new CustomItems(getDataFolder().toPath().resolve("items.yml"));
        items.load();
        itemFactory = new ItemFactory(this);
        itemAbilities = new ItemAbilities(System::currentTimeMillis);

        lootTables = new LootTables(getDataFolder().toPath().resolve("loot.yml"));
        lootTables.load();
        lootFiller = new LootFiller(items, itemFactory);

        achievements = new Achievements(getDataFolder().toPath().resolve("achievements.yml"),
                System::currentTimeMillis);
        achievements.load();

        tablists = new Tablists(new TablistModel(identities), getServer().getMotd());
        applyTablistSettings();
        // Applied again whenever they change, so switching the custom list off in a menu puts
        // every name back rather than freezing whatever was last drawn.
        settings.onChange(config -> applyTablistSettings());
        warps = new Warps(places, System::currentTimeMillis);

        FarmWorldState farmState = new FarmWorldState(
                getDataFolder().toPath().resolve("farmworlds.yml"));
        farmState.load();
        farmWorlds = new FarmWorlds(this, farmState);
        for (var set : farmState.all()) {
            farmWorlds.ensure(set);
        }
        getServer().getPluginManager().registerEvents(
                new FarmWorldPortalListener(farmWorlds), this);
        clickActions = new ClickActions(System::currentTimeMillis);
        // Deliberately empty: Core registers no commands at all, so it has no callback command to
        // point buttons at until a plugin registers one and says what it called it. Until then
        // buttons render as readable text without a click, rather than as a button that silently
        // does nothing — see ChatButtons and CoreCommands.
        buttons = new ChatButtons(clickActions, "");
        chat = chatFor("Core");
        navigation = new SettingsNavigation(registry);
        // One chat listener for every prompt on the server. Three plugins each registering their
        // own is three plugins fighting over the next line a player types.
        prompts = new ChatPrompts(System::currentTimeMillis);
        getServer().getPluginManager().registerEvents(new PromptListener(prompts), this);
        // The commands are registered by RainsCoreBootstrap, before this runs. Paper fires the
        // COMMANDS lifecycle event during bootstrap, so a handler registered here would never fire
        // at all — silently, which is how both commands were dead until a live server was tried.
        new SettingsChatInput(this, navigation, chat, chat.brand(), prompts);

        effects = new Effects(new BukkitEffectSink(), System::currentTimeMillis);
        votes = new Votes(System::currentTimeMillis);
        players = new PlayerAdmin(new BukkitPlayerAdminSink());
        vanish = new Vanish(new BukkitVanishSink(this));
        getServer().getPluginManager().registerEvents(
                new VanishListener(this, vanish, "rainscore.vanish.see"), this);
        inventoryViews = new InventoryViews(watcher -> {
            org.bukkit.entity.Player looking =
                    getServer().getPlayer(java.util.UUID.fromString(watcher));
            if (looking != null) {
                looking.closeInventory();
            }
        });
        applyNewSettings(settings.current());
        chunks = new ChunkHolds(new BukkitChunkLoader(this));
        // A world by name, or null when it is not loaded — the seam that keeps every rule about
        // what is safe testable without a server.
        safety = new Safety(chunks, BukkitBlocks::of);

        // The one pack every plugin's assets end up in. Built off the main thread, because it is a
        // zip of everything every plugin contributed and a server that stalls on startup for it is
        // a server nobody waits for.
        resourcePacks = new ResourcePacks(getDataFolder().toPath().resolve("packs"),
                new BukkitPackSink());
        startResourcePacks(settings.current());

        getServer().getPluginManager().registerEvents(this, this);
        // One listener for every menu in every plugin: a menu is its inventory's holder, so this
        // recognises our windows without a registry that would outlive the players in it.
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        // The scheduler hands the task its own handle, which neither of these wants.
        Scheduling.globalTimer(this, ACTION_BAR_PERIOD_TICKS, ACTION_BAR_PERIOD_TICKS,
                task -> actionBars.tick());
        Scheduling.globalTimer(this, SWEEP_PERIOD_TICKS, SWEEP_PERIOD_TICKS,
                task -> {
                    clickActions.sweep();
                    prompts.sweep();
                    votes.sweep();
                });
        Scheduling.globalTimer(this, TABLIST_PERIOD_TICKS, TABLIST_PERIOD_TICKS,
                task -> tablists.refresh());
        // Written on a timer rather than on every change: a disk write every time somebody sets a
        // home would be a disk write on the main thread, and isDirty() means an idle server writes
        // nothing at all.
        Scheduling.globalTimer(this, SAVE_PERIOD_TICKS, SAVE_PERIOD_TICKS, task -> {
            places.flush();
            identities.flush();
            punishments.flush();
            items.flush();
            achievements.flush();
            lootTables.flush();
            farmWorlds.state().flush();
        });
        // Its own, much slower timer: regenerating stops the server for as long as the disk takes,
        // so it is checked once a minute rather than folded in with the saves.
        Scheduling.globalTimer(this, REGEN_CHECK_TICKS, REGEN_CHECK_TICKS,
                task -> farmWorlds.regenerateWhatIsDue());

        Banner banner = Banner.of(getName(), "core utils for Raindancer118's plugins")
                .version(getPluginMeta().getVersion())
                .by("Raindancer118")
                .fact("Settings", registry.keys().size() + " across "
                        + registry.topics().visibleRoots().size() + " categories")
                .fact("Logs", getDataFolder().toPath().resolve("logs").toString())
                .fact("Places", places.all().size() + " remembered")
                .fact("Warps", warps.all().size() + " set")
                .fact("In force", punishments.allActive().size() + " punishment(s)")
                .fact("Items", items.all().size() + " defined")
                .fact("Achievements", achievements.all().size() + " defined")
                .fact("Loot tables", lootTables.all().size() + " defined")
                .fact("Scheduler", Scheduling.isFolia() ? "Folia, regionised" : "Paper");
        for (String problem : places.problems()) {
            banner.warning("places.yml: " + problem);
        }
        for (String problem : settings.problems()) {
            log.warn("config.yml: {}", problem);
            banner.warning("config.yml: " + problem);
        }
        banner.took(java.time.Duration.ofNanos(System.nanoTime() - startedAt))
                .print(getComponentLogger());
    }

    /**
     * Puts the settings for the newer subsystems into effect.
     *
     * <p>Called at startup and whenever the settings change, so a toggle in the menu takes hold
     * without a restart — which is the difference between a setting somebody uses and one they read
     * about.
     */
    private void applyNewSettings(CoreConfig config) {
        effects.enabled(config.effectsEnabled());
        effects.minimumGap(java.time.Duration.ofMillis(config.effectsRepeatGapMillis()));
        vanish.flightWhileVanished(config.vanishFlight());
    }

    /**
     * Brings the resource pack up: settings, the web server, then a build off the main thread.
     *
     * <p>Deliberately survives every part of itself failing. A port that is taken, a contributed
     * pack that is not a zip, or no contributions at all must leave a server that starts normally
     * and plays normally — a resource pack is not worth refusing to boot over.
     */
    private void startResourcePacks(CoreConfig config) {
        resourcePacks.required(config.packsRequired());
        resourcePacks.description(config.packsDescription());
        resourcePacks.mode(config.packsCombine() ? PackMode.COMBINED : PackMode.STACKED);
        if (!config.packsEnabled()) {
            return;
        }
        if (config.packsServe()) {
            packServer = new PackServer(getDataFolder().toPath().resolve("packs"),
                    config.packsBind(), config.packsPort());
            packServer.publicAddress(config.packsPublicAddress());
            try {
                packServer.start();
                resourcePacks.urls(packServer::urlFor);
            } catch (java.io.IOException failure) {
                // Said plainly rather than thrown: the rest of the server is fine, and an owner
                // whose port 8123 is taken needs to read that sentence, not a stack trace.
                log.error("The resource pack server could not start on {}:{} ({}). Plugin assets "
                        + "will not be sent.", config.packsBind(), config.packsPort(),
                        failure.getMessage());
                packServer = null;
            }
        }
        getServer().getPluginManager().registerEvents(
                new PackListener(resourcePacks, config.packsOnJoin()), this);
        // Off the main thread on purpose, and after everything else has enabled: the plugins that
        // contribute have not all been enabled yet at this point in our own onEnable.
        Scheduling.globalLater(this, PACK_BUILD_DELAY_TICKS, () -> Scheduling.async(this, () -> {
            if (resourcePacks.contributions().isEmpty()) {
                return;
            }
            resourcePacks.rebuild().ifPresent(built -> log.info(
                    "Resource pack ready: {} from {} contribution(s), {}",
                    built.digest().substring(0, 12), built.contributions(), built.readableSize()));
        }));
    }

    @Override
    public void onDisable() {
        // The logfile last: everything above may want to say something on the way out.
        instance = null;
        if (chunks != null) {
            // Before anything else that touches the world: a force-loaded chunk is written into the
            // world's own data and survives a restart, so one left behind here is one ticking for
            // ever with nothing to say why.
            int released = chunks.releaseAll();
            if (released > 0) {
                log.info("Let go of {} chunk(s) that were being kept loaded.", released);
            }
        }
        if (packServer != null) {
            // Before anything else: it holds a port and two threads, and a reload that left them
            // behind would stop the next start from binding at all.
            packServer.stop();
            packServer = null;
        }
        if (places != null) {
            places.flush();
        }
        if (identities != null) {
            identities.flush();
        }
        if (farmWorlds != null) {
            farmWorlds.state().flush();
        }
        if (punishments != null) {
            punishments.flush();
        }
        if (items != null) {
            items.flush();
        }
        if (achievements != null) {
            achievements.flush();
        }
        if (lootTables != null) {
            lootTables.flush();
        }
        if (tablists != null) {
            // Before anything else: the teams it makes live on the main scoreboard, and one left
            // behind survives a /reload with nothing holding it.
            tablists.shutdown();
        }
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
    /**
     * Reads the moderation half of the settings onto the guard.
     *
     * <p>Recording and enforcing are separate switches on purpose: the record is useful on its own —
     * it is the history a moderator reads — and a server driving punishments from somewhere else
     * should be able to keep its own behaviour without losing it.
     */
    private void applyModerationSettings() {
        CoreConfig config = settings.current();
        punishmentGuard.enabled(config.enforcePunishments());
        punishmentGuard.enforce(PunishmentKind.MUTE, config.enforceMutes());
        punishmentGuard.enforce(PunishmentKind.FREEZE, config.enforceFreezes());
        punishmentGuard.appealMessage(config.appealMessage());
    }

    /** Reads the tablist's half of the settings onto it. */
    private void applyTablistSettings() {
        CoreConfig config = settings.current();
        tablists.enabled(config.tablistEnabled());
        tablists.groupByWorld(config.tablistGroupByWorld());
        tablists.showWorldOnEachLine(config.tablistWorldOnEachLine());
        tablists.header(config.tablistHeader());
        tablists.footer(config.tablistFooter());
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
    public CustomItems items() {
        return items;
    }

    @Override
    public ItemAbilities itemAbilities() {
        return itemAbilities;
    }

    @Override
    public ItemFactory itemFactory() {
        return itemFactory;
    }

    @Override
    public LootTables lootTables() {
        return lootTables;
    }

    @Override
    public LootFiller lootFiller() {
        return lootFiller;
    }

    @Override
    public Achievements achievements() {
        return achievements;
    }

    @Override
    public Punishments punishments() {
        return punishments;
    }

    @Override
    public PunishmentGuard punishmentGuard() {
        return punishmentGuard;
    }

    @Override
    public VanillaBanBridge banBridge() {
        return banBridge;
    }

    @Override
    public Identities identities() {
        return identities;
    }

    @Override
    public PoiStore places() {
        return places;
    }

    @Override
    public BossBars bossBars() {
        return bossBars;
    }

    @Override
    public Warps warps() {
        return warps;
    }

    @Override
    public FarmWorlds farmWorlds() {
        return farmWorlds;
    }

    @Override
    public ResourcePacks resourcePacks() {
        return resourcePacks;
    }

    @Override
    public Safety safety() {
        return safety;
    }

    @Override
    public ChunkHolds chunks() {
        return chunks;
    }

    @Override
    public Effects effects() {
        return effects;
    }

    @Override
    public Votes votes() {
        return votes;
    }

    @Override
    public Vanish vanish() {
        return vanish;
    }

    @Override
    public PlayerAdmin players() {
        return players;
    }

    @Override
    public InventoryViews inventoryViews() {
        return inventoryViews;
    }

    @Override
    public ChatPrompts prompts() {
        return prompts;
    }

    @Override
    public Tablists tablists() {
        return tablists;
    }

    @Override
    public SettingsNavigation settingsNavigation() {
        return navigation;
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
        registry.add(store);
        return store;
    }

    /** Every plugin's settings, for the combined GUI. */
    public Map<String, SettingsStore<?>> stores() {
        return Map.copyOf(stores);
    }

    /** Every plugin's settings merged into one tree — what the menu and the command walk. */
    public SettingsRegistry settingsRegistry() {
        return registry;
    }

    // ------------------------------------------------------------------------ housekeeping

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        actionBars.forget(player.getUniqueId());
        clickActions.forget(player.getUniqueId());
        scoreboards.forget(player.getUniqueId());
        itemAbilities.forget(player.getUniqueId());
        bossBars.forget(player.getUniqueId());
        tablists.forget(player);
        warps.forget(player.getUniqueId());
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
