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
- Versioned Scarpet script `0.1.4` adds safer spawn source, fake-player-only control, lifecycle stop hooks, fresh heartbeat gating with 12 bounded heartbeat slots, lost-ack stop coverage, command-specific acks, and no tick `state.json` overwrite.
- Local controller tests pass: 15/15.

## Current Blocker

Live `0.1.2` bridge status worked in the copied world, then hit a Windows writer failure while Carpet removed `state.json`. The screenshot error was `io_exception` at the `write_file('state','json', __state())` line inside `__poll_remy_bridge`. Runtime proof is pending deployment/reload of `0.1.4`, then the approved spawn/read/look/brief-move/stop sequence.

## Live Proof Status

Completed:

- Confirmed the bridge can acknowledge file commands in the copied world.
- Confirmed stale initial script failed spawn due Carpet command syntax and fixed the source.
- Confirmed safety tests for action allowlist, command TTL bounds, path confinement, stale/malformed acknowledgments, command-specific ack replay defense, heartbeat version/world/age gating, bounded movement, lost movement ack stop, explicit stop sequencing, and Scarpet allowlist/version checks.

Pending:

- Reload script in game.
- Verify fresh `bridgeVersion: 0.1.4` in a bounded `heartbeat_*` slot file.
- Run approved live sequence: spawn, status, bounded surroundings/inventory read, look, brief move, stop, final status.
