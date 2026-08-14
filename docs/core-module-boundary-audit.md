# Core/Module boundary audit — 2026-08-14

Started from an outside model's read of `RainsCore.java`'s javadoc only. Verified against
actual implementations and sibling repos (`RainsHomes`, `RainsTPA`, `RainsExtendedClaims`,
`Rain's Flexible Modules`) using a second model (`agy` / Gemini) plus direct reading. This file
is the running record — update it as more is verified, don't let it go stale.

## Standing rule under test

> Core owns infrastructure and server-wide-unique state; modules own product features and their
> policy.

## Confirmed findings

1. **UI arbitration is real, not just a shared API.** `ActionBars`, `Scoreboards`, `Tablists` run
   an actual priority queue of claims (`Priority` enum: COMBAT/WARNING/INFO), preempting and
   restoring correctly. This is Core's strongest, most load-bearing piece — keep it as the
   template for what "Core owns infrastructure" should mean.

2. **Warps stays in Core; LootTables and FarmWorlds don't belong there.**
   - `Warps` is genuinely coupled to Core's `PoiStore` (`places()`), the same store homes and
     ghast-line stops use. Extracting it means either duplicating `PoiStore` access from a module
     or exporting that store's API anyway — not worth it.
   - `LootTables` (`content/loot/LootTables.java`) and `FarmWorlds` (`world/farm/FarmWorlds.java`)
     are freestanding — YAML parsing and `WorldCreator` calls, no real Core coupling. Candidates
     to pull into modules.

3. **Punishments: the state/policy split the critique assumed exists does not exist yet.**
   `Punishment.java` holds `reason`, `lifterReason`, moderator UUID — Core *is* the moderation
   module today, not a thin state cache. Building a real Moderation module with its own
   reasons/escalation/UI on top of a slimmer Core state store is new work, not a seam to harden.

4. **Teleportation infra split works — where it's been adopted.**
   - `rtp-module` (`Rain's Flexible Modules/rtp-module/.../RtpService.java`) correctly consumes
     Core's `Travel`, `Safety`, `Scatter`, `Cooldowns` and keeps only real policy locally (ring
     centre, whether a landing is safety-checked, wording, cooldown length). It even has a
     `ReuseTest` whose only job is to fail if the module ever forks `Travel`'s warm-up/cancel
     logic instead of reusing it.
   - `RainsTPA` duplicates its own `travel()`/`cancel()`/`isWarmingUp()`/`arrive()` instead of
     using Core's `Travel`/`Safety`. Root cause, not a design flaw: `RainsTPA` was written
     2026-08-02; Core's `Travel` seam landed 2026-08-04. `RainsTPA` simply predates the seam it
     should now be migrated onto.
   - **Follow-up task:** port `RainsTPA` onto `Travel`/`Safety` the way `rtp-module` already does,
     and add a `ReuseTest`-style guard so it can't drift back.

5. **Command auto-registration is a deliberate, documented decision, done right.**
   `paper-plugin.yml` states explicitly: "Deliberately no commands: block, and a bootstrapper that
   registers nothing. This is a library — taking a name like /warp on somebody's server is not its
   decision to make." Keep this as-is; it's a good pattern worth repeating in any future
   Core-adjacent library.

6. **Scope-creep trajectory is empirically real, not a hypothetical worry.**
   123 commits in, `git log --oneline` on `RainsCore` shows a steady stream of `feat:` commits each
   adding one more "service" to the interface — sidebar, bossbar, places, identities, punishments,
   items/achievements, chat prompts, invsee, SQLite+audit, land protection, grants,
   vanish/godmode, command directory. The interface has grown monotonically the whole time this
   project has existed (since ~2026-08-04). The "define the boundary now" recommendation is
   grounded in an observed trend, not speculation.

## Decision: do not split Core into multiple plugins/artifacts (e.g. Core-World, Core-Teleportation)

Considered and rejected. Two concrete reasons:

- **It would break arbitration**, the one thing Core is actually for. Arbitration (one
  `ActionBars`, one `Travel`, etc. per player) only works because it's one object graph inside one
  plugin lifecycle. Splitting Core into several plugins recreates the "who owns the actionbar"
  problem one level up, between Core's own pieces.
- **It would import the module-versioning problem Core exists to avoid for consumers — but for
  Core's own internals.** `Warps` calling `places()`, `Travel` calling `Safety`, etc. currently
  cost nothing because they're the same artifact/classloader. Splitting them into separate
  plugins turns every one of those calls into a cross-plugin dependency with its own `depend:`
  line and compatibility range, for zero arbitration benefit (none of these pieces need
  singleton-per-player arbitration between *each other*).

