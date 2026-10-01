# Setup

## Active PlayerEngine Track

Use the duplicate Remy Homestead profile only. Do not install this track into the original Homestead profile.

Prerequisites:

- Homestead pack version `1.3.7`.
- Minecraft `1.20.1`.
- Java 17 with enough memory for Homestead. The pack docs recommend 6-8 GB allocated.
- Fabric loader compatible with the duplicated Homestead instance.
- Official PlayerEngine Fabric `1.20.1-1.4.0`.
- Architectury `9.2.14` and Fabric API already present in the Homestead duplicate profile.
- Self-authored `remy-playerengine-adapter-0.1.0.jar`.

Installed duplicate-profile files:

- `mods/playerengine-fabric-1.20.1-1.4.0.jar`
- `mods/remy-playerengine-adapter-0.1.0.jar`
- `playerengine/server_player2.json`

The template at `config/playerengine/server_player2.json` is the hardened config used for the duplicate profile. It disables call-by-name chat, owner-offline continuation, ModIntelligence startup inspection/enrichment, RAG/live memory helpers, deep-check helpers, and TTS ack.

Runtime startup verification:

1. Back up the current Remy world before risky changes.
2. Launch the duplicate Homestead profile.
3. Open the Remy world.
4. Inspect `latest.log` before testing commands.

Expected startup evidence:

```text
Player2 server config: payerMode=OWNER_PAYS_ALL dedicated=false ownerOfflineContinue=false callByNameChat=false
Player2 ModIntelligence config: enabled=false enrichmentEnabled=false
Remy PlayerEngine adapter initialized
ModIntelligence: disabled by config
```

Unexpected evidence that should stop testing:

```text
Attempting local login
Starting web device flow
player2.game
Player2 HTTP
```

Do not complete Player2 sign-in for this deterministic adapter test.

Manual test commands:

```text
/remyengine status
/remyengine spawn
/remyengine status
/remyengine come
/remyengine stop
/remyengine status
```

Only test `/remyengine follow` after spawn/come/stop are verified. Do not use `/playerengine`, Player2 natural-language commands, voice AI, or old Scarpet commands during PlayerEngine adapter verification.

## Build

The adapter builds with Gradle:

```powershell
.\gradlew.bat --no-daemon build
```

Build outputs must not be committed except through intentional release artifacts. Third-party jars, Minecraft saves, logs, player data, and local machine paths stay out of the repository.

## Legacy Carpet/Scarpet Track

The older Carpet bridge remains in the repo for recovery/history. It is not the active movement track.

Legacy prerequisites:

- Official Carpet release `1.4.112` for Minecraft `1.20` / `1.20.1`.
- Node.js 20 or newer for the local file controller.

Legacy flow:

1. Copy `scarpet/remy_bridge.sc` into the copied world's `scripts` folder.
2. Configure the owner file in the copied world's app data folder. Do not commit it.
3. Load the app with `/script load remy_bridge` if needed.
4. Use the Node controller only when deliberately testing the legacy bridge.

Legacy commands:

```text
/remy_bridge follow
/remy_bridge stop
/remy_bridge status
remy follow
remy stop
remy status
```

The legacy bridge proved spawn/read/look/short-move/stop, but custom follow/pathfinding is paused after runtime type issues.
