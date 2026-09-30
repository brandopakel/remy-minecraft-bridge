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
- The bridge app loaded in the duplicate world and writes state files.
- Versioned Scarpet script `0.1.2` was verified live by `bridgeVersion`.
- Versioned Scarpet script `0.1.3` changes spawn to execute as the human player before `player remy spawn`, so Remy should inherit the player's mode instead of using the server/script source.
- Local controller tests pass: 9/9.

## Current Blocker

Live `0.1.2` bridge status works in the copied world. Runtime proof is pending deployment/reload of `0.1.3`, then the approved spawn/read/look/brief-move/stop sequence.

## Live Proof Status

Completed:

- Confirmed the bridge can acknowledge file commands in the copied world.
- Confirmed stale initial script failed spawn due Carpet command syntax and fixed the source.
- Confirmed safety tests for action allowlist, command TTL bounds, path confinement, stale/malformed acknowledgments, bounded movement, explicit stop sequencing, and Scarpet allowlist/version checks.

Pending:

- Reload script in game.
- Verify `bridgeVersion: 0.1.3` in `state.json`.
- Run approved live sequence: spawn, status, bounded surroundings/inventory read, look, brief move, stop, final status.
