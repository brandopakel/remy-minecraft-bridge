# remy-minecraft-bridge

This is a small local proof-of-control bridge for testing `remy` as a separate teammate in a copied Homestead Minecraft world.

The design uses the official Carpet mod for Minecraft 1.20/1.20.1 and a world-local Scarpet app. A local Node controller writes JSON command files into the copied world's `scripts/remy_bridge.data` directory, and the Scarpet app acknowledges commands with command-specific JSON files. There is no network listener, public tunnel, RCON, new credential, or second Minecraft client.

Current scope:

- Spawn a Carpet fake player named `remy`.
- Read position, look vector, gamemode, inventory, and a bounded 3x3x3 surrounding block sample.
- Look in cardinal/up/down directions.
- Move briefly, capped at 1000 ms, then stop automatically.
- Owner-only in-game commands: `/remy_bridge follow`, `/remy_bridge stop`, `/remy_bridge status`, plus chat forms `remy follow`, `remy stop`, and `remy status`.
- Follow as a first-pass tick-local goal with stop priority, bounded distance/vertical checks, and conservative hazard/cliff/obstacle stops.
- Offline blueprint planning for a deterministic `small_shelter` spec, including palette validation, material counts, ordered placement steps, reconciliation, and cancellation generation.
- Stop immediately on request or when a command goes stale.
- Avoid shared overwrite-style state files so Windows readers cannot block Carpet's delete-and-replace file writes.
- Keep heartbeat files bounded to a small rotating slot set instead of writing one file per tick.
- Prune old command acknowledgment files from the controller side.

Not in scope yet:

- Reliable pathfinding around obstacles, live construction, mining, attacking, using items, dropping items, hotbar changes, inventory mutation, or broad autonomy.
- Original Homestead profile/world mutation.
- Publishing Minecraft saves, player data, logs, jars, or Homestead pack content.

See `docs/setup.md`, `docs/architecture.md`, and `docs/blueprints.md` for the exact test flow and safety model.
