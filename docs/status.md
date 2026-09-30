# Verified Status

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
- No autonomy features have been implemented.
