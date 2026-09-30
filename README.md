# remy-minecraft-bridge

This is a small local proof-of-control bridge for testing `remy` as a separate teammate in a copied Homestead Minecraft world.

The design uses the official Carpet mod for Minecraft 1.20/1.20.1 and a world-local Scarpet app. A local Node controller writes JSON command files into the copied world's `scripts/remy_bridge.data` directory, and the Scarpet app acknowledges commands and writes state back. There is no network listener, public tunnel, RCON, new credential, or second Minecraft client.

Current scope:

- Spawn a Carpet fake player named `remy`.
- Read position, look vector, gamemode, inventory, and a bounded 3x3x3 surrounding block sample.
- Look in cardinal/up/down directions.
- Move briefly, capped at 1000 ms, then stop automatically.
- Stop immediately on request or when a command goes stale.

Not in scope yet:

- Mining, building, attacking, using items, dropping items, hotbar changes, inventory mutation, or broad autonomy.
- Original Homestead profile/world mutation.
- Publishing Minecraft saves, player data, logs, jars, or Homestead pack content.

See `docs/setup.md` and `docs/architecture.md` for the exact test flow and safety model.
