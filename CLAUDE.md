# Perfect Graves — Project Reference

A Forge 1.20.1 death-recovery mod. Items go into a gravestone (or virtual fallback) at the death
spot, get auto-equipped on retrieval, and are protected from non-owners during a configurable
timer. Designed to coexist with claim mods, accessory mods, and third-party soulbound systems.

This document is the stable reference Claude should consult before reasoning about the codebase.
The README is for end users; this document is the contributor-facing reference.

## Toolchain

- Forge **1.20.1** (`forge_version=47.4.10`), Java **17**, Parchment mappings (`2023.09.03-1.20.1`).
- Soft deps (compileOnly): FTB Chunks/Library/Teams, Open Parties And Claims, Flan, Curios.
- No `gradlew` wrapper — use `gradle` directly. Sandbox is fine; commands run from project root.

### Build / run / deploy

| Command | What it does |
| --- | --- |
| `gradle compileJava` | Compile only — fastest sanity check. |
| `gradle build` | Full build → `build/libs/perfectgraves-0.1.0.jar`. |
| `gradle runClient` | Dev client (in `run/`). Pass `-Pusername=Test1` / `-PuserUuid=…` to launch with a distinct identity for multi-player tests. |
| `gradle runServer` | Dev dedicated server (in `run/`). Console accepts `op <name>` etc. |
| `gradle deployToPrism` | Builds and copies the jar as `perfectgraves-dev.jar` to every path in `prismDeployPath` (set in `gradle-local.properties`, comma-separated). Used for testing against real third-party mods that misbehave under `runClient` (mixin-heavy mods, Xaero's). **Do NOT run while a client/server is using the jar — class loaders lose handles to lazy classes.** |

`gradle-local.properties` is gitignored. Example file is `gradle-local.properties.example`.

## Architecture

Top-level packages under `com.risen.perfectgraves`:

| Package | Responsibility |
| --- | --- |
| `accessories` | Curios bridge — re-equip rings/amulets/etc. to their original Curios slots on quick-pickup. Provider pattern (`ExtendedInventoryRegistry`) so absence of Curios doesn't break the mod. |
| `claims` | Claim-mod adapters (FTB Chunks, OPAC, Flan). Provider pattern again — each integration is loaded only if its class is present. Disable individually via `claims.enabledIntegrations`. |
| `client` | Renderers (BER + level-stage), client commands, marker caches (grave + loose-drop), client-side config readers. |
| `config` | All config (Forge `ForgeConfigSpec`). `PGConfig.COMMON.*` is server-side, `PGConfig.CLIENT.*` is client-side. Read per-call (`.get()`) so changes hot-reload. |
| `death` | `DeathEventHandler` (LivingDropsEvent + Clone), `DeathContext` (death-type classifier), `CascadeResult` enum. |
| `grave` | `GraveBlock` + `GraveBlockEntity` + `GraveContainerMenu` + `QuickPickup` + `GraveInteractionHandler` + `OwnerPresenceSync`. |
| `history` | Per-player death log (`DeathHistoryData` SavedData), `/grave history` menu (list + detail). |
| `marker` | Long-distance marker entity (`LooseDropMarkerEntity`), `MarkerMode` enum. |
| `placement` | `PlacementCascade` (the seven-step decision tree), `ShellSearch` (candidate scoring), `PlatformBuilder` (lava/void platforms), `SafePosTracker` (last-safe-ground per player). |
| `registry` | `DeferredRegister`s for blocks, BEs, items, menus, entities, command-arg types. |
| `soulbound` | `SoulboundFilter` (extracts soulbound items pre-grave), `SoulboundTracker` (in-memory cache + restore on Clone/Login). |
| `util` | `PGLog` (scoped logger), `NbtKeys`, `DimensionDisplay`. |
| `virtual` | `VirtualGrave` record, `VirtualGraveData` SavedData, `VirtualGraveCommand` (`/grave list|restore|history|debug`), `VirtualGraveMenu` (the list UI). |
| `xp` | `SafeXPEntity` (registered, vestigial — current quick-pickup grants XP directly to the player). |

Single mod-class entry point: `PerfectGraves.java` (`MOD_ID = "perfectgraves"`).

## The placement cascade

Most defects live here. The order is fixed and the log lines are stable contracts used for
support diagnostics:

1. **Death-loop truncation** — same dim + within `timeWindowTicks` + within `radiusBlocks`. Bypasses
   physical placement entirely → virtual grave. Log: `death-loop truncation → virtual grave`.
2. **Shell search** — score grid of cells in expanding shells around the corrected death pos. Best
   passing candidate gets a grave. Log: `step=1-shell grave @ <pos>`.
3. **Platform** — lava/void deaths with no natural ledge build a 3×3 platform. Log: `step=2-platform`.
4. **Fallback** by `[fallback].mode`: `SAFE_DROP` / `VIRTUAL_GRAVE` / `RETURN_TO_INVENTORY` / `VANILLA_DROP`.
5. **Last-resort virtual** — if nothing else worked and the mode isn't VANILLA_DROP, route to virtual
   grave so items are never extruded above bedrock or into the void. Log: `step=5-virtual (physical options exhausted)`.
6. **Vanilla drop** — only when admin chose VANILLA_DROP, or virtual graves are disabled. A loose-drop
   marker spawns at the death pos so the player can find their items. Log: `step=4-vanilla`.

There are seven distinct triggers that route to virtual grave: VIRTUAL_GRAVE fallback mode,
claim-blocked platform on lava/void, platform generation disabled, death-loop truncation,
SAFE_DROP-with-no-candidate (the Nether-bedrock rescue), RETURN_TO_INVENTORY mode (handled
by the same exhausted-options path), and self-destruct timer fired with `onSelfDestruct =
"VIRTUAL_GRAVE"`. Each one logs `step=5-virtual (...)` (or, for the self-destruct case,
`self-destruct @ <pos> → virtual grave <uuid>`) — keep this list in sync if the cascade
changes.

## Critical invariants

These are non-negotiable; violating them caused real bugs in the past.

- **Items are never silently lost.** If the cascade can't find a physical spot, it MUST route to
  virtual grave (when enabled) — the vanilla-drop branch only fires when the admin explicitly
  chose it or when `[virtualGrave].enabled = false`. Don't add a code path that drops to vanilla
  without a marker spawn AND a clear log line.
- **`LivingDropsEvent` runs at `EventPriority.LOWEST` with `receiveCanceled=false`.** Other mods'
  drop-adding listeners (Apotheosis, etc.) on NORMAL must run BEFORE us so their items land in
  the grave. If you change priority, document why in CLAUDE.md and add a regression test.
- **BE tick is 20-tick gated.** `GraveBlockEntity.serverTick` early-returns unless
  `gameTime % 20 == 0`. Per-tick work scales with the number of placed graves, so this matters.
- **Soulbound is in-memory only.** Items are kept in `SoulboundTracker.CACHE` between
  `LivingDropsEvent` and `PlayerEvent.Clone`/`PlayerLoggedInEvent`. Server crash between death
  and respawn means the items are gone. Acceptable per spec; do not introduce disk persistence.
- **Config is read per-call.** Always `PGConfig.COMMON.x.get()`, never cache. Keeps the spec
  the single source of truth so any future reload (or test override) takes effect immediately.
  In Forge 1.20.1 the COMMON-config file watcher itself is unreliable (atomic-save editors
  bypass it; OS-level NIO quirks add more flakiness), so the supported workflow for live edits
  is **restart the world** — don't promise hot-reload in user-facing docs.
- **Logging via `PGLog`.** All log lines start with `[pg.<scope>]`; scopes are constants in
  `PGLog`. The standard log filter `grep "pg\."` (in support / debugging contexts) depends on
  this format — don't introduce log lines that bypass `PGLog`.
- **The owner UUID in the BE is server-authoritative.** Synced to clients via `getUpdateTag` /
  `onDataPacket` so renderers and `getDestroyProgress` can use it client-side too. Don't gate
  client-side ownership checks on tab list — use the synced field.

## Interaction model on the grave block

| Action | Outcome |
| --- | --- |
| Right-click (no shift, any hand) | **Quick-pickup**: armor/curios/weapon auto-equip, rest to main inv, XP granted directly, grave removed. |
| Shift + right-click (empty hand) | Open the read-only manual-browse menu. |
| Shift + right-click (held item) | Vanilla suppresses our `use()` → block placement happens (intentional escape hatch). |
| Left-click hold (break, survival) | Same as quick-pickup — `BlockEvent.BreakEvent` is canceled and the pickup runs server-side. |
| Left-click (creative) | If non-owner: `PlayerInteractEvent.LeftClickBlock` is canceled before the action packet, no animation. If owner: triggers break path → quick-pickup. |
| Non-owner break (survival) | `getDestroyProgress` returns 0 → bedrock-like swing, no progress accumulates, no ghost-break flash. `BlockEvent.BreakEvent` cancellation is a backstop for any path that bypasses progress. |

## Hologram timer formulas (renderer)

`GraveBlockEntityRenderer` picks one of two formulas per frame, gated by the
**server-authoritative** `protectionPaused` flag (synced via `getUpdateTag`):

- **Unpaused** (`protectionPaused=false`): `endsAt − gameTime`. Smooth per-tick countdown.
  Matches "owner online" or `pauseProtectionWhileOffline=false`.
- **Paused** (`protectionPaused=true`): `total − elapsed`. Frozen display.
  Matches `pauseProtectionWhileOffline=true` AND owner offline.

Login/logout sync: `OwnerPresenceSync` listens for `PlayerLoggedInEvent` /
`PlayerLoggedOutEvent`, looks up the owner's graves in a per-owner static index
(populated in BE `onLoad` / `setContents`, cleaned in `setRemoved`), and pushes
`sendBlockUpdated` for each. This brings the formula transition's discontinuity
inside one tick (~50ms), so observers don't see the timer jump on owner presence flips.

## Localization

9 locales under `src/main/resources/assets/perfectgraves/lang/`: `en_us, de_de, es_es, fr_fr,
ja_jp, pl_pl, pt_br, ru_ru, zh_cn`. Conventions:

- Death-history and virtual-grave card lore reuse the same translation keys
  (`perfectgraves.history.lore.*`) so the format stays consistent.
- Counts use **label-prefix form** (`Items: %s • XP: %s`), not count-prefix — this avoids Slavic
  plural agreement issues entirely.
- Vanilla 1.20.1 ships no `dimension.minecraft.*` keys, so `DimensionDisplay` ships its own
  `perfectgraves.dimension.{overworld,the_nether,the_end}` and falls back to
  `dimension.<ns>.<path>` then prettified path for unknown dims.

## Configuration files

After first launch:
- `run/config/perfectgraves-common.toml` — server-side config.
- `run/config/perfectgraves-client.toml` — client-side config (mostly markers).

Common-config sections: `[search]`, `[claims]`, `[platform]`, `[fallback]`, `[timers]`,
`[virtualGrave]`, `[soulbound]`, `[xp]`, `[deathLoopDetection]`, `[deathHistory]`, plus
`dimensionOverrides` (top-level list of strings).
Client-config sections: `[marker]` only.

`dimensionOverrides` entries look like `"minecraft:the_end/maxSearchRadius=20"`. Malformed entries
warn (not fatal). Per-dim keys: `maxSearchRadius`, `voidGravePlatformY`.

## Common gotchas

- **`Inventory.add` silently destroys overflow when `Abilities.instabuild=true`** (creative mode).
  `QuickPickup.spillRest` works around this by temporarily clearing the flag. Don't reintroduce
  the bug by calling `add` inside a creative-mode path without that workaround.
- **`FluidTags.WATER` matches both source and flowing water.** Always check `isSource()` if you
  intend "source only" — relevant in `PlacementCascade.placeGrave` (grave waterlog) and anywhere
  else fluid identity matters.
- **Client `level.getGameTime()` lags the server by network jitter.** Don't compare it to a
  server-set absolute deadline expecting exact equality; use `>=` and a small slack.
- **Don't deploy while the game is running.** Class loaders lose handles to lazy classes when
  the jar is overwritten — `NoClassDefFoundError` mid-session.
- **Vanilla `BlockEntity.setRemoved` is called on chunk unload too**, not just block break. Code
  that only wants the "block broken" case must distinguish (see `GraveBlock.onRemove` and
  `GraveBlockEntity.setRemoved` for the pattern using chunk-state inspection).

## Testing

**Multi-player testing**: easiest via `gradle runClient -Pusername=Test1` for a second client
plus a dev `runServer`. For tests that need real third-party mods (Curios, FTB Chunks, claim
denials), deploy via `gradle deployToPrism` to two Prism instances on a local LAN server.

## Recently shipped (worth knowing about)

These are features that exist in the code but might not be obvious from a quick scan:

- **Death history** (`/grave history`, also `<player>` form for OPs) — separate SavedData in
  `history.DeathHistoryData`, distinct from virtual graves. Records every death, regardless of
  outcome. Click-through detail menu shows items at death time.
- **Long-distance markers + loose-drop markers** — separate from holograms, render at distance
  via `client.GraveHologramRenderer.onRenderLevelStage`. Loose-drop markers (gold variant)
  spawn on `SAFE_DROP` and `VANILLA_DROP` outcomes; per-viewer proximity dismissal is
  client-side and persistent in `config/perfectgraves/loose_marker_cache/<worldKey>.dat`.
- **Waterlogged graves** — only source water cells get waterlogged (not flowing). Standard
  `SimpleWaterloggedBlock` pattern.
- **Soulbound auto-equip second pass** — `SoulboundTracker` runs at LOWEST priority on both
  `Clone` and `PlayerRespawnEvent` to move armor pieces from main inv / hotbar into empty
  armor slots, fixing third-party soulbound mods that don't slot-route.
- **All-soulbound short-circuit** — `DeathEventHandler` skips the cascade entirely if the
  filtered pool is empty AND no XP is being saved.
- **Bedrock-like break feedback for non-owners** — `GraveBlock.getDestroyProgress` returns 0;
  creative-mode `LeftClickBlock` is canceled in `GraveInteractionHandler`.
- **Cause text in virtual-grave tooltips** — `VirtualGrave` carries `causeJson` (mirroring
  `DeathHistoryEntry`); the `/grave list` card lore matches the death-history format.
- **OwnerPresenceSync** — login/logout pushes per-owner BE syncs so the protection-timer
  hologram doesn't jump on presence flips.
