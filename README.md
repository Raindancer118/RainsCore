# Rain's Core

The one implementation of everything Rain's plugins do to the game.

A Paper **library plugin**: other plugins depend on it and call into it. It is not a framework and it
does not want to own your plugin's structure — it owns the things a server can only have one of.

```java
RainsCore core = RainsCore.get();

Chat chat = core.chatFor("Claims");
chat.ok(player, "Claimed <n> blocks.", Chat.arg("n", 256));

core.actionBars().countdown(player.getUniqueId(), "tpa", Duration.ofSeconds(5),
        ActionBarPriority.NORMAL,
        left -> Component.text("Teleporting in " + Math.ceilDiv(left, 1000)));
```

---

## Why it exists

Nine plugins had grown their own copy of the same things. Five separate menu frameworks, three
records with the same five fields and the same javadoc explaining them, four ideas of what a chat
prefix was, and nine calls to `getLogger()` with nothing written down anywhere. Fixing a bug in one
copy left it live in the other four.

Worse than the duplication was the fighting. A player has **one** action bar, **one** sidebar, **one**
tablist slot. When the ghast lines write flight progress every tick and the claims write "you have
entered Raindancer118's claim" on a move, neither knowing the other exists, what a player sees is
whoever wrote last — a flicker several times a second. No amount of tidying inside either plugin
fixes that; somebody has to arbitrate.

So the rule here is: **if a player can only have one of it, this owns it.** Plugins ask.

---

## Getting it

```bash
cd RainsCore && mvn install
```

Then depend on it — `provided` in Maven, `compileOnly` in Gradle. Never shaded: see
[`examples/README.md`](examples/README.md), which also has the `paper-plugin.yml` block you need and
a complete example plugin that this project compiles on every build.

> **The one thing everybody gets wrong:** `depend: [RainsCore]` is the old `plugin.yml` syntax and is
> **silently ignored** in a `paper-plugin.yml`. Use the `dependencies:` block with
> `join-classpath: true`. Getting this wrong looks like `NoClassDefFoundError` naming a class you
> never wrote.

---

## It registers no commands

None. Not `/warp`, not `/settings`, nothing — taking a name on somebody's server is not a library's
decision, and a server that already has a warp plugin should not have to fight this one.

What it gives you is the parts to write them with, including ready-made handlers you register
yourself:

```java
public final class MyBootstrap implements PluginBootstrap {
    public void bootstrap(BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            CoreCommands.clickCallback(event.registrar());          // chat buttons need this
            CoreCommands.settings(event.registrar(), "settings");
            CoreCommands.warps(event.registrar(), "warp", "w");
        });
    }
}
```

```java
// in onEnable, so buttons know where their callbacks live
core.buttons().callbackCommand(getName().toLowerCase(Locale.ROOT) + ":rcclick");
```

**Buttons are the one thing that needs a command.** A chat click can only open a URL, fill the chat
box, or run a command — so a server-side callback *is* a command, by necessity. Until something
registers one, `buttons()` still draws readable labels but nothing is clickable, and Core says so
once with the exact fix.

> **Register in a bootstrapper, never in `onEnable`.** Paper fires `COMMANDS` during bootstrap, so a
> handler registered in `onEnable` never runs — silently, no exception, no log line. Core itself got
> this wrong, and every chat button in the library was dead on a real server until one was booted.

---

## What is in it

Everything below is reached through `RainsCore.get()`.

### Things a player only has one of

| | What it adds beyond the Bukkit API |
|---|---|
| **`actionBars()`** | Owner, priority and lifetime per message, so plugins stop overwriting each other. What a refusal interrupts comes back when it expires. `countdown()` redraws itself — no repeating task to cancel. |
| **`scoreboards()`** | The sidebar, arbitrated the same way. Packet-level underneath, so it does not flicker and does not fight other plugins over teams. Degrades to nothing if the server's internals are newer than the copied-in code. |
| **`bossBars()`** | These *stack*, so it is a cap and a ranking rather than a winner. Also **shared bars**: one bar, an audience that changes — a flight's passengers get on and off, and leaving takes the bar with them. |
| **`tablists()`** | Grouped by world, so the list says who is where. **Sorted by rank** — your staff at the top, not wherever the alphabet put them — via scoreboard-team keys, the same lever Velocitab uses. A title and a logo of your own, animated header and footer, and the ping written as a number beside each name, because 30ms and 130ms look identical in the five bars. |
| **`resourcePacks()`** | Plugins contribute assets; Core builds **one** pack, serves it, and sends it. Reproducible zips (so clients cache rather than redownload), conflicts reported by name, and a built-in HTTP server you can turn off if you have your own. |

