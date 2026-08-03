package de.raindancer.core;

import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.core.content.achievement.Achievements;
import de.raindancer.core.ui.banner.Banner;
import de.raindancer.core.ui.bossbar.BossBars;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.chat.ClickActions;
import de.raindancer.core.ui.chat.ClickResult;
import de.raindancer.core.ui.chat.Style;
import de.raindancer.core.ui.menu.MenuListener;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.content.items.CustomItems;
import de.raindancer.core.content.items.ItemAbilities;
import de.raindancer.core.content.items.ItemFactory;
import de.raindancer.core.content.loot.LootFiller;
import de.raindancer.core.content.loot.LootTables;
import de.raindancer.core.moderation.punishment.PunishmentGuard;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.core.moderation.punishment.PunishmentListener;
import de.raindancer.core.moderation.punishment.Punishments;
import de.raindancer.core.moderation.punishment.VanillaBanBridge;
import de.raindancer.core.platform.bukkit.BukkitActionBarSink;
import de.raindancer.core.platform.bukkit.BukkitAudiences;
import de.raindancer.core.platform.bukkit.BukkitBarViewers;
import de.raindancer.core.ui.identity.Identities;
import de.raindancer.core.world.poi.PoiStore;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.core.ui.prompt.PromptListener;
import de.raindancer.core.ui.scoreboard.FastBoardFactory;
import de.raindancer.core.ui.scoreboard.Scoreboards;
import de.raindancer.core.data.sql.Databases;
import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.data.settings.SettingsChatInput;
import de.raindancer.core.data.settings.SettingsCommand;
import de.raindancer.core.data.settings.SettingsNavigation;
import de.raindancer.core.data.settings.SettingsRegistry;
import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.ui.tablist.TablistModel;
import de.raindancer.core.ui.tablist.Tablists;
import de.raindancer.core.world.chunk.BukkitChunkLoader;
import de.raindancer.core.world.chunk.ChunkHolds;
import de.raindancer.core.ui.effect.BukkitEffectSink;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.moderation.invsee.Inventories;
import de.raindancer.core.moderation.invsee.InvseeListener;
import de.raindancer.core.moderation.invsee.InventoryViews;
import de.raindancer.core.moderation.invsee.OfflineEdits;
import de.raindancer.core.moderation.players.BukkitPlayerAdminSink;
import de.raindancer.core.moderation.players.PlayerAdmin;
import de.raindancer.core.moderation.vanish.BukkitVanishSink;
import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.moderation.vanish.VanishListener;
import de.raindancer.core.content.vote.Votes;
import de.raindancer.core.content.pack.BukkitPackSink;
import de.raindancer.core.content.pack.PackListener;
import de.raindancer.core.content.pack.PackMode;
import de.raindancer.core.content.pack.PackServer;
import de.raindancer.core.content.pack.ResourcePacks;
import de.raindancer.core.world.safety.BukkitBlocks;
import de.raindancer.core.world.safety.Safety;
import de.raindancer.core.world.warp.Warps;
import de.raindancer.core.world.farm.FarmWorldPortalListener;
import de.raindancer.core.world.combat.Combat;
import de.raindancer.core.world.combat.CombatListener;
import de.raindancer.core.world.farm.FarmWorldState;
import de.raindancer.core.world.farm.FarmWorlds;
import de.raindancer.core.world.protection.BlockProtectionListener;
import de.raindancer.core.world.protection.EnvironmentProtectionListener;
import de.raindancer.core.world.protection.InteractionProtectionListener;
import de.raindancer.core.world.protection.Land;
import de.raindancer.core.world.protection.LandPolicies;
import de.raindancer.core.world.protection.LandPolicyStore;
import de.raindancer.core.world.protection.MobControlListener;
import de.raindancer.core.world.protection.MovementProtectionListener;
import de.raindancer.core.world.protection.Seclusion;
import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
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
 * {@link de.raindancer.core.ui.chat.Audiences}, {@link ClickActions} takes a clock — and this is where
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
    /**
     * How often the stores are written, in the seconds the async scheduler counts in.
     *
     * <p>Seconds rather than ticks because this is off the server's threads, where a tick is not a
     * unit of anything.
     */
    private static final long SAVE_PERIOD_SECONDS = 120L;

    /**
     * How often the audit journal is written, in seconds.
     *
     * <p>Ten, rather than the two minutes the YAML stores use. An audit entry lost to a crash is an
     * entry nobody can get back, and the cost of writing more often is one transaction per ten
     * seconds on a database nothing else is contending for.
     */
    private static final long AUDIT_FLUSH_PERIOD_SECONDS = 10L;

    /**
     * How often entries past the retention period are deleted, in seconds.
     *
     * <p>Hourly. Retention is measured in days, so anything more frequent is a range delete over the
     * largest table on the server to remove nothing.
     */
    private static final long AUDIT_PRUNE_PERIOD_SECONDS = 60L * 60L;

    /**
     * How often the tablist is rebuilt.
     *
     * <p>Two seconds. A player's world changes on an event and their ping changes continuously, so
     * an event-only tablist shows a stale latency for ever — but nothing here is urgent, and a
     * tablist rebuilt every tick is packets nobody asked for.
     */
    private static final long TABLIST_PERIOD_TICKS = 40L;

    /**
     * How often it is worked out who can see whom.
     *
     * <p>Ten ticks. Half a second of being visible while walking out of a private garden is not a privacy
     * failure, and doing it per move event is a lookup per player per tick for an answer that changes when
     * somebody crosses a line.
     */
    private static final long SECLUSION_PERIOD_TICKS = 10L;

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
    private Land land;
    private LandPolicies landPolicies;
    private LandPolicyStore landPolicyStore;
    private MovementProtectionListener movementProtection;
    private Seclusion seclusion;
    private ResourcePacks resourcePacks;
    private ChunkHolds chunks;
    private Safety safety;
    private Effects effects;
    private Votes votes;
    private Vanish vanish;
    private PlayerAdmin players;
    private InventoryViews inventoryViews;
    private Inventories inventories;
    private Databases databases;
    private Combat combat;
    private CombatListener combatListener;
    private Messages messages;
    /** False while the stores are being read, true once players can be on. */
    private volatile boolean watchingThreads;

    /**
     * The timers that write to disk, kept so shutdown can stop them.
     *
     * <p>Discarded handles were a race at shutdown: {@code onDisable} flushes and then closes the
     * databases, and an async timer firing in between writes into a database that is closing. The
     * entries it was carrying are then lost, quietly, exactly when a shutdown is the last chance to
     * write them.
     */
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask auditFlushTask;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask savingTask;
    private Audit audit;
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

        // Before every store, because each of them is handed one. Opening a database applies its
        // schema, so a subsystem given one whose tables are not there yet fails a query at a time in
        // a different place each time.
        //
        // Loading on this thread is deliberate and is the one exemption: a plugin must not report
        // itself as enabled before its data has arrived, so the stores are read before onEnable
        // returns. Nothing is being stalled that anybody can see, because nobody is on yet.
        //
        // The guard is therefore armed at the end of onEnable rather than switched off. Passing a
        // supplier that always answers false would have disabled it for the whole run — a safety net
        // that never fires, which is worse than none because it reads as proof.
        databases = new Databases(getDataFolder().toPath(),
                () -> watchingThreads && getServer().isPrimaryThread());
        audit = new Audit(databases.audit(), System::currentTimeMillis);

        // Before anything that says anything. The file is written out on the first start, and on every
        // start after that only the keys this version added are appended to it — the owner's wording,
        // ordering and comments are left exactly as they are, and the previous file is copied aside
        // first. A key that is missing anyway falls back to the jar's wording, so the merge failing
        // costs the owner the chance to reword the new lines rather than the lines themselves.
        messages = new Messages(getDataFolder().toPath().resolve("messages.yml"));
        messages.mergeMissing(getResource("messages.yml"));
        messages.load(getResource("messages.yml"));
        if (!messages.problems().isEmpty()) {
            log.warn("messages.yml: {}", String.join("; ", messages.problems()));
        }

        places = new PoiStore(databases.core());
        places.load();

        identities = new Identities(databases.core());
        identities.load();

        punishments = new Punishments(databases.core(),
                System::currentTimeMillis);
        punishments.load();
        punishmentGuard = new PunishmentGuard(punishments, System::currentTimeMillis);
        punishmentGuard.messages(messages);
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
                databases.core(),
                System::currentTimeMillis);
        achievements.load();

        tablists = new Tablists(new TablistModel(identities), getServer().getMotd());
        applyTablistSettings();
        // Applied again whenever they change, so switching the custom list off in a menu puts
        // every name back rather than freezing whatever was last drawn.
        settings.onChange(config -> applyTablistSettings());
        warps = new Warps(places, System::currentTimeMillis);

        FarmWorldState farmState = new FarmWorldState(
                getDataFolder().toPath().resolve("farmworlds.yml"), databases.core());
        farmState.load();
        farmWorlds = new FarmWorlds(this, farmState);
        for (var set : farmState.all()) {
            farmWorlds.ensure(set);
        }
        getServer().getPluginManager().registerEvents(
                new FarmWorldPortalListener(farmWorlds), this);
        // World protection. Registered here with nothing to protect: the ground itself comes from whichever
        // plugin owns regions, and it registers a LandProvider once it is enabled. Until then every question
        // answers UNKNOWN rather than "nothing is protected" — see Land and LandVerdict.
        // Read from disk, not built fresh. Held in memory alone, an admin turning a flag off got what they
        // asked for until the next restart — which is worse than not offering the setting, because the
        // server then behaves differently after a restart than it did before one and nothing says why.
        landPolicyStore = new LandPolicyStore(getDataFolder().toPath().resolve("land-flags.yml"));
        landPolicies = landPolicyStore.load();
        landPolicyStore.problem().ifPresent(trouble -> log.warn("land-flags.yml: {}", trouble));
        land = new Land(landPolicies, messages, System::currentTimeMillis);
        getServer().getPluginManager().registerEvents(new BlockProtectionListener(land), this);
        getServer().getPluginManager().registerEvents(new InteractionProtectionListener(land, messages), this);
        EnvironmentProtectionListener environmentProtection = new EnvironmentProtectionListener(land);
        getServer().getPluginManager().registerEvents(environmentProtection, this);
        getServer().getPluginManager().registerEvents(new MobControlListener(land), this);
        movementProtection = new MovementProtectionListener(land, messages);
        // Told about each other after both exist, rather than one taking the other in its constructor: the
        // damage listener has to be registered before this one, and a constructor argument would be a cycle.
        environmentProtection.grounding(movementProtection);
        getServer().getPluginManager().registerEvents(movementProtection, this);

        // Who can be seen from outside a private area. On a timer rather than on every move: two players
        // walking towards each other generate a move event each per tick, and the answer only changes when
        // one of them crosses a border.
        seclusion = new Seclusion(this, land);
        Scheduling.globalTimer(this, SECLUSION_PERIOD_TICKS, SECLUSION_PERIOD_TICKS,
                task -> seclusion.refresh());

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
        // A chat answer arrives on a Netty thread, and a plugin's callback almost always touches the
        // player. Dispatched onto the thread that owns them, so no plugin using this has to know.
        prompts.runCallbacksOn((who, task) -> {
            org.bukkit.entity.Player answering = getServer().getPlayer(who);
            if (answering == null) {
                // They left between typing and this running. Answering "no" rather than dropping it
                // silently, so the line is reported as undelivered instead of as answered.
                return false;
            }
            Scheduling.entity(this, answering, task);
            return true;
        });
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
                // On their own thread. A window is closed from wherever the reason arrived — a
                // login on the connection thread, a quit on somebody else's region thread — and on
                // Folia touching another player's inventory from the wrong region is an
                // IllegalStateException that takes the event with it. On Paper this is simply the
                // next tick.
                Scheduling.entity(this, looking, looking::closeInventory);
            }
        });
        // The playerdata folder of the main world, which is where the server writes everybody who
        // is not currently on it. Worked out once rather than per read: getWorlds() is a copy.
        inventories = new Inventories(this, inventoryViews, new OfflineEdits(System::currentTimeMillis),
                getServer().getWorlds().get(0).getWorldFolder().toPath().resolve("playerdata"));
        // Every look and every change written down. Handed over here rather than taken in the
        // constructor because the journal needs its database, which needs the data folder, which is
        // not there until the plugin is enabling.
        inventories.audit(audit);
        inventories.messages(messages);
        getServer().getPluginManager().registerEvents(new InvseeListener(inventories), this);
        applyNewSettings(settings.current());
        // Re-applied on every change, so a toggle in the menu takes hold without a restart —
        // which is the difference between a setting somebody uses and one they read about.
        settings.onChange(this::applyNewSettings);
        combat = new Combat();
        applyCombatSettings();
        // Kept, because it holds one entry per player it has had to refuse and the quit handler has
        // to be able to drop them. A map that only grows is a leak on a server that runs for months.
        combatListener = new CombatListener(combat, System::currentTimeMillis, messages);
        // The damage event fires in the victim's region, and the attacker may be standing in another —
        // shooting across a boundary is ordinary. So the message goes out on their own thread.
        combatListener.tellOn((player, task) -> Scheduling.entity(this, player, task));
        getServer().getPluginManager().registerEvents(combatListener, this);

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

        // From here on, database work on a thread running the world is a mistake and is reported as
        // one. Before here it was the startup read, which is allowed.
        watchingThreads = true;

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
                    // An offline edit whose moderator crashed would otherwise hold that player out
                    // of the server until a restart.
                    inventories.sweep();
                });
        Scheduling.globalTimer(this, TABLIST_PERIOD_TICKS, TABLIST_PERIOD_TICKS,
                task -> tablists.refresh());
        // Written on a timer rather than on every change: a disk write every time somebody sets a
        // home would be a disk write on the main thread, and isDirty() means an idle server writes
        // nothing at all.
        // The audit journal is written off the server's threads: recording an entry only queues it,
        // and this is where the queue is turned into rows. Separate from the flushes below because
        // those write YAML on the main thread by design and this must not.
        auditFlushTask = Scheduling.asyncTimer(this, AUDIT_FLUSH_PERIOD_SECONDS,
                AUDIT_FLUSH_PERIOD_SECONDS, task -> audit.flush());
        Scheduling.asyncTimer(this, AUDIT_PRUNE_PERIOD_SECONDS, AUDIT_PRUNE_PERIOD_SECONDS,
                task -> audit.forgetOlderThan(
                        java.time.Duration.ofDays(settings.current().auditRetentionDays())));
        // On the ASYNC timer, not the global one. That was wrong until a review pointed it out: every
        // one of these writes a file or a database, and the global timer runs on the thread that ticks
        // the world. Two minutes between freezes is still a freeze.
        //
        // Worth noting how it survived this long: the guard in Databases would have reported it, and
        // the live checks never ran long enough for the first tick to arrive. A safety net only helps
        // if something reaches it.
        savingTask = Scheduling.asyncTimer(this, SAVE_PERIOD_SECONDS, SAVE_PERIOD_SECONDS, task -> {
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
        if (combat != null) {
            // A change in the menu takes hold without a restart, which is the difference between a
            // setting somebody uses and one they read about.
            applyCombatSettings();
        }
        effects.enabled(config.effectsEnabled());
        effects.minimumGap(java.time.Duration.ofMillis(config.effectsRepeatGapMillis()));
        vanish.flightWhileVanished(config.vanishFlight());
        if (tablists != null) {
            tablists.model().showPing(config.tablistShowPing());
            tablists.model().title(config.tablistTitle());
            tablists.model().logo("auto".equalsIgnoreCase(config.tablistLogo().trim())
                    ? de.raindancer.core.ui.tablist.TablistModel.logoFor(getServer().getMotd())
                    : framesOf(config.tablistLogo()));
            tablists.headerFrames(framesOf(config.tablistHeaderFrames()), config.tablistFrameTicks());
            tablists.footerFrames(framesOf(config.tablistFooterFrames()), config.tablistFrameTicks());
        }
    }

    /**
     * Animation frames as a server owner writes them: one line, separated by a bar.
     *
     * <p>A list in a flat settings file has to be a string, and a bar is the one separator that does
     * not appear in MiniMessage or in anything anybody would put in a header.
     */
    private static java.util.List<String> framesOf(String written) {
        if (written == null || written.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(written.split("\\|"))
                .map(String::trim)
                .filter(frame -> !frame.isEmpty())
                .toList();
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
        if (seclusion != null) {
            // Before anything else, and for the same reason as the chunk holds below: somebody left hidden by
            // a reload is invisible until they reconnect, with nothing on their screen to explain it.
            seclusion.revealEverybody();
        }
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
        // Before anything is flushed by hand: a timer that fires while this method is closing the
        // databases writes into one that is going away, and loses whatever it was carrying.
        stopTimer(savingTask);
        stopTimer(auditFlushTask);
        if (audit != null) {
            // Before the databases are closed, and on this thread rather than scheduled: the
            // scheduler is already shutting down, so a task handed to it now may never run — and
            // this is the one flush whose entries cannot be recovered from anywhere else.
            int written = audit.flush();
            if (written > 0) {
                log.info("Wrote {} audit entr{} on the way out.", written,
                        written == 1 ? "y" : "ies");
            }
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
        if (databases != null) {
            // After every flush above and before the logfile: closing a database folds its
            // write-ahead log back into the file, and a database left open keeps a .db-wal beside
            // it that somebody taking a backup will not copy.
            databases.close();
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

    /**
     * Reads the combat half of the settings onto the rules.
     *
     * <p>Only the server-wide switches. A world's own rule is set by whoever wants one — a plugin, or
     * a command — and reading the settings must not wipe those: somebody who reloads the config would
     * otherwise turn PvP back on inside an arena without touching anything to do with it.
     */
    private void applyCombatSettings() {
        CoreConfig config = settings.current();
        combat.pvp(config.combatPvp());
        combat.playersMayHurtMobs(config.combatPlayersMayHurtMobs());
        combat.mobsMayHurtPlayers(config.combatMobsMayHurtPlayers());
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
    public Land land() {
        return land;
    }

    @Override
    public LandPolicies landPolicies() {
        return landPolicies;
    }

    @Override
    public boolean saveLandPolicies() {
        try {
            landPolicyStore.save(landPolicies);
            return true;
        } catch (IOException couldNotWrite) {
            // The change is already live, so this is not worth refusing over — but an admin who is told
            // nothing will find their decision gone after the next restart and have no idea when it went.
            log.error("Could not write land-flags.yml ({}). Your flag change is in force now but will be "
                    + "lost when the server restarts.", couldNotWrite.getMessage());
            return false;
        }
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
    public Messages messages() {
        return messages;
    }

    @Override
    public Audit audit() {
        return audit;
    }

    @Override
    public Combat combat() {
        return combat;
    }

    @Override
    public Databases databases() {
        return databases;
    }

    @Override
    public Inventories inventories() {
        return inventories;
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
        effects.forget(player.getUniqueId());
        land.forget(player.getUniqueId());
        movementProtection.forget(player.getUniqueId());
        seclusion.forget(player.getUniqueId());
        if (combatListener != null) {
            combatListener.forget(player.getUniqueId());
        }
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
            case NOT_YOURS -> chat.raw(clicker, messages.prefixed("button.not-yours"));
            case SPENT -> chat.raw(clicker, messages.prefixed("button.already-answered"));
            case EXPIRED, UNKNOWN -> chat.raw(clicker,
                    messages.prefixed("button.no-longer-offered"));
            case FAILED -> chat.raw(clicker, messages.prefixed("button.failed"));
        }
    }
    /**
     * Stops one timer, if it was ever started.
     *
     * <p>Guarded because {@code onDisable} also runs when {@code onEnable} failed halfway, and a
     * plugin that throws on the way down while shutting down hides whatever went wrong on the way up.
     */
    private static void stopTimer(
            io.papermc.paper.threadedregions.scheduler.ScheduledTask task) {
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }

}
