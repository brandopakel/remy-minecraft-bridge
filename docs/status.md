# Verified Status

## 2026-10-03 (late, v0.3.4 → v0.4.0): local brain installed, architect written

- `/remyengine brain setup` (triggered at startup through `config/remy/setup-brain.request`) downloaded `OllamaSetup.exe` from ollama.com (1.58 GB), installed it silently per-user (exit 0), found Ollama 0.35.1 running, pulled `tev1:0.8b` (774 MB), and got an HTTP 200 decision from `/v1/systemone`.
- Accuracy note: for "chop some birch for me" the 0.8b model picked `follow` (0.52) over `gather` (0.45), but the right target (`minecraft:birch_log`). Clear phrasing is handled by the rules first; the 4b model would be more accurate but needs about 4.5 GB more memory.
- Texture colours checked offline against the real 1.20.1 client jar: oak planks #a2824e, spruce log #3a2510, stone bricks #7a797a, mossy cobblestone #6e765e, cherry planks #e2b2ac.
- Architect (catalog, blueprint, design call, instant/survival build) compiles and is installed as v0.4.0, but is **not verified in-game yet**: Homestead crashed on world load twice more with Windows commit memory exhausted (Minecraft ~10.5-11.4 GB, ~21 GB used by other programs, 32 GB limit). Needs a larger page file.

## 2026-10-03 (evening, v0.3.1 → v0.3.2): brain chat, no Player2 prompts, first real village

Verified in-game with `remy-playerengine-adapter-0.3.1`:

- Chat `remy protect me` → rules → `set_follow_mode DEFENDER` + `follow xxBP00`; Remy answered and stayed close.
- Chat `remy chop 3 oak logs for me` → rules → `get oak_log 3`; Remy felled a tree, picked up the logs, task `SUCCEEDED`.
- No Player2 authentication/device-flow messages after either chat (0.3.0 had triggered one; the 0.3.1 conversation-queue mute fixed it).
- `/remyengine village setup` installed agentcraft's Python deps (Python 3.14, numpy 2.5, scipy 1.18, bitarray 3.11) in ~40 s.
- Chat `remy build a village here` started agentcraft against the live 1.20.1 world through the GDMC bridge: NBT read, site found on attempt 18, main street at (943, 54), settlers Birch/Cora/Eli built a tower, tiny houses, logs, a market stall, hay, carts and lamps.

Problems found:

- First village run died with `OpenBLAS error: Memory allocation still failed`. OpenBLAS starts one thread per core (24 on this PC), each with its own buffers, and Windows was already near its commit limit. Fixed by capping BLAS threads at 1: in `village/agentcraft-remy.patch` (run.py) and in the village process environment (v0.3.2).
- About 2.5 minutes into the second run, Minecraft itself crashed with a native out-of-memory error: commit charge 11.6 GB with `-Xmx6144m`, and the system page file had 11 MB left (32 GB commit limit = 16 GB RAM + 16 GB page file). This is the same failure as the earlier Homestead crashes. Background apps (Chrome, VMs) are using most of the commit budget. Fix on the PC side: a larger or system-managed page file, or closing heavy apps before playing.
- A `/tp` to y=135 in survival killed the player (fall damage). Use spectator mode to inspect builds.

## 2026-10-03: Remy moves, works, and has a brain

Verified in-game on Homestead 1.3.7 (profile `Homestead - Remy Carpet Test`, world `New World - Remy LAN Test`):

- `/remyengine come`: Remy walked ~11 blocks to the owner in 7 s (PlayerEngine `GetToBlock` task finished).
- Chat `remy follow`: Remy followed the owner downhill.
- `/remyengine do get oak_log 4`: Remy broke an oak tree, picked up the drops, task `get:oak_logx4 SUCCEEDED`.
- PlayerEngine started with Player2 AI/sign-in disabled; no device-flow prompt, no Player2 network calls observed.

Root causes of the 0.1.x "pathing=true but never moves" bug:

1. PlayerEngine doesn't tick controllers globally. The body must call `PlayerEngineController#serverTick()` itself, which is where Baritone inputs get applied. The 0.1.x adapter had no controller, so it computed paths but never applied inputs.
2. The zombie (MobEntity) body ran vanilla NoAI/MoveControl logic that zeroes movement input. Remy is now a plain `LivingEntity` (same structure as the reference body in Goodbird-git/Player2NPC) with melee support and a player-model renderer.

Added and checked off-game (`scripts/run-checks.sh`, all passing):

