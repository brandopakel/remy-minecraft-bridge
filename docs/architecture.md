# Architecture

## Why Carpet

The first Mineflayer/Yuniko attempt could not complete the Homestead modded login handshake. Local logs and source inspection pointed to `owo-lib` requiring an `owo:handshake` login query with channel and particle-controller hashes. PrismarineJS `minecraft-protocol` defaults to responding to unknown login plugin requests as not understood, so a plain Mineflayer client is not a compatible drop-in for this Fabric pack.

Carpet avoids that client-handshake problem by running inside the already-launched Fabric server/client and creating a server-side fake player with `/player`.

## Components

- `scarpet/remy_bridge.sc`: world-local Scarpet app loaded by Carpet from the copied world's `scripts` folder.
- `src/controller.mjs`: local command-line controller that writes JSON files to the copied world's app data folder.
- `scripts/remy_bridge.data/command.json`: one-shot command mailbox.
- `scripts/remy_bridge.data/ack.json`: command acknowledgment and state snapshot.
- `scripts/remy_bridge.data/state.json`: latest state snapshot.

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

The app rejects stale commands, acknowledges each accepted/rejected command, deletes processed command files, and sends `/player remy stop` automatically after a bounded move expires.

## Current Test Status

Prepared and verified locally:

- Pinned Carpet release identified and staged separately for local installation.
- Duplicate Homestead profile created and registered separately from the original.
- Carpet jar installed only in the duplicate profile.
- Copied test world is `New World - Remy LAN Test`.
- Bridge source, controller, and tests created.
- Node safety test suite passes.
- Versioned Scarpet script `0.1.2` is deployed to the copied test world.

Blocked before live proof:

- The running Scarpet app has not reloaded the versioned script yet. The live state file is still missing `bridgeVersion`.
- Run `/script load remy_bridge` in the duplicate profile's copied world to activate the deployed script.

Unknown until launch:

- Whether the first live proof succeeds after reload: spawn, status, look, brief bounded move, explicit stop, final status.
