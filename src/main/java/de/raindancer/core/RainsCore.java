package de.raindancer.core;

import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.chat.ClickActions;
import de.raindancer.core.ui.bossbar.BossBars;
import de.raindancer.core.ui.identity.Identities;
import de.raindancer.core.content.achievement.Achievements;
import de.raindancer.core.content.items.CustomItems;
import de.raindancer.core.content.items.ItemAbilities;
import de.raindancer.core.content.items.ItemFactory;
import de.raindancer.core.content.loot.LootFiller;
import de.raindancer.core.content.loot.LootTables;
import de.raindancer.core.moderation.punishment.PunishmentGuard;
import de.raindancer.core.moderation.punishment.Punishments;
import de.raindancer.core.moderation.punishment.VanillaBanBridge;
import de.raindancer.core.world.poi.PoiStore;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.core.ui.scoreboard.Scoreboards;
import de.raindancer.core.data.settings.SettingsNavigation;
import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.ui.tablist.Tablists;
import de.raindancer.core.world.chunk.ChunkHolds;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.data.sql.Databases;
import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.moderation.invsee.Inventories;
import de.raindancer.core.moderation.invsee.InventoryViews;
import de.raindancer.core.moderation.players.PlayerAdmin;
import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.content.vote.Votes;
import de.raindancer.core.content.pack.ResourcePacks;
import de.raindancer.core.world.safety.Safety;
import de.raindancer.core.world.warp.Warps;
import de.raindancer.core.world.farm.FarmWorlds;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;

/**
 * What a plugin gets from Rain's Core.
 *
 * <h2>How a plugin reaches this</h2>
 * <pre>
 * RainsCore core = RainsCore.get();
 * Chat chat = core.chatFor("Claims");
 * SettingsStore&lt;ClaimConfig&gt; settings = core.settingsFor(this, ClaimConfig.class, ClaimConfig.DEFAULTS);
 * </pre>
 *
 * <p>An interface rather than the plugin class, so a plugin compiles against what it is allowed to
 * use and not against {@code RainsCorePlugin}'s wiring. It also means a test can hand a plugin a
 * different implementation, which is the whole reason every piece behind here has a seam.
 *
 * <h2>What is shared and what is not</h2>
 * The things that own something a player can only have one of are shared: one {@link ActionBars} for
 * the server, one {@link ClickActions} registry, one palette. The things that are a plugin's own
 * identity are not: each plugin gets its own {@link Chat} with its own {@link Brand}, so a message
 * still says who is talking.
 */
public interface RainsCore {

    /**
     * The running instance.
     *
     * @throws IllegalStateException when RainsCore is not enabled — which for a plugin that declares
     *                               {@code depend: [RainsCore]} cannot happen, and for one that
     *                               forgot to is the clearest possible way to find out
     */
    static RainsCore get() {
        RainsCore running = RainsCorePlugin.instance();
        if (running == null) {
            throw new IllegalStateException(
                    "RainsCore is not enabled. A plugin using it must declare it in its "
                            + "paper-plugin.yml: depend: [RainsCore]");
        }
        return running;
    }

    /** Whether it is there — for a plugin that treats RainsCore as optional. */
    static boolean isAvailable() {
        return RainsCorePlugin.instance() != null;
    }

    /**
     * A chat helper signed with this plugin's own tag.
     *
     * <p>Made fresh each call, and meant to be held as a field. The tag is the only thing that
     * differs between two plugins' chat; the palette behind it is the server's.
     */
    Chat chatFor(String tag);

    /** The same, when a plugin wants to point the tag at one of its own settings. */
    Chat chatFor(Brand brand);

    /** The action bar. One for the server, because a player has one action bar. */
    ActionBars actionBars();

    /**
     * The sidebar. One for the server, because a player has one sidebar and two plugins wanting it
     * is a collision somebody has to arbitrate.
     */
    Scoreboards scoreboards();

    /**
     * Boss bars. Unlike the action bar and the sidebar these stack, so this caps how many a player
     * is shown at once and ranks what fills the cap — and it owns shared bars, where an audience
     * watches one bar and people join and leave while it runs.
     */
    BossBars bossBars();