What's already the right move, and should continue: internal package-level modularity within one
deployable artifact (see `cab96dc refactor: thirty-four flat packages become six groups by
purpose`). If a service doesn't need Core's internal coupling, pull it out to a *module*
(different plugin, downstream of Core) — not into a second Core.

## Round 2 — broad sweep across all 12 sibling repos (agy / Gemini, spot-checked)

### Remaining Core services: infrastructure vs. freestanding feature

| Service | Verdict | Evidence |
|---|---|---|
| `combat()` | **Keep in Core** — arbitrates `EntityDamageByEntityEvent` cancellation centrally so arenas/claims/etc. don't fight over listener priority | `world/combat/Combat.java:44` |
| `vanish()` | **Keep in Core** — single source of truth for visibility across chat/tablist/commands | `moderation/vanish/Vanish.java:23` |
| `powers()` | **Keep in Core** — hooks the same central damage listener as `combat()` for god-mode/instakill | `moderation/players/PlayerPowers.java:28` |
| `chunks()` | **Keep in Core** — arbitrates one plugin unloading a chunk another is holding | `world/chunk/ChunkHolds.java:40` |
| `effects()` | **Keep in Core** — central sound/particle registry, UI-consistency arbitration | `ui/effect/Effects.java:20` |
| `prompts()` | **Keep in Core** — arbitrates which plugin owns the next chat line, same class of problem as UI arbitration | `ui/prompt/ChatPrompts.java:40` |
| `achievements()` | **Move to a module** — plain progress-tracking registry, no arbitration | `content/achievement/Achievements.java:59` |
| `votes()` | **Move to a module** — isolated ballot-tallying utility | `content/vote/Votes.java:42` |
| `items()`/`itemFactory()`/`itemAbilities()` | **Move to a module** — boilerplate item/cooldown/usage tracking, not arbitrated state | `content/items/ItemAbilities.java:33` |
| `players()` (PlayerAdmin) | **Move to a module** — Bukkit-call wrapper (heal/feed/gamemode), no cross-plugin state | `moderation/players/PlayerAdmin.java:35` |
| `audit()` | **Move to a module** — async logging utility, independent of game mechanics | `moderation/audit/Audit.java:51` |
| `commands()` (CommandDirectory) | **Move to a module** — just aggregates a help book | `platform/command/CommandDirectory.java:31` |
| `grants()` | **Move to a module** — permissions tracker for LuckPerms-less servers, no arbitration need | `platform/permission/Grants.java:47` |

Net: the arbitration-needing half of Core (`combat`, `vanish`, `powers`, `chunks`, `effects`,
`prompts`, plus round-1's `Warps`) looks like the real Core. The rest (`achievements`, `votes`,
`items`/`itemFactory`/`itemAbilities`, `players`, `audit`, `commands`, `grants`, plus round-1's
`LootTables`/`FarmWorlds`) is a substantial second half that doesn't need to be Core at all — this
is the concrete shape of the scope-creep problem, not just its trend line.

### Confirmed duplication / arbitration-bypass across modules (spot-checked, real)

- **`RainsHomes` hand-rolls its own warmup instead of using Core's `Travel`.** Its own
  `ScheduledTask`/countdown loop with cancel-on-move/cancel-on-damage —
  `RainsHomes/src/main/java/de/raindancer/homes/HomeService.java:116`. Same class of duplication as
  pre-seam `RainsTPA` (see round 1). *Verified by direct read: confirmed real.*
- **`RainsHomes` bypasses `actionBars()`**, calling `player.sendActionBar(...)` directly for its
  warmup countdown — `HomeService.java:156`. *Verified by direct read: confirmed real.*
- **`RainsExtendedClaims` bypasses `actionBars()`** the same way —
  `MovementListener.java:277`. Not independently re-verified this session, but same pattern as
  the confirmed `RainsHomes` case.
- **`RainsRecords` bypasses `actionBars()`** during employment operations —
  `Employment.java:159`. Not independently re-verified.
- **`RainsAwesomePluginManager` bypasses `ChatPrompts`**, registering its own raw `AsyncChatEvent`
  listener at `LOWEST` priority instead of using `prompts()` —
  `ChatPromptListener.java:27`.
- **`RainsAwesomePluginManager` reimplements settings loading** against raw Bukkit
  `FileConfiguration` instead of `settingsFor()` — `ApmConfig.java:25`.