### Saying things

| | |
|---|---|
| **`chatFor(tag)`** | One plugin's voice. `tell` / `ok` / `warn` / `no` / `row`. Player-supplied text goes through `Chat.arg()` and is never parsed as markup. |
| **`buttons()`** | Clickable chat buttons with **server-side callbacks** — bound to one player, one-shot, revocable. `ask()` is a two-answer question where answering removes the other button. |
| **`identities()`** | A player's chat prefix, suffix, colour, and the prefix above their head. Set once; it shows in chat, on the nametag and in the tablist. |

### Remembering things

| | |
|---|---|
| **`places()`** | Every saved place — homes, stops, warps, death points — in one store. Which is why a ghast line can fly somebody to their own home. |
| **`warps()`** | Named places, built on `places()`: permissions, categories, one cooldown per player. |
| **`punishments()`** | Bans, mutes, kicks, freezes. Nothing is ever deleted — lifting a ban records the lifting. |
| **`achievements()`** | Custom achievements, earned exactly once, with progress towards a goal. |
| **`items()` / `itemFactory()` / `itemAbilities()`** | Custom items with abilities, cooldowns, charges and recipes. Recognised by a key in the item's PDC, so an anvil cannot forge one. |
| **`lootTables()` / `lootFiller()`** | Weighted tables by tier. An entry may be a real custom item, so a supply drop can contain one that actually works. |

### Running things

| | |
|---|---|
| **`settingsFor(...)`** | Your settings as an annotated record — see below. |
| **`farmWorlds()`** | A farm world is three linked worlds with its own nether and end, regenerated on a schedule. Its portals stay inside it, which is the entire point. |
| **`de.raindancer.core.gui`** | The one menu framework. Six rows, three bands, chrome the framework owns. |
| **`de.raindancer.core.log`** | One logger, one logfile per day, rotated and pruned. Never blocks, never throws. |
| **`de.raindancer.core.banner`** | The startup splash, with a logo drawn from your plugin's name. |
| **`de.raindancer.core.messages`** | `Messages` — a `messages.yml` an owner can edit, over the defaults your plugin ships. A key their file is missing falls back rather than blanking; player text is escaped; broken markup still renders. |
| **`de.raindancer.core.store`** | `YamlStore` — a YAML file read and written without ever losing it. Use it for anything your plugin keeps. |
| **`safety()`** | Is it safe to put a player there, and where instead. Two blocks of room, solid ground, no lava/fire/portal, a survivable drop — optionally checking the blocks around it too. Never loads a chunk to answer; `chunks()` does that first. |
| **`chunks()`** | Keeping chunks loaded — for a moment, or until somebody lets go. Every permanent hold carries a name, because the flag survives a restart. |
| **`effects()`** | Every sound and particle any plugin makes, asked for **by meaning** (`Cues.NO`) rather than by sound. 23 cues shipped; rebind one and every plugin's changes. Repeats suppressed so a per-tick loop cannot deafen anybody. |
| **`vanish()`** | Being properly not here — hidden, uncounted, silent joins, no collisions. Ask `visibleOf()` instead of `getOnlinePlayers()`. |
| **`players()`** | Heal, feed, starve, damage, effects, flight, gamemode, kick. Every action answers what happened instead of throwing on the edges. |
| **`inventoryViews()`** | Invsee. One editor at a time (two duplicates items), armour protected by default, windows close when their owner leaves. |
| **`votes()`** | Ask a question, count the answers. One ballot each, changeable until the deadline, and a tie stays a tie. |
| **`de.raindancer.core.choose`** | Ready-made catalogues: every item sorted into the creative tabs **and into families within them** (Oak, Deepslate, Red, Diamond); every sound with an icon of the thing that makes it; every particle; every player who has ever visited, ranked by how long ago. |
| **`de.raindancer.core.time`** | `Times` — reads what people type. `2min`, `2m`, `1h30m`, `2 weeks`, `perm`. **`m` is minutes and `M` is months**, deliberately. |

---

## Resource packs

