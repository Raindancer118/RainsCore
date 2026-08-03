# The big refactor — plan and running status

Source of truth for this refactor. **Updated as work happens, not at the end.**
Honesty rule: a box is only ticked when the thing *really works and was verified* — built, tested,
and checked against real behaviour. Partial work stays unticked with a note saying what is missing.

Driven by `What I want done.md`. Decisions taken with the user on 2026-08-03 are in
[Decisions](#decisions) and are not to be re-litigated.

---

## Decisions

| # | Question | Decision |
|---|---|---|
| 1 | How do the shared helpers reach the plugins? | **`RainsCore`, a real Paper library plugin.** Other plugins `depend: [RainsCore]` and compile against it. One runtime instance, so scoreboard, bossbars, POIs, the logfile, punishments and custom items are genuinely shared instead of duplicated per jar. |
| 2 | ~~Standalone repos vs. the copies inside `RainsSMPCore`~~ | ~~SMPCore consumes the real sources. One multi-module build.~~ **REVERSED 2026-08-03** — see decision 8. |
| 3 | Blast radius | **Free to restructure, including config and on-disk formats** — with automatic one-way migrations for existing servers. Commands, permissions and existing data must keep working for a server that upgrades. |
| 4 | How is code written here? | **Test first, and the tests are extensive.** Added 2026-08-03. The test class is written and run red before the implementation exists. Extensive means edge cases, null/blank, concurrency (Folia: any region thread), failure paths, bounds, idempotency, persistence round-trips — not one happy path per method. Every defect found gets a regression test that fails on the old code *before* it is fixed. |
| 5 | Who checks the work? | **`agy`** (the local Gemini CLI) reviews each milestone as a second opinion. Its findings are judged, not obeyed; what I accept gets a regression test first. |
| 6 | Write it or take it? | **Look for an existing implementation first.** Added 2026-08-03: *"has this ever been done? ohhh yeah this guy on github there did that already."* For whole features, prefer taking working code over writing it. Two conditions: **it must actually support 26.2** — check the release notes, do not assume — and it is **copied in, not depended on** (see below). Adjust it to our conventions afterwards, and cover the seam with our own tests. |
| 8 | Is anything folded together? | **No. `RainsCore` is a standalone foundation and nothing is merged into anything.** Replaces decision 2, at the user's instruction: *"don't merge all of it. Build new, so that even new plugins can build on it."* Each plugin keeps its own repo and ships its own jar, and depends on `RainsCore` at runtime. That is what lets **`TheHungerGames` — a separate project in a separate repo, on Gradle — build on it too**, and any plugin written after it. A foundation that only its own family can use is not a foundation. |
| 7 | External dependencies | **None.** Added 2026-08-03. Copied code is vendored under `core.internal`, recorded in `THIRD-PARTY.md` with its licence and version, and kept unmodified apart from the package name so upgrading stays unpack-and-rename. `NoExternalDependenciesTest` enforces all of it. |

### Asked for while the work was running

Recorded here rather than absorbed quietly. All three are 2026-08-03.

- **A custom tablist**, showing **which world each player is in**. Header, footer, per-player entry
  formatting, and players grouped or labelled by world. Uses `core.identity` for the prefix/suffix
  already there, so a rank shows up in chat, above the head and in the tablist from one declaration.
- **A warp API**, built into the core — **and it must work with the code already there.** Warps are
  `core.poi` entries of kind `warp`: the store, the persistence, the world-missing handling and the
  "reachable" question are all solved, so this is permissions, categories, cooldowns, a command and a
  menu on top of it — not a second store.
- **A farm world**, *with its own nether and end.* A managed set of three linked worlds that can be
  regenerated on a schedule without touching the main ones, with the portal linking between them
  kept inside the set.

### Added after the plan was written

**Spec point 11 — a settings API, and player identity** (asked for 2026-08-03, after work started):
everything that sensibly can be should be configurable, exposed as an API other plugins use — chat
colours, symbols, prefixes — plus **per-player prefixes and suffixes in chat, and a prefix on their
nametag.** This becomes two subsystems, `core.settings` and `core.identity`, both listed in Phase 1
below. It is a real scope increase over `What I want done.md`, recorded here rather than absorbed
quietly.

### ~~Derived decision: one build system — Maven~~ — dropped 2026-08-03

This followed from decision 2: one reactor needs one build system. With nothing being folded
together there is no reactor, so **each project keeps whatever build it already has**. Two are on
Gradle and that is now fine — `TheHungerGames` is on Gradle as well, and it has to be able to depend
on `RainsCore` regardless.

What that does require is that **`RainsCore` publishes properly**: `mvn install` puts it in the local
Maven repository, and a Gradle project consumes it from `mavenLocal()`. Verified by a test that
resolves it exactly as an outside project would — see `Phase 1`.

### What this means for `RainsSMPCore`

It currently vendors copies of eight plugins, kept honest by `tools/vendor-drift.sh`. Under decision
8 that machinery is **no longer the destination of this refactor** and is left alone: SMPCore keeps
working exactly as it does today. Whether those copies are eventually replaced by real dependencies
is a separate question for another day, deliberately not answered here.

---

## The target architecture

```
RainsCore                       (new repo)  →  RainsCore.jar, a library plugin
  de.raindancer.core.gui         THE menu framework. Every screen in every plugin.
  de.raindancer.core.chat        Chat/messaging helper: prefixes, routing, action bar vs chat
  de.raindancer.core.log         One logger, with real logfiles on disk
  de.raindancer.core.scoreboard  Scoreboard manager, exposed as a Java API
  de.raindancer.core.bossbar     Bossbar handler
  de.raindancer.core.poi         The single Point-Of-Interest store
  de.raindancer.core.moderation  Punishment & moderation, usable by any plugin
  de.raindancer.core.items       Custom-item API + registry, configured by command and GUI
  de.raindancer.core.module      PluginModule / ModuleHost (moved out of smpcore)
  de.raindancer.core.util        Scheduling, FontWidth, Duration, Text

Each plugin repo                 →  <X>Plugin extends JavaPlugin  (standalone jar)
                                    <X>Module — the real thing, takes a Plugin host
RainsSMPCore                     →  depends on all of them, shades them into one jar
```

The `<X>Module` split is what makes decision 2 work: SMPCore stops holding *copies* and instead
constructs the same module class the standalone jar does, through a `PluginModule` host.

---

## The settings model

Chosen 2026-08-03. SMPCore's catalogue is **not** the basis — it is replaced. What was wrong with it,
so the replacement does not drift back:

- call sites were stringly-typed (`settings.bool("gameplay.remove-phantoms")`) — a typo is a runtime
  failure, there is no find-usages, and values come back as `Object` and get cast;
- one 835-line file held every setting on the server, and also parsed, wrote, validated and migrated;
- `SettingGroup` was a closed enum that had grown `HOMES`, `TPA` and `GHASTS` entries belonging to
  other plugins, each with a comment apologising for it;
- the shipped `config.yml` was a second copy of the catalogue, kept honest by a build-failing test.

A plugin now declares its settings as **one record whose components are the settings**:

```java
@Settings(id = "claims", topics = {
    @Topic(path = "management",        title = "Management",  icon = Material.IRON_AXE),
    @Topic(path = "management/fences", title = "Fences",      icon = Material.OAK_FENCE),
})
record ClaimConfig(
    @In("management/fences") @Title("Show fences")
    @Describe("Draws a fence along the claim border.")
    boolean fencesEnabled,

    @In("management/fences") @Title("Fence height") @Range(min = 1, max = 16)
    int fencesHeight,

    @In("management/fences") @Title("Fence tint")
    NamedTextColor fencesTint
) {
    static final ClaimConfig DEFAULTS = new ClaimConfig(true, 3, NamedTextColor.AQUA);
}
```

```java
ClaimConfig config = settings.of(ClaimConfig.class);   // immutable snapshot
if (config.fencesEnabled()) { ... }
settings.onReload(ClaimConfig.class, this::rebuildFences);
```

The record is the single source: `config.yml`, its comments, validation, tab completion and the GUI
are all derived from it, so there is nothing to keep in sync and no second copy to fail a test over.
Defaults come from the `DEFAULTS` instance, which means they are written in real Java and checked by
the compiler rather than being untyped literals in a list.

**Topics are an open tree, and plugins bring their own categories.** I first built this with three
fixed roots and a refusal for anything else. That was wrong and the user said so: *"not these
submenus. I want more submenus, and plugins should be able to bring their categories."* A plugin
knows what its own settings are about, and making the ghast lines file "cruise speed" under someone
else's category makes the menu harder to read, not easier.

So: any category, any depth. `@In` names the path; ancestors are made on the way, so a plugin
declares only the leaf it cares about. `SettingsRegistry` merges every plugin's categories into one
tree, which is what lets two plugins share a branch without either declaring the whole trail.

What survives of the fixed-root idea is **furniture, not a rule** — a handful of names RainsCore has
an opinion about: `player`, `management`, `config`, `appearance`, `moderation`, `modules`. Use one
and the button comes with a good title, icon and description, and two plugins using it land in the
same place. Use anything else and the title is read from the path (`ghast-lines` → "Ghast lines").

The clutter is solved by **depth**, not by a fixed vocabulary: a topic with subtopics renders as a
menu, a topic with settings as a page, empty topics are hidden, and no page holds more than a
handful of related things.

---

## Status

Legend: `[ ]` not started · `[~]` in progress · `[x]` done **and verified** · `[!]` blocked

### Phase 0 — clean base
- [x] Survey the workspace, read every build file, find every duplicated framework
- [x] Decide delivery model, repo layout, build system (above)
- [x] Write this plan down
- [x] Get `RainsSMPCore` green and committed as a checkpoint — `94f73e7`, **749/749 tests pass**
  - working tree was mid-reconciliation: 9 vendored files reconciled, baseline not updated,
    and `RrpMenu` had swapped `Brand.wrap` for an installed titler seam
  - `WindowTitlesTest` now blesses the `titler.apply(` seam; vendor baseline rewritten (71 entries)

### The live-server check

`RainsCoreTestPlugin` runs **77 checks against a real Paper 26.2 server** and prints one line the
run is judged by. It exists because everything else here is tested *below* the server line — that is
what the seams are for — and that cannot prove the jar loads, the descriptor is right, or that Bukkit
accepts what we build. Two things it found that no unit test could have:

1. **Both commands were dead.** Paper fires the `COMMANDS` lifecycle event during *bootstrap*, so a
   handler registered in `onEnable` never runs — silently. Every chat button in the library pointed
   at a command that did not exist. Now registered from `RainsCoreBootstrap`.
2. **The dependency instructions were wrong.** `examples/README.md` said `depend: [RainsCore]`, which
   is the legacy `plugin.yml` form and is ignored in a `paper-plugin.yml`. A plugin following them
   died on `NoClassDefFoundError` naming a class its author never wrote.

### Phase 1 — RainsCore exists and builds
- [~] Repo skeleton, POM — compiles. `paper-plugin.yml` and the main class not written yet.
- [ ] Root aggregator POM; `RainsAwesomePluginManager` and `RainsResourcepackManager` on Maven
- [x] `core.log` — one logger, rotating logfiles. **26/26 tests pass.**
  `Log` · `LogChannel` · `LogFile` · `LogLevel`. Async single-writer thread, daily rotation with a
  32 MiB part cap, retention pruning by filename, `{}` placeholders, never blocks, never throws.
  `agy` reviewed it and found five real defects; all five are fixed and each has a regression test:
  1. poison pill dropped on a full queue → shutdown hung for the full grace period. Pill removed
     entirely; the drain polls and checks a flag.
  2. the drain kept its interrupt flag set while writing the backlog, so every remaining line died
     of `ClosedByInterruptException` — the last lines before a shutdown, silently lost.
  3. a restart reset the part number to 1, scattering a startup sequence across the tails of every
     full part of the day. Now resumes in the highest part that has room.
  4. `Log.configure` closed the old file synchronously — a `/reload` could freeze the main thread
     for up to six seconds. Now `closeInBackground()`.
  5. `openPart` was read across threads without publication; `currentFile()` could name a file that
     does not exist. Replaced by one volatile `Path`.
- [x] **chat buttons** — `ChatButton` · `ChatButtons` · `ClickActions` · `ClickResult`.
      **43 tests, all green.** Asked for 2026-08-03. Callback buttons bound to one player, one-shot
      by default, expiring, revocable, bounded registry, `ask()` for a two-answer question.
      *Reuse decision:* Adventure's own `ClickEvent.callback` was considered and rejected — it does
      not bind to a player, cannot be revoked, and a spent callback silently does nothing where the
      player needs to hear which of "not yours" / "already answered" / "expired" it was. All three
      are required by the town-council approval (spec point 3), which this now unblocks.
      Still to wire: the `/rcore click <token>` command and the sweep timer.
- [~] `core.chat` — from `smpcore.util.Feedback`/`Notifier`, claims' `Messages`, rrp's `Msg`,
      and the three near-identical `Chrome` classes in homes/tpa/ghastlines.
      `Style` · `Preset` · `Brand` · `Feedback` · `Chat` are **written but not yet tested**, so they
      do not count as done. Writing their tests found the first design fault already: `Chat.broadcast`
      and `Chat.console` call `Bukkit` statics directly and therefore cannot be tested at all — an
      `Audiences` seam has to go in before they can be.
- [~] `core.settings` — **an annotated config record**, not SMPCore's catalogue. See
      [The settings model](#the-settings-model). Replaces `smpcore.config` outright.
  - [x] **the schema layer** — `Settings` `Topic` `In` `Title` `Describe` `Icon` `Range` `Key`
        annotations, `Setting<T>`, `SettingsTopic`, `SettingsTopics`, `SettingsSchema`.
        **31 tests, all green** (57 in the project). Written before the implementation.
        Verified non-vacuous: two deliberate mutations of `SettingsTopics` were each caught.
  - [ ] the store: YAML binding, generated `config.yml` with comments, `set`/`cycle`/`reset`,
        validation on read, `of(Class)` snapshots and `onReload`
  - [ ] the registry that aggregates every plugin's schema into one GUI
  - [ ] the command front end and tab completion
- [x] `core.actionbar` — **31 tests, all green.** `ActionBars` · `ActionBarPriority` · `ActionBarSink`.
      Arbitration by owner/priority/lifetime, fallback when a winner expires, self-redrawing
      countdowns, once-a-second refresh against the client fade, and an orphaned-slot race found by
      its own regression test (reproduces within ~5 rounds unguarded).
      Still to wire: the Bukkit `ActionBarSink` and the repeating task that calls `tick()`.

  **The design, in full:** the action bar is a shared slot, like the bossbar and the scoreboard.
      Two plugins writing to it fight, and the one that ticks fastest wins: today a flight's
      commentary and a claim-entry message overwrite each other with no arbitration at all. So the
      helper is not a `sendActionBar` wrapper — it is an owner: messages have a priority and a
      duration, the highest-priority live message is what shows, and it is re-sent on a tick because
      the client fades it after ~3 seconds. Asked for 2026-08-03.

  **What belongs on it — corrected 2026-08-03.** I had written that `Chat.tell` would route through
  the action bar. That is wrong, and the user said so: *"not everything should go through the action
  bar. Just notifications, for example a tpa countdown. Anything that's just important for the
  second it's shown."*

  The test is **does this still matter a second later?**

  | Belongs on the action bar | Belongs in chat |
  |---|---|
  | A TPA countdown, a cast bar, any progress | "Home set", "Request sent to Bentex_OG" |
  | Flight commentary while a ghast is in the air | A refusal the player may want to read twice |
  | "You have entered Raindancer118's claim" | Lists, headings, manuals, anything with rows |
  | A warning tied to where the player is standing right now | Anything naming another player who might reply |

  So the destination is **chosen at the call site**, not by a setting. This also kills the inherited
  `messages.personal-in-action-bar` flag from `smpcore.util.Feedback`: one boolean deciding for every
  personal message in the jar is precisely the blunt instrument being corrected. `Chat.tell` goes to
  chat; a separate call puts something on the bar; and a countdown gets its own API because it
  redraws itself.
- [ ] `core.identity` — per-player chat prefix and suffix, nametag prefix, chat colour; the symbol
      set. The gradient maths in `RainsColouredNames` (`Naming.colourAt`, `NameStyle`, `Palette`)
      is worth harvesting, but it styles **item and mob names** — there is nothing for player
      nametags anywhere in the workspace: no `TextDisplay`, no scoreboard teams, no font handling.
      Green-field.

  **What the reference screenshot actually needs** (given 2026-08-03). The user likes a tag reading
  `[emblem] [PLAYER] Bentex_OG` with a second line `0 blocks` beneath it. Split by what is even
  possible server-side:

  | Part | Where it comes from |
  |---|---|
  | The circular emblem | **Lunar Client**, a client-side mod. Not ours to draw — confirmed by the user. |
  | The filled pill behind `PLAYER` | Needs checking: probably Lunar's nametag background too. If we want it for non-Lunar players it is a resourcepack font (a background sprite plus negative-space glyphs), which `RainsResourcepackManager` can already serve. **Ask before building.** |
  | `PLAYER` as a rank prefix, and the name's colour | Ours. Plain server-side text. |
  | The second line `0 blocks` | Ours, but a vanilla nametag is one line. Needs a `TextDisplay` entity mounted on the player, with the vanilla tag hidden. |
- [x] `core.gui` — from `extendedclaims.gui.Menu`, with its arithmetic pulled into `MenuLayout`
      so it is testable; `MenuGrammarTest` holds the rules a source scan can hold
- [ ] `core.poi` — one store replacing `Home`, `Destination`, `Waypoint` and their three stores
- [x] `core.scoreboard` — **23 tests.** FastBoard 2.2.0 copied in (MIT, states 26.2 support), wrapped
      by `Scoreboards` which adds owner/priority/fallback arbitration and degrades to nothing —
      loudly once, then quietly — on a server whose internals it cannot reflect into.
- [x] `core.banner` — **17 tests.** A per-plugin console splash: name, generated block-letter logo,
      tagline, version, author, facts, warnings, timing. Asked for 2026-08-03.
- [ ] `core.bossbar` — generalised from `ghastlines.FlightService`, the only bossbar user today
- [x] `core.moderation` — punishments any plugin can hand out and ask about
- [x] `core.items` — registry, abilities, cooldowns, charges, recipes; built against
      `TheHungerGames`' thirteen items and verified it can express each shape
- [x] `core.achievement` — custom achievements, earned once, with progress towards a goal
- [x] `core.loot` — weighted tables by tier, entries that may be real custom items
- [x] `core.tablist` — grouped by world, configurable header/footer
- [x] `core.warp` — built on `core.poi`, as asked
- [x] `core.world` — farm worlds with their own nether and end, and the portal linking

### Phase 2 — plugins migrate onto RainsCore
One entry per plugin; each is done when it builds, its tests pass, and its own duplicate of the
shared code is **deleted**, not merely bypassed.
- [ ] RainsExtendedClaims · RainsHomes · RainsTPA · RainsGhastLines · RainsRecords
- [ ] RainsColouredNames · RainsNoFlyingTrees · RainsResourcepackManager · RainsAwesomePluginManager

### Phase 3 — SMPCore consumes real sources
- [ ] `<X>Module` / `<X>Plugin` split in each plugin
- [ ] SMPCore depends on the plugin artifacts and shades them
- [ ] Delete the vendored trees, `tools/vendor-drift.sh`, `tools/vendor-baseline.txt`, `VendorDriftTest`

### Phase 4 — the GUI revamp (spec points 1, 4)
- [ ] Menus sorted by topic: player options, management, config — a consistent grammar
- [ ] Every screen in every plugin moved onto the shared framework and re-laid-out

### Phase 5 — town council approval (spec point 3)
- [ ] A new claim inside a town asks every online council member to accept or deny,
      reusing the accept-message flow the claim fees already use

### Phase 6 — finish
- [ ] Automatic start-up tests for every jar
- [ ] Config migrations verified against a real pre-refactor server directory
- [ ] Docs (`Project.md`, `README.md`, `CHANGELOG.md`) updated per repo
- [ ] Commits and pushes per repo

---

## Working agreements

- **Second opinion.** `agy` (local Gemini CLI) reviews each milestone. It is a check on my work,
  not an oracle — I judge what it reports and say so.
- **No proof-stubs.** No empty skeletons, no `NotImplementedError`, no "comes later" comments
  presented as finished work.
- **Report honestly.** What runs, what does not, what was skipped, with the output to back it.
- Commit identity is the user's; Claude is never mentioned in a commit.