- `RemyBrain`: rules → optional Jev/tev1 decision → PlayerEngine command lines; registry-based item/mob/block matching including modded ids.
- `GdmcBridge` + `VillageManager`: GDMC-HTTP 0.4.x endpoint for agentcraft; 1.16 chunk encoding round-trips through agentcraft's own decoder; patched agentcraft ran end-to-end against `village/mock_gdmc.py`.

Not yet verified in-game: chat brain intents beyond follow/come/stop, Jev/tev1 calls (no key or Ollama installed yet), village generation in the real world.

Incidents: the first launch crashed with Windows out of commit memory (7 historical Homestead crashes show the same), and was fixed by closing Chrome. During testing, a creeper killed the player while the pause menu wasn't responding to key input.

## Local Findings

- Homestead is Minecraft `1.20.1` on Fabric.
- Homestead pack version is `1.3.7`.
- The original target world is `New World`; the working test copy is `New World - Remy LAN Test`.
- Earlier startup failure evidence was native memory exhaustion, not resource-pack warnings.
- Java 17 was used for the Homestead launch.
- The Mineflayer/Yuniko route failed the required modded handshake.
- Local culprit matched `owo-lib` for 1.20, which sends a required login query on `owo:handshake`.

## Prepared For Carpet Experiment

- Official Carpet `1.4.112` release artifact staged and hash recorded outside this repo.
- Duplicate profile folder: `Homestead - Remy Carpet Test`.
- CurseForge display name observed for the duplicate: `Homestead - A Cozy Survival Experience (1)`.
- Carpet is installed only in that duplicate profile.
- The copied test world is `New World - Remy LAN Test`.
- The bridge app loaded in the duplicate world and acknowledged file commands.
- Versioned Scarpet script `0.1.2` was verified live by `bridgeVersion`.
- Versioned Scarpet script `0.1.5` adds safer spawn source, corrected selector syntax, fake-player-only control, lifecycle stop hooks, fresh heartbeat gating with 12 bounded heartbeat slots, lost-ack stop coverage, command-specific acks with controller-side pruning, and no tick `state.json` overwrite.
- Local controller tests pass: 16/16.

## Current Result

The approved proof-of-control succeeded in the copied test world with `remy_bridge` `0.1.5`. Remy spawned as a Carpet fake player in survival mode, position/inventory/bounded surroundings were read, look east succeeded, a 250 ms forward move succeeded, and an explicit stop left `moving: false`.

The original Homestead profile and original `New World` save were not modified. The proof ran only in the duplicate `Homestead - Remy Carpet Test` profile and copied `New World - Remy LAN Test` save.

This is still an experimental local control bridge, not an autonomous teammate. Mining, building, item use, inventory mutation, pathfinding, following, combat, and broad autonomy remain out of scope until separately designed and approved.

## Live Proof Status

Completed:

- Confirmed the bridge can acknowledge file commands in the copied world.
- Confirmed stale initial script failed spawn due Carpet command syntax and fixed the source.
- Confirmed safety tests for action allowlist, command TTL bounds, path confinement, stale/malformed acknowledgments, command-specific ack replay defense and pruning, heartbeat version/world/age gating, bounded movement, lost movement ack stop, explicit stop sequencing, and Scarpet allowlist/version checks.
- Confirmed fresh `bridgeVersion: 0.1.5` heartbeat advanced before live commands.
- Confirmed runtime spawn/status/look/move/stop/final-status sequence succeeded.
- Final proof state showed Remy online as `playerType: fake`, `controllable: true`, `gamemode: survival`, `moving: false`, inventory count `2`, surroundings count `27`, and no writer fault.
- Position changed from about `[889.31, 82, 34.57]` to `[889.70, 82, 34.57]` during the approved brief move.
- Post-proof log check showed Remy joined/spawned successfully and no new `Callback failed` or `io_exception` entries after the `0.1.5` reload.
- A later read-only stopped-state check did not complete because the single-player server paused before processing `status`; the expired status command was removed from the duplicate bridge data folder.

Remaining:

- Remy remains installed and available in the copied test world only.
- No production/original-world rollout has been done.
- No production autonomy features have been implemented.

## Follow Milestone In Progress

Local follow changes add owner-only `/remy_bridge follow`, `/remy_bridge stop`, `/remy_bridge status`, plus exact chat commands `remy follow`, `remy stop`, and `remy status`. Follow is implemented as a single tick-local goal with generation-based stop cancellation, bounded local supported-neighbor navigation, and conservative stops for unsupported routes, stuck movement, hazards, dimension mismatch, owner logout, or non-fake Remy.

