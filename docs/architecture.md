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

Prepared:

- Pinned Carpet release identified and downloaded separately for local installation.
- Duplicate Homestead profile shell created without copied saves.
- Carpet jar installed only in the duplicate profile.
- Bridge source and controller created.
- Node controller syntax check passes.

Blocked before live proof:

- The current Homestead game process is still running, so the test save has not been copied into the duplicate profile.
- The user needs to Save and Quit before copying the test save and launching the duplicate profile.

Unknown until launch:

- Whether Homestead plus Carpet starts cleanly in the duplicate profile.
- Whether CurseForge automatically recognizes the filesystem-level duplicate profile.
- Whether Scarpet autoload works in this copied world without a manual `/script load remy_bridge`.
