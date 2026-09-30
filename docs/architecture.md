# Architecture

## Why Carpet

The first Mineflayer/Yuniko attempt could not complete the Homestead modded login handshake. Local logs and source inspection pointed to `owo-lib` requiring an `owo:handshake` login query with channel and particle-controller hashes. PrismarineJS `minecraft-protocol` defaults to responding to unknown login plugin requests as not understood, so a plain Mineflayer client is not a compatible drop-in for this Fabric pack.

Carpet avoids that client-handshake problem by running inside the already-launched Fabric server/client and creating a server-side fake player with `/player`.

## Components

- `scarpet/remy_bridge.sc`: world-local Scarpet app loaded by Carpet from the copied world's `scripts` folder.
- `src/controller.mjs`: local command-line controller that writes JSON files to the copied world's app data folder.
- `scripts/remy_bridge.data/command.json`: one-shot command mailbox.
- `scripts/remy_bridge.data/ack_<command-id>.json`: command-specific acknowledgment and state snapshot. The controller keeps only the newest 64 ack files by default.
- `scripts/remy_bridge.data/heartbeat_<slot>.json`: deployment heartbeat used by the controller to verify the loaded app version and world. The app rotates across 12 slots, so heartbeat growth is bounded.

The bridge intentionally avoids shared overwrite-style files such as `state.json`. Carpet writes JSON by deleting the existing file before replacing it, and on Windows a reader that does not share delete access can make that fail. Command acks are command-specific with controller-side pruning, and heartbeats use a bounded slot ring so a stale reader cannot break the tick callback or safety watchdog.

## Safety Model

The Scarpet app allowlist is intentionally tiny:

- `spawn`
- `status`
- `look`
- `move`
- `stop`

The app does not expose Carpet actions for `attack`, `use`, `drop`, `dropStack`, `swapHands`, `hotbar`, `kill`, `shadow`, mount/sneak/sprint toggles, block setting, block destruction, or inventory mutation.

Every command carries:

- `id`: unique request ID.
- `action`: allowlisted action.
- `createdMs`: controller timestamp.
- `expiresMs`: absolute deadline.

The app rejects stale commands, acknowledges each accepted/rejected command, deletes processed command files, and sends `/player remy stop` automatically after a bounded move expires. The watchdog runs before file I/O; failed JSON writes are caught as Scarpet `io_exception`, recorded in memory, and retried for pending acknowledgments without replaying the action.

## Current Test Status

Prepared and verified locally:

- Pinned Carpet release identified and staged separately for local installation.
- Duplicate Homestead profile created and registered separately from the original.
- Carpet jar installed only in the duplicate profile.
- Copied test world is `New World - Remy LAN Test`.
- Bridge source, controller, and tests created.
- Node safety test suite passes.
- Versioned Scarpet script `0.1.5` includes the safer spawn source fix, corrected selector syntax, lifecycle stop hooks, a deployment heartbeat, fake-player-only control, and tighter command timing checks.

Blocked before live proof:

- Runtime `0.1.2` hit a Windows file writer failure while removing `state.json`. The fix is `0.1.5`, which removes tick `state.json` writes and uses command-specific ack files plus bounded heartbeat slots.
- Run `/script load remy_bridge` in the duplicate profile's copied world after deploying `0.1.5`, then verify a fresh `heartbeat_*` file before live control.

Unknown until launch:

- Whether the first live proof succeeds after reload: spawn, status, look, brief bounded move, explicit stop, final status.