Local tests after the `0.1.9` fix pass: 42/42, and `npm run check` passes. The added follow checks cover owner-gated cancellable commands, stop-before-player-stop sequencing, bounded local navigation, one-block step-up, modest safe drops, hazard avoidance, unsupported-cliff no-route handling, stuck stops, Scarpet tuple queue shape, and command-file stop priority before follow ticks.

Latest live `0.1.6` follow attempt recognized owner chat (`remy follow on`) but immediately stopped with `followStatus: blocked_blocked_feet`. Diagnosis: Scarpet reports vanilla block names as `air`/`stone` in observed state, while the `0.1.6` passable/hazard lists only matched namespaced ids such as `minecraft:air`. `0.1.7` accepts both forms.

Latest live `0.1.7` follow attempt recognized owner chat and loaded correctly, but immediately stopped with `followStatus: blocked_cliff`. Runtime evidence showed Remy at `[889.546, 82, 34.3]` with a projected east step over unsupported air. `0.1.8` replaces straight-line stepping with bounded local supported-neighbor navigation.

Latest live `0.1.8` follow attempt activated and recognized the route-follow goal, but failed repeatedly in Scarpet with `Argument 'address' to a list index has to be of a numeric type` at `pos = node:'pos'` inside `__find_follow_step`. The cause was Scarpet container access semantics: the runtime frontier node was a list-like value and required numeric tuple indexes rather than map-key access. A bridge `stop` command succeeded afterward, and a follow-state read confirmed `activeGoal: idle`, `followActive: false`, `moving: false`, `moveExpiresMs: 0`, and `followStatus: manual_stop`.

Prepared `0.1.9` fixes the route queue to use explicit tuple nodes (`node:0`, `node:1`, `node:2`), adds a Scarpet-side queue-shape self-test recorded in heartbeat/state, and processes external file commands before follow ticks so `stop` keeps priority if a future route tick fails.

`0.1.9` is deployed on disk in the copied test world with a backup of the previous copied-world script at `remy_bridge.sc.bak-20260930-001041`. The restarted game is still reporting live `0.1.8` heartbeats, so `0.1.9` has not been activated in memory yet and no follow retry has been run.

Live `0.1.9` later activated with `runtimeSelfTest.ok: true` and Remy respawned as a fake survival player, but the follow attempt hit a second Scarpet numeric coercion error at `abs(nx - rx)` in `__find_follow_step`. A bridge stop succeeded immediately afterward with `activeGoal: idle`, `followActive: false`, `moving: false`, and `followStatus: manual_stop`.

Prepared and deployed `0.1.10` casts position triples and route direction tuple components through numeric helpers before movement math, extends the runtime self-test to exercise the exact `abs(nx - rx)` arithmetic, and keeps command polling before follow ticks. The copied-world `0.1.9` script was backed up as `remy_bridge.sc.bak-20260930-002308`. Live memory remains `0.1.9` until `/script load remy_bridge` activates `0.1.10`.

## Guard/Fight Primitive Work

Local branch: `guard-fight-primitives`.

Added pure Node guard/combat primitives without changing the deployed world script:

- Owner command parsing for `remy guard`, `/remy_bridge stop`, `remy fight nearest hostile`, and explicit `remy fight <target>`.
- Guard/fight state transitions with stop-priority generation cancellation.
- Hostile target selection that excludes owner, Remy, players, pets, and passive mobs by default.
- Attack-once intent with cooldown, never continuous attack.
- Reach and trace checks before attack intent.
- Stop behavior for target disappearance, owner logout, Remy offline/non-fake, dimension mismatch, and owner too far.

Local tests now pass: 26/26. This guard/fight work has not been deployed or live-tested; live combat remains blocked behind successful `0.1.6` follow proof.

## Blueprint Planner Work

Added offline building planner primitives without changing the deployed world script:

- Deterministic `small_shelter` blueprint spec with explicit anchor, orientation, dimensions, and namespaced block/state palette.
- Ordered, bounded placement steps with absolute positions.
- Material count and missing-material reporting.
- Progress reconciliation against observed block state, including complete, pending, unknown, and mismatched blocks.
- Build generation cancellation so stale placement actions cannot continue after cancel/stop.
- Documentation for translating user descriptions into validated specs without executing chat text or arbitrary code.

This is not live construction yet. It does not place, mine, overwrite, or consume inventory in Minecraft.

## Recovery State

