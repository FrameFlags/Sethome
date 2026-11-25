# Sethome behavior contract

This is the single source of truth for what every cell must do. The in-game probe and the CI assertions are derived from it.

## Commands

1. `/sethome <name>` saves `x, y, z, yaw, pitch, dimension` for the command sender's UUID. Bare `/sethome` must use `home`.
2. `/home <name>` restores that exact position and facing. Bare `/home` must use `home`.
3. `/home list` lists every home owned by the sender, with coordinates.
4. `/delhome <name>` removes one home. Bare `/delhome` uses `home`.
5. `list` is reserved: `/sethome list` is rejected with one clear message.
6. No command requires op, cheats or a permission plugin.
7. On Brigadier cells, `/home <name>` completion offers only that player's home names.

## Config and countdown

- `config/sethome.json` defaults: `maxHomes = -1` (infinite), `countdownSeconds = 0` (instant).
- With `maxHomes >= 0`, a player may not create more than that many distinct home names. Overwriting an existing name is always allowed.
- With `countdownSeconds > 0`, the teleport is queued on server ticks and fires once when the deadline is reached. The countdown is displayed on the action bar only; exactly one chat confirmation may be sent when the countdown starts and one when it completes.
- Pending teleports are cancelled on death or disconnect.

## Storage

- Homes are keyed by player UUID and home name, stored in world data (`SavedData`/`WorldSavedData`), private per player, and survive a server restart.
- Dimension is stored as a stable string key. Orientation is stored as yaw/pitch.

## No spam

- No command may emit repeated chat lines on successive ticks.
- A rejected command emits exactly one error message.
- `/home list` emits one message (multi-line where the platform supports it), not one message per home.

## Test assertions (all cells)

A test-only probe drives the real command dispatcher as the joined player and asserts:

1. `/sethome alpha` stores the player's current x/y/z/yaw/pitch.
2. Player moves away, `/home alpha` restores the exact pose (within floating-point rounding).
3. `/home list` includes `alpha`.
4. `/delhome alpha` removes it; `/home alpha` then fails once.
5. Bare `/sethome` and `/home` use `home`.
6. A negative cell with `maxHomes = 1` rejects a second distinct home.
7. A countdown cell with `countdownSeconds > 0` does not teleport before the deadline and does teleport at the deadline.
8. No repeated chat lines are observed.

The probe writes exactly one canonical line `SETHOME_TEST_RESULT: PASS|FAIL ...` to `sethome-result.txt` (root and `run/`). CI requires exactly one canonical result file line, then checks the verdict and required detail substrings. A cell without the product jar must fail the negative control.
