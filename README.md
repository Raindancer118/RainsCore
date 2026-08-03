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

## What is in it

Everything below is reached through `RainsCore.get()`.

### Things a player only has one of

| | What it adds beyond the Bukkit API |
|---|---|
| **`actionBars()`** | Owner, priority and lifetime per message, so plugins stop overwriting each other. What a refusal interrupts comes back when it expires. `countdown()` redraws itself — no repeating task to cancel. |
| **`scoreboards()`** | The sidebar, arbitrated the same way. Packet-level underneath, so it does not flicker and does not fight other plugins over teams. Degrades to nothing if the server's internals are newer than the copied-in code. |
| **`bossBars()`** | These *stack*, so it is a cap and a ranking rather than a winner. Also **shared bars**: one bar, an audience that changes — a flight's passengers get on and off, and leaving takes the bar with them. |
| **`tablists()`** | Grouped by world, so the list says who is where. Configurable header and footer. |

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
mvn test        # 606 tests, no server needed
mvn install     # to the local Maven repository
```

**Everything that can be tested without a server, is.** That is what the seams are for: `ActionBars`
takes a sink and a clock, `Chat` takes an `Audiences`, `Warps` takes "is this world loaded". The
arbitration, the arithmetic, the persistence and the failure paths are all tested below the server
line.

**What cannot be, is tested on a real one.** `../RainsCoreTestPlugin` runs **77 checks against a live
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
- **Every store writes through a temporary file**, so a server killed mid-write has the old file or
  the new one, never half of each.
- **Comments say why, not what.** Most of them exist because something went wrong once.