A player has one resource pack. So a plugin never sends its own — it offers what it has:

```java
core.resourcePacks().contribute(
        PackContribution.of("Claims", "icons", getDataFolder().toPath().resolve("icons.zip"))
                .describedAs("The icons the claim menu uses")
                .priority(10));
```

Core collects every contribution, serves them, and sends them. A zip or a plain folder both work.

**By default they go out stacked** — one pack per plugin, applied in order, all in one request. The
client has supported that since 1.20.3, and it is the better default: nothing has to guess how two
plugins' files combine, packs for different game versions coexist happily, and each pack is cached on
its own, so adding a plugin costs players *that plugin's* download rather than all of them again.

Flip `packs-combine` on to merge everything into a single zip instead — one download and one entry in
the client's list, with `lang/*.json` and `sounds.json` genuinely merged key by key. The cost is that
any change rebuilds the whole thing. In that mode two plugins wanting the same file is **reported by
name** rather than silently resolved.

Either way the build is byte-reproducible so clients cache instead of redownloading on every restart,
a contribution that isn't a usable pack is dropped with a reason rather than served, and
`isWearing(player)` answers whether a given player can actually see your glyphs.

Turn the built-in web server off in `config.yml` and set a public address if you already have a CDN.

---

## Settings

Declare a record; everything else is derived from it.

```java
@Settings(id = "claims", topics = {
    @Topic(path = "config/limits", title = "Limits", icon = Material.BARRIER),
})
public record ClaimConfig(
    @In("config/limits") @Title("Blocks per player") @Range(min = 0, max = 100_000)
    int blocksPerPlayer,

    @In("config/limits") @Title("Fence tint")
    NamedTextColor fenceTint
) {
    public static final ClaimConfig DEFAULTS = new ClaimConfig(40_000, NamedTextColor.AQUA);
}
```

```java
SettingsStore<ClaimConfig> settings =
        core.settingsFor(this, ClaimConfig.class, ClaimConfig.DEFAULTS);

if (settings.current().blocksPerPlayer() > 0) { ... }
settings.onChange(config -> rebuildFences());
```

From that one declaration you get a documented `config.yml`, validation, `/settings` with tab
completion that offers a setting's actual allowed values, and a page in the shared settings menu.
Nothing is written twice, so nothing can drift.

**Categories are yours.** Bring whatever you like, at whatever depth. Use a name RainsCore knows —
`player`, `management`, `config`, `appearance`, `moderation`, `modules` — and the button comes with a
title and icon for free, and your settings share a page with everybody else's. That merging is the
point: a player looking for a setting does not know which of nine jars owns it.

---

## Working on it

```bash
mvn test        # 1021 tests, no server needed
mvn install     # to the local Maven repository
```

**Everything that can be tested without a server, is.** That is what the seams are for: `ActionBars`
takes a sink and a clock, `Chat` takes an `Audiences`, `Warps` takes "is this world loaded". The
arbitration, the arithmetic, the persistence and the failure paths are all tested below the server
line.

**What cannot be, is tested on a real one.** `../RainsCoreTestPlugin` runs **101 checks against a live
Paper server** and prints one line the run is judged by. It exists because unit tests cannot prove
the jar loads, the descriptor is right, or that Bukkit accepts what you build — and it earned its
place immediately by finding that **every chat button in the library was dead**, because a `COMMANDS`
lifecycle handler registered in `onEnable` never fires.

**No external dependencies.** Copied-in code lives under `core.internal`, is recorded in
[`THIRD-PARTY.md`](THIRD-PARTY.md) with its licence and version, and is kept unmodified so upgrading
stays unpack-and-rename. `NoExternalDependenciesTest` enforces all of that.

---

## Conventions worth knowing before reading the code

- **A refusal says which one it was.** "That is not your button", "you already answered", and "that
  expired" are three different things a player needs to hear. A silent no gets clicked again.
- **Anything a player typed is never markup.** A home called `<red>` is nine characters.
- **A button somebody may not use is shown, greyed, with the reason** — not hidden, and not live and
  then refusing.
- **Every store writes through `store.YamlStore`**, so a server killed mid-write has the old file or
  the new one, never half of each. Written once and used by all seven stores in here — it exists
  because those seven each had their own copy of it, which is the exact thing this library is for.
- **Comments say why, not what.** Most of them exist because something went wrong once.