- **`RainsAwesomePluginManager`** is legitimately its own standalone plugin (a Paper-server
  package manager) — keep it independent, but it should still consume Core's utilities rather than
  reimplement them.
- **`RainsSMPCore` is not a Core consumer at all.** Its `paper-plugin.yml` (verified directly:
  `src/main/resources/paper-plugin.yml`, `target/classes/paper-plugin.yml`) declares no
  `RainsCore` dependency whatsoever — it's a verbatim merge of `RainsHomes`, `RainsTPA`,
  `RainsExtendedClaims`, and `RainsRecords` source into one jar ("formerly RainsHomes" etc.
  throughout its permissions block), inheriting every one of their Core-bypass problems at once
  (e.g. the same direct `sendActionBar` calls, same duplicated warmup). This is the single largest
  architectural risk found: an entire plugin that doesn't participate in Core's arbitration model
  by construction.

### CORRECTION (2026-08-14, later same day): most of round 2's module findings target dead code

Round 2 audited RainsHomes, RainsExtendedClaims, RainsRecords, RainsAwesomePluginManager as if they
were the live products. They are not, for five of the six: `Rain's Flexible Modules` (a sibling repo)
already contains a fully-migrated, correctly-built replacement for most of them:

| Old standalone repo | Superseded by | Status |
|---|---|---|
| `RainsHomes` | `homes-module` (+ `homes-standalone`) | Already correct — uses Core's `Travel`/`Cooldowns`/`Effects` properly. Retire the old repo. |
| `RainsTPA` | `tpa-module` (+ `tpa-standalone`) | Same — retire the old repo. |
| `RainsExtendedClaims` | `claims-module` (+ `claims-standalone`) | Same — retire the old repo. |
| `RainsColouredNames` | `names-module` | Same — retire the old repo. |
| `RainsResourcepackManager` | `pack-module` | Same — retire the old repo. |
| `RainsRecords` | **none** | No module exists for the record-seller feature. Still potentially live — do NOT retire without separately confirming. `Employment.java:159`'s direct `actionBar` bypass finding from round 2 still stands and is unfixed. |
| `RainsAwesomePluginManager` | none (different category — a package manager, not a feature) | Round 2's findings (bypasses `ChatPrompts`, reimplements settings loading) still stand and are unfixed. |
| `RainsGhastLines`, `RainsSimpleClans`, `RainsNoFlyingTrees`, `RainsSMPCore` | not checked this round | Not verified against the module reactor — `RainsSMPCore`'s bundle (homes+tpa+claims+records+ghastlines+names+pack) is close to but not identical to `yeuksmp`'s (claims+tpa+warp+moderation+pack+names+homes+rtp) — missing records and ghastlines from `yeuksmp`, so not cleanly superseded either. |

**Practical effect on this file's earlier recommendations:** the Core-side items (dedupe
`FarmWorlds`↔`WorldRegenerator`, add `ChunkPregen`, `TravelListener`'s live `moveCancels`/`hurtCancels`
flags) are unaffected and were completed and verified. The "extract `Warps`/`FarmWorlds` to a module"
recommendation and the "build a real Moderation state/policy split" recommendation from round 1 both
already exist as `warp-module` and `moderation-module` in the reactor — verify their actual content
before doing that work again from scratch.

### Scope note (2026-08-14, later still): focus is RainsCore + Rain's Flexible Modules only

User confirmed explicitly: `RainsRecords` and `RainsAwesomePluginManager` are out of scope going
forward. `RainsRecords` keeps the fixes already made this session (Core dependency, actionbar
routing, the `/recordseller` bootstrap bug fix — commit `8bf70d9`) but gets no further work.
`RainsAwesomePluginManager` was investigated (duplicate `ChatPrompts`/`MenuManager` logic, duplicate
settings loading in `ApmConfig`) but received no source changes — it isn't a Flexible Modules module
at all (a package-manager tool, not a feature) and was never a good target for this effort. Don't
pick either back up without the user asking again.

### New recommendation from round 2

`RainsSMPCore` is the most urgent item, more than any single Core-service boundary question: as
long as it exists as a standalone verbatim merge, every arbitration guarantee Core provides
(one actionbar, one set of teleport safety checks) is void on any server running it, regardless of
how clean Core's own boundary is. Either migrate it to depend on and consume `RainsCore`, or
replace it with a metapackage that declares dependencies on the individual (Core-consuming)
modules rather than forking their source.