- Local branch: `guard-fight-primitives`.
- Last full local validation: `npm test` passed 42/42 and `npm run check` passed after the `0.1.10` numeric-casting fix.
- Deployed duplicate-world script on disk is `0.1.10`; active in-memory script is still `0.1.9` until reload/startup activation.
- Follow runtime proof depends on a fresh `0.1.10` heartbeat before any further live movement test.
- Guard/fight and blueprint planner work is local-only and has not been deployed to Minecraft.
- No remote push has been made for this batch.

## PlayerEngine Adapter Track

Separate worktree/branch: `playerengine-adapter`.

Completed:

- Downloaded official PlayerEngine Fabric `1.20.1` v`1.4.0` from CurseForge file `8454548`.
- Verified PlayerEngine jar SHA-256: `0D160A4C5991DC40CEDA0889DA8C12E9997E60E5CB466BC2DA220CEDF12103CF`.
- Verified PlayerEngine mod metadata requires Minecraft `~1.20.1`, Java `>=17`, Fabric Loader `>=0.17.2`, Architectury `>=9.2.14`, and Fabric API. The duplicate profile already has compatible Fabric Loader, Architectury, and Fabric API.
- Built self-authored `remy-playerengine-adapter-0.1.0.jar` successfully with Gradle `8.10.2`.
- Adapter jar SHA-256: `B7CBBDE627B7DA753E7E19A993D9C11777E8042949646FABDF9D4B271D19A03E`.
- Added deterministic command surface: `/remyengine spawn`, `/remyengine come`, `/remyengine follow`, `/remyengine stop`, `/remyengine status`, and `/remyengine say`.
- Audited PlayerEngine startup enough to identify automatic local mod-intelligence inspection as the main first-launch side effect to suppress.
- Added profile-local config template `config/playerengine/server_player2.json` with mod-intelligence disabled for deterministic adapter testing.

Current night checkpoint:

- The working world `New World - Remy LAN Test` in the duplicate `Homestead - Remy Carpet Test` profile is now treated as the main Remy world. It is no longer disposable.
- Latest save/exit evidence for that world is clean: `latest.log` shows `Saving chunks`, `ThreadedAnvilChunkStorage: All dimensions are saved`, `[FastQuit] Finished saving "New World - Remy LAN Test"`, and `Stopping!`.
- Fresh main-world backup completed after that clean exit:
  - backup folder: `minecraft-save-backups/New World - Remy LAN Test-main-backup-20261001-201952`
  - manifest: `minecraft-save-backups/New World - Remy LAN Test-main-backup-20261001-201952.manifest.json`
  - restore path: `Homestead - Remy Carpet Test/saves/New World - Remy LAN Test`
  - verified count/size: 186 files, 37,203,113 bytes
  - verified matching SHA-256 hashes for `level.dat`, `level.dat_old`, `session.lock`, and `kubejs_persistent_data.nbt`
- PlayerEngine `1.20.1-1.4.0`, `remy-playerengine-adapter-0.1.0`, and the hardened `config/playerengine/server_player2.json` are present only in the duplicate profile.
- Installed PlayerEngine jar SHA-256: `0D160A4C5991DC40CEDA0889DA8C12E9997E60E5CB466BC2DA220CEDF12103CF`.
- Installed adapter jar SHA-256: `0F63193D45966E632A7FD48D6D0FBDA56712862054116EDD02A79AB4B8FA122F`.
- The previous adapter jar was preserved at `mods/remy-playerengine-adapter-0.1.0.jar.bak-20261001-202259`.
- The old Scarpet bridge app was disabled, not deleted: `scripts/remy_bridge.sc.disabled-20261001-202259`. Existing `remy_bridge.sc.bak-*` recovery files remain in place.
- The hardened config now sets `payerMode: OWNER_PAYS_ALL`, `dedicatedClientProxy: false`, `ownerOfflineServerContinuation: false`, `callByNameChat: false`, `modIntelligenceEnabled: false`, `modIntelligenceInspectOnLaunch: false`, `modIntelligenceEnrichmentEnabled: false`, and `botTtsPlaybackAckEnabled: false`.
- Local validation after the install checkpoint: `npm test` passed 42/42 and Gradle `build` succeeded.
- Runtime compatibility and suppression of Player2 auth/network prompts have not been verified after this install checkpoint because no launch/runtime test was requested tonight.
- Do not use `/playerengine`, Player2 auth, voice AI, or the old `/remy_bridge` commands during the next verification. The intended first runtime commands remain the deterministic adapter commands: `/remyengine spawn`, `/remyengine status`, `/remyengine come`, `/remyengine stop`.
