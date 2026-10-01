# remy-minecraft-bridge

This repository tracks local experiments for bringing `remy` into a Homestead Minecraft world as a separate teammate.

## Current Track: PlayerEngine Adapter

The active track is a small self-authored Fabric mod, `remy-playerengine-adapter`, installed only in the duplicated Remy Homestead profile. It reuses the official PlayerEngine Fabric `1.20.1-1.4.0` lower-level navigation APIs instead of continuing custom Scarpet movement/pathfinding.

Current adapter commands:

- `/remyengine spawn`
- `/remyengine status`
- `/remyengine come`
- `/remyengine follow`
- `/remyengine stop`
- `/remyengine say <message>`

Verified so far:

- Official PlayerEngine jar was downloaded, hashed, and staged in the duplicate profile only.
- The adapter builds and loads with Homestead.
- The hardened PlayerEngine config disables call-by-name chat, owner-offline continuation, ModIntelligence startup inspection/enrichment, RAG/live memory helpers, deep-check helpers, and TTS ack.
- After restart, logs showed `payerMode=OWNER_PAYS_ALL`, `dedicated=false`, `ownerOfflineContinue=false`, `callByNameChat=false`, `ModIntelligence: disabled by config`, and no fresh Player2 device-flow prompt in the observed startup window.

Not proven yet:

- Runtime spawn/come/follow/stop behavior.
- Long-run guarantee that no PlayerEngine AI/auth/network feature can be triggered by unrelated commands. The adapter avoids PlayerEngine controller/API/auth/LLM/TTS classes, but the upstream jar still registers its broader systems. Do not use `/playerengine`, Player2 sign-in, voice AI, or natural-language PlayerEngine features for this track.
- Complex building or full autonomy.

## Legacy Track: Carpet/Scarpet Bridge

The previous track used official Carpet plus a world-local Scarpet app and a local Node controller. It remains useful as recovery/history, but it is not the active movement path.

Legacy capabilities that were proven in the copied world:

- Spawn a Carpet fake player named `remy`.
- Read position, gamemode, inventory, and bounded surroundings.
- Look, briefly move, and stop with command acknowledgments.

Legacy limitations:

- Custom follow/pathfinding hit Scarpet runtime type issues and was paused.
- It should not be used for new movement tests unless deliberately revived.

## Project Boundaries

- Preserve the original Homestead profile/worlds.
- Treat the current Remy world as valuable user progress and back it up before risky changes.
- Do not publish Minecraft saves, player data, logs, third-party jars, credentials, or local machine paths.
- Keep Git pushes milestone-based: useful tested states, not every small scratch change.

See `docs/setup.md`, `docs/playerengine-adapter.md`, `docs/status.md`, and `docs/architecture.md` for the current test flow and safety notes.
