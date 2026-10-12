# Changelog

## 1.1.1

No changes to the mod itself — this is 1.1.0 re-released because its CurseForge upload was
rejected by the CurseForge API and never went out. If you already have 1.1.0 from Modrinth or
from the GitHub release, you are not missing anything.

If you are on 1.0.x, read the 1.1.0 notes below and update: it fixes two bugs that could
destroy a full inventory with no way to get it back.

## 1.1.0

Two bugs in this release could destroy a full inventory with no way to get it back. If you are
on 1.0.x, update.

### Fixed — graves destroyed by the explosion that killed you

A creeper (or TNT, a ghast fireball, an end crystal, a bed in the Nether) that killed you would
usually also delete the grave it caused, along with every item in it. Nothing could recover
them: `/grave list` only tracks virtual graves, and `/grave history` can show what you lost but
not return it.

The cause was an ordering problem rather than a missing blast resistance value. Minecraft picks
the blocks an explosion will destroy *before* it damages anything — and that list includes empty
air. Your death, and with it the grave placement, happens during the damage step. The grave
therefore landed in a cell that was already queued for removal, and the clean-up pass at the end
of the same explosion removed it without re-checking that a blast-proof block had appeared
there in the meantime.

Graves are now genuinely explosion-proof, including during the explosion that creates them.

### Fixed — dying twice within 5 seconds destroyed the second death's items

An internal guard against duplicate drop events keyed off "did this player die in the last 5
seconds" rather than off the specific death. A real second death inside that window was
mistaken for a duplicate event and had its entire inventory discarded — no grave, no virtual
grave, not even a death-history entry. Respawning into lava, into a mob-infested spawn point or
back into the void was enough to trigger it.

This also meant death-loop detection, which exists precisely for repeated deaths in the same
spot, never got a chance to run for deaths less than 5 seconds apart.

### Fixed — the Ender Dragon and the Wither deleted graves

Both destroy blocks without consulting blast resistance, honouring only
`#minecraft:dragon_immune` and `#minecraft:wither_immune`. The grave block is now in both tags.

### Added — rescue net for any other external removal

If a grave block is removed by something that isn't one of the mod's own pickup paths — a modded
block breaker or quarry, `/setblock`, worldedit, a third-party explosion that bypasses vanilla's
explosion code — the contents are now converted into a virtual grave retrievable with
`/grave list` instead of being destroyed. The server log records it as
`[pg.grave] grave @ <pos> removed externally (...) → virtual grave <uuid>`.

### Changed — `[fallback].mode` now defaults to `VIRTUAL_GRAVE`

The old default, `SAFE_DROP`, left items as loose entities on the ground when no valid grave
spot could be found. Those despawn after five minutes and are destroyed by explosions and lava,
and no command can bring them back. The new default keeps them server-side until the owner
retrieves them.

**This only affects new installs.** An existing `config/perfectgraves-common.toml` keeps
whatever it already has, so change `mode` by hand if you want the new behaviour on a world you
have already run.

### Also in this release

- An XP-only grave (all items soulbound, XP saved) is no longer discarded when the self-destruct
  timer converts it to a virtual grave.
- Quick-pickup now drains stored XP atomically, closing a double-grant race against the
  take-XP button in the browse menu.
- Removed leftover debug logging that wrote a stack trace on every player logout and spammed
  INFO lines on every death.
