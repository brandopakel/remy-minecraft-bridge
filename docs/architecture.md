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

In-game commands are owner-gated through a local, uncommitted `owner.json` file in `scripts/remy_bridge.data`. The Scarpet app registers `/remy_bridge follow`, `/remy_bridge stop`, and `/remy_bridge status`, and also consumes exact owner chat messages `remy follow`, `remy stop`, and `remy status`. Non-owner chat is ignored.

Follow is a single active goal managed inside the tick loop. A stop command increments the goal generation and cancels the active goal before issuing `/player remy stop`, so stale or queued work cannot restart movement after a stop. The current follow behavior is intentionally simple steering: look at the owner, move forward in short pulses, and stop on distance, vertical gap, obstacle, cliff, dimension mismatch, owner logout, or hazards such as lava, fire, cactus, magma, campfires, berry bushes, or powder snow. It does not use fake-player pathfinding because fake players are server players, not mobs with navigation.

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

- Whether the `0.1.6` follow milestone succeeds after reload: owner-only slash/chat commands, follow start, bounded local steering, and reliable stop.

## Roadmap Semantics

Future combat should avoid `/player remy attack continuous` because that command can mine blocks and is not an entity-combat loop. Combat experiments should use deliberate attacks with cooldowns and explicit targets.

Future building has two different modes that must be labeled honestly. Scarpet direct placement helpers can construct or set blocks without consuming Remy's inventory, while survival-realistic building should use held inventory and fake-player actions. The same distinction applies to harvesting: direct destruction is not normal timed mining.

Future crafting and automation should treat `recipe_data` as recipe inspection, not a universal executor for custom mod machines or GUIs. Modded custom GUI interaction may need separate, explicit support.

Fake players can disconnect on death. Higher-level goals must treat death/disconnect as a stop-and-replan event.
