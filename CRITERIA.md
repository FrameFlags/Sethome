# Criteria: Sethome

- [ ] For all 12 cells, `build.yml` matrix shows `success` and each expected jar exists.
- [ ] For all 12 cells, `behavior.yml` matrix shows `success`, with exactly one canonical `SETHOME_TEST_RESULT:` line per job.
- [ ] `/sethome alpha` + move + `/home alpha` restores x/y/z/yaw/pitch on every cell.
- [ ] `/home list` lists homes; `/delhome alpha` removes it; bare `/sethome` and `/home` use `home`.
- [ ] `list` cannot be used as a home name (case-insensitive).
- [ ] `maxHomes = 1` rejects a second distinct home in the negative control.
- [ ] `countdownSeconds > 0` fires exactly once at the deadline and does not teleport early.
- [ ] No repeated chat lines on any tick; countdown uses action bar only.
- [ ] Homes survive a server restart in at least one cell.
- [ ] Pure Java core tests pass (limit/overwrite, one-shot countdown, JSON round-trip).
- [ ] Final HEAD has green build + behavior runs and README links both.
