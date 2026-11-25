# Sethome implementation notes (for cell adapters)

## Shared core (already written)

`shared-core/com/frameflags/sethome/core/`: `HomeRecord`, `HomeConfig`, `HomeResult`, `HomeData`, `Pending`, `CountdownQueue`. Copy this whole package into every cell at `src/main/java/com/frameflags/sethome/core/` (plain source copy; no cross-cell dependencies).

Behavior already implemented and unit-tested in `shared-tests/`:
- `HomeData.normalize(String)` trims + lowercases with `Locale.ROOT`.
- `set` rejects `list` (`RESERVED_NAME`) and blank names (`INVALID_NAME`); overwrite is always allowed; new names are blocked by `maxHomes >= 0`.
- `CountdownQueue.schedule` is an upsert per player; `tick(now)` drains due entries so each fires once.

## Product module API (must exist in every cell)

`com.frameflags.sethome.Sethome` with static members:
- `HomeConfig config()`
- `HomeData data()`
- `CountdownQueue queue()`
- `Path dataFile()` — world-specific data file, e.g. `config/sethome-homes.json` is acceptable if consistent
- `Path configFile()` — `config/sethome.json`
- `void init(Path gameDir)` — loads config and homes once; creates parent dirs
- `void saveHomes()` — writes `data().toJson()` to `dataFile()`

Command adapter must implement the six forms in `BEHAVIOR_CONTRACT.md`, no `.requires(...)`, no permission level > 0.

Teleport: use saved dimension key, x+0.5, y+0.5, z+0.5, saved yaw/pitch. On countdown, call `queue.schedule(uuid, name, currentTick + seconds*20)`, cancel on death/disconnect, and on due re-resolve the home (home might have been deleted).

Storage: if a version has `SavedData`/`WorldSavedData`, use it to persist the JSON string with dirty marking; if not, use the JSON file directly in `config/`. `Sethome.saveHomes()` must write the same data the commands use.

## Test-only probe module (staged by CI as a separate jar)

Package `com.frameflags.sethome_test`. The probe must:
1. Wait for a joined player (or use a fabricated player on 1.12.2 if no client harness works).
2. Dispatch the real commands as that player through the server command dispatcher (not by calling product helpers directly).
3. Move the player away, then assert `/home` restores exact x/y/z/yaw/pitch (tolerance 0.01).
4. Assert `/home list` contains the name, `/delhome` removes it, bare forms use `home`, and `list` name is rejected.
5. If `Sethome.config().maxHomes == 1`, assert a second distinct home is rejected with exactly one message.
6. If `Sethome.config().countdownSeconds > 0`, assert no early teleport and teleport exactly at the deadline.
7. Count chat messages: fail if the same message is repeated on consecutive ticks.
8. Write exactly one canonical line `SETHOME_TEST_RESULT: PASS ...` or `SETHOME_TEST_RESULT: FAIL reason=...` to `sethome-result.txt` in both the repo root and `run/`; also print it to stdout with `[SETHOME_PROBE]` prefix.
9. Exit assertion must be machine-readable; CI greps the canonical file only.

## Local skeletons (copy pins/metadata from these, rewrite identity)

- 1.20.1-fabric: `../KeepInventoryPorts/1.21.1-fabric` pattern with 1.20.1 pins from `../SimpleMechanicMods/mods/auto-fishing/1.20.1-fabric`
- 1.20.1-forge: `../SimpleMechanicMods/mods/auto-fishing/1.20.1-forge`
- 1.21.1-fabric: `../KeepInventoryPorts/1.21.1-fabric`
- 1.21.1-neoforge: `../VoidRescue/1.21.1-neoforge` or CBT `worldshop/1.21.1-neoforge`
- 26.1.2/26.3 fabric+neoforge: `../CreateBigCannonsTNT/mods/worldshop/26.1.2-fabric|neoforge` and `../KeepInventoryPorts/26.3-fabric|neoforge`
- 1.19.2-forge, 1.16.5-forge/fabric, 1.12.2-forge: no local skeleton for the first three except `../CallOrbitalCannons-1.12.2` and `../SimpleMechanicMods/mods/auto-fishing/1.12.2-forge`; derive pins from official MDK and validate with CI.

## Hard rules

- Never edit `.github/`, `README.md`, `BEHAVIOR_CONTRACT.md`, `CRITERIA.md`, or `shared-core/` from a cell task.
- Use `mod_id = sethome`, test mod id `sethome_test`.
- Product jar name `sethome-<cell>-1.0.0.jar`; probe jar `sethome-testmod-<cell>-1.0.0.jar`.
- Fabric 1.21.x probe jars must go through Loom `remapJar` (named -> intermediary), like KeepInventoryPorts did.
- No `|| true` on any assertion path.