    /**
     * Every place any plugin has asked to remember: homes, stops on a ghast line, where somebody
     * died. One store, so a ghast line can fly a player to their own home without either plugin
     * knowing about the other.
     */
    PoiStore places();

    /**
     * Who a player is as everybody else sees them: their chat prefix and suffix, the prefix above
     * their head, and the colour of their name.
     */
    Identities identities();

    /**
     * Bans, mutes, freezes and the record of who did what — for any plugin that needs to refuse
     * somebody something and remember that it did.
     */
    Punishments punishments();

    /**
     * What actually stops a punished player, and what they are told.
     *
     * <p>Here rather than in whichever plugin owns the moderation commands, because any plugin can
     * call {@link Punishments#punish} — the claims module freezing somebody's hands should not have
     * to write its own listeners for that to mean anything.
     */
    PunishmentGuard punishmentGuard();

    /**
     * Keeps our bans and the server's own {@code banned-players.json} saying the same thing, in
     * both directions.
     */
    VanillaBanBridge banBridge();

    /**
     * Every custom item any plugin has defined, so one plugin's item can be given, recognised or
     * listed by another — and by a command and a menu.
     */
    CustomItems items();

    /** Turns a definition into an actual stack, and recognises one again by the key inside it. */
    ItemFactory itemFactory();

    /**
     * What an item <em>does</em>: the trigger it answers to, its cooldown, and how many uses it has
     * left. A plugin registers the effect; this decides whether it may run and says why not.
     */
    ItemAbilities itemAbilities();

    /**
     * Custom achievements: what a player has done and what they are working towards. Not vanilla
     * advancements, which cannot express "claim your first plot" at all.
     */
    Achievements achievements();

    /**
     * Every plugin's settings as one tree, with the rules about what a screen shows and what a
     * click does — what {@code /settings} and the settings menu both walk.
     */
    SettingsNavigation settingsNavigation();

    /**
     * The tablist: who is on, grouped by which world they are in, with the prefixes and suffixes
     * from {@link #identities()} — so a rank set once shows in chat, above the head and here.
     */
    Tablists tablists();

    /**
     * Asking a player to type something.
     *
     * <p>Use this rather than registering a chat listener: the next line a player types is a thing
     * only one plugin can have, and three of them each claiming it is three plugins fighting over
     * one answer.
     */
    ChatPrompts prompts();

    /**
     * Named places anybody can be sent to. Stored as {@link #places()} entries, so a ghast line can
     * fly somebody to a warp and a menu can list warps beside homes.
     */
    Warps warps();

    /**
     * Farm worlds: a set of three linked worlds — overworld, its own nether, its own end — that can
     * be regenerated on a schedule without touching the main ones.
     */
    FarmWorlds farmWorlds();

    /**
     * The one resource pack every plugin's assets go into.
     *
     * <p>A player has one resource pack, so a plugin must never send its own: whichever sends last
     * wins and everybody else's textures are simply not there, with nothing logged anywhere. Offer
     * a {@link de.raindancer.core.content.pack.PackContribution} instead and this decides what is sent,
     * builds it reproducibly, serves it, and reports the files two plugins both wanted.
     */
    ResourcePacks resourcePacks();

    /**
     * Whether it is safe to put a player somewhere, and where to put them instead.
     *
     * <p>Use it before every teleport. A warp set on a platform that has since been mined and a home
     * in a house somebody flooded both end with a player suffocating in stone, and the plugin that
     * put them there has no idea unless it asks.
     */
    Safety safety();

    /**
     * Keeping chunks loaded — for a moment, or until somebody lets go.
     *
     * <p>A permanent hold carries the name of whoever asked, because the flag is written into the
     * world and survives a restart: a plugin that forgets leaves a server ticking chunks nobody can
     * account for.
     */
    ChunkHolds chunks();

    /**
     * Every sound and every particle any plugin makes.
     *
     * <p>Ask by meaning — {@code play(player, Cues.NO)} — rather than by sound. That is what lets a
     * server owner change how every menu in every plugin sounds from one place, and what stops nine
     * plugins each choosing their own click.
     */
    Effects effects();

