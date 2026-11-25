# Sethome

Personal home teleport commands for Minecraft: `/sethome [name]`, `/home [name]`, `/home list`, `/delhome [name]`. No op, no cheats, no permissions plugin required.

## Commands

| Command | Behavior |
|---|---|
| `/sethome <name>` | Saves current position, yaw/pitch and dimension under `name`. Overwrites an existing home of that name. |
| `/sethome` | Same, using the default name `home`. |
| `/home <name>` | Teleports instantly to the home (or after the configured countdown). |
| `/home` | Teleports to the home named `home`. |
| `/home list` | Lists the player's homes with coordinates. |
| `/delhome <name>` | Deletes one home (default name `home`). |

`list` is reserved and cannot be used as a home name. Homes are private per player UUID. Modern versions suggest the player's existing home names when typing `/home <name>`.

## Config

`config/sethome.json`:

```json
{
  "maxHomes": -1,
  "countdownSeconds": 0
}
```

- `maxHomes = -1` means infinite homes; `>= 0` is a per-player limit.
- `countdownSeconds = 0` means instant teleport. A positive value shows a server-tick countdown on the action bar only — never one chat message per tick.
- Config changes are read at server startup; restart after editing.

## Cells

| Cell | Minecraft | Loader | Java |
|---|---|---|---|
| 1.12.2-forge | 1.12.2 | Forge | 8 |
| 1.16.5-forge | 1.16.5 | Forge | 8/16 |
| 1.16.5-fabric | 1.16.5 | Fabric | 16 |
| 1.19.2-forge | 1.19.2 | Forge | 17 |
| 1.20.1-forge | 1.20.1 | Forge | 17 |
| 1.20.1-fabric | 1.20.1 | Fabric | 17 |
| 1.21.1-fabric | 1.21.1 | Fabric | 21 |
| 1.21.1-neoforge | 1.21.1 | NeoForge | 21 |
| 26.1.2-fabric | 26.1.2 | Fabric | 25 |
| 26.1.2-neoforge | 26.1.2 | NeoForge | 25 |
| 26.3-fabric | 26.3 | Fabric | 25 |
| 26.3-neoforge | 26.3 | NeoForge | 25 |

CI links and exact Gradle pins are recorded here once all workflows are green.

## Verification philosophy

Compiling is not enough. Every cell gets a launcher boot **and** a real in-game probe that executes the commands as the joined player and writes one machine-readable result line. Negative controls prove the tests can fail.
