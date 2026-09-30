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
- Versioned Scarpet script `0.1.2` has been deployed to the copied test world.
- Local controller tests pass: 9/9.

## Current Blocker

The running Scarpet app has not reloaded the latest deployed script yet. The live state file is still missing `bridgeVersion`, so runtime proof is pending a manual `/script load remy_bridge` in the duplicate world.

## Live Proof Status

Completed:

- Confirmed the bridge can acknowledge file commands in the copied world.
- Confirmed stale initial script failed spawn due Carpet command syntax and fixed the source.
- Confirmed safety tests for action allowlist, command TTL bounds, path confinement, stale/malformed acknowledgments, bounded movement, explicit stop sequencing, and Scarpet allowlist/version checks.

Pending:

- Reload script in game.
- Verify `bridgeVersion: 0.1.2` in `state.json`.
- Run approved live sequence: spawn, status, bounded surroundings/inventory read, look, brief move, stop, final status.
