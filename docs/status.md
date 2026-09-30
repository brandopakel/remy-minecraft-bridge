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
- Duplicate profile name: `Homestead - Remy Carpet Test`.
- Carpet is installed only in that duplicate profile.
- The duplicate profile currently has no saves copied while the original game remains open.

## Current Blocker

The user needs to Save and Quit the currently running Homestead session before the test save can be copied consistently into the duplicate profile.