    /**
     * Asking everybody — or a named few — a question, and counting the answers.
     *
     * <p>One ballot per person, changeable until the deadline, and a tie stays a tie. Also what a
     * town council approving a claim is: a vote with an electorate and a time limit.
     */
    Votes votes();

    /**
     * Being properly not here.
     *
     * <p>Ask {@code visibleOf} instead of {@code getOnlinePlayers()} and {@code isVanished} before
     * naming anybody: vanish is a promise every subsystem has to keep, and nearly every place it
     * leaks is a place that skipped one of those two calls.
     */
    Vanish vanish();

    /**
     * Doing things to a player from a management screen — heal, feed, starve, effects, flight,
     * gamemode, kick.
     *
     * <p>Every action answers what happened rather than throwing, because the edges are where these
     * go wrong: healing past the maximum, damaging past what somebody has, or acting on a player who
     * logged out a moment ago.
     */
    PlayerAdmin players();

    /**
     * Who is looking inside whose inventory, and what they may touch there.
     *
     * <p>One editor at a time, whatever else is watching: two people dragging in the same inventory
     * duplicates items every time.
     */
    InventoryViews inventoryViews();

    /**
     * Looking inside somebody's inventory, whether or not they are on the server.
     *
     * <p>The offline half is the one that matters: {@code OfflinePlayer} has no
     * {@code getInventory()}, and the players a moderator most needs to look at are exactly the ones
     * who have logged out. Their items are read out of the file the server saved them in, and an
     * edit holds them out of the server for as long as it lasts — because the server rewrites that
     * file on the next join and would discard the change without a word.
     */
    Inventories inventories();

    /**
     * The record of what was done on this server, and who did it.
     *
     * <p>Two questions, both unanswerable from an ordinary logfile: what has this moderator been
     * doing, and what has been done to this player. Recording something never touches the disk on
     * the calling thread — entries are queued and written on a timer — so a plugin may record from a
     * click handler without thinking about it. Searching does touch the disk, and belongs off the
     * server's threads.
     */
    /**
     * Every word this server says to a player, in a file somebody can edit.
     *
     * <p>Four layers, lowest first: a default a plugin supplied in code, the wording built into the
     * jar, the owner's {@code messages.yml}, and — above even that — anything a plugin insists on.
     * The ordering is the design: the owner's file beats both defaults, because somebody who edits a
     * line has to get that line or the file is decoration; and a plugin can still override outright
     * for the few texts that must not be freely editable.
     *
     * <p>A plugin adding its own messages calls {@code define}. A plugin replacing one of Core's
     * calls {@code force}, and should expect to explain why.
     */
    Messages messages();

    Audit audit();

    /**
     * The databases this server keeps.
     *
     * <p>For a plugin that wants its own tables rather than its own files. SQLite, with no dependency
     * to declare: Paper ships the driver. Every read and write must happen off the server's threads —
     * {@link Databases} says so loudly in the log if it does not.
     */
    Databases databases();

    /**
     * Weighted loot tables, by tier — what comes out of a chest and how often. An entry may be a
     * plain material or one of {@link #items()}, so a supply drop can contain a real custom item.
     */
    LootTables lootTables();

    /** Puts a rolled table into an actual container. */
    LootFiller lootFiller();

    /** Clickable chat buttons, already pointed at the command that runs their callbacks. */
    ChatButtons buttons();

    /** The registry behind those buttons, for a plugin that wants to revoke one itself. */
    ClickActions clickActions();

    /**
     * Binds a plugin's settings record to {@code config.yml} in its own data folder, loads it, and
     * writes back anything a new version added.
     *
     * <p>The returned store is the plugin's; RainsCore keeps a reference only so that the combined
     * settings GUI can find it.
     */
    <T> SettingsStore<T> settingsFor(Plugin plugin, Class<T> type, T defaults);

    /** The same, for a plugin that wants the file somewhere other than its data folder. */
    <T> SettingsStore<T> settingsFor(SettingsSchema<T> schema, Path file);
}
