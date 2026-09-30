# PlayerEngine Adapter Track

This track is separate from the paused Carpet/Scarpet bridge.

Verified local dependency:

- `playerengine-fabric-1.20.1-1.4.0.jar`
- CurseForge project/file: `playerengine-1322604:8454548`
- Size: `4,460,317` bytes
- SHA-256: `0D160A4C5991DC40CEDA0889DA8C12E9997E60E5CB466BC2DA220CEDF12103CF`
- Embedded mod metadata: `playerengine`, version `1.20.1-1.4.0`, Minecraft `~1.20.1`, Java `>=17`, Fabric Loader `>=0.17.2`, Architectury `>=9.2.14`, Fabric API.

Important caveat: the official PlayerEngine jar initializes its conversation manager and internal packet receivers at mod load. This adapter avoids constructing `PlayerEngineController`, `Player2APIService`, `LLMCompleter`, Player2 auth, TTS, or any natural-language brain path. It uses the lower-level `IBaritone`/Automatone pathing component only.

Initial command surface:

- `/remyengine spawn`
- `/remyengine come`
- `/remyengine follow`
- `/remyengine stop`
- `/remyengine status`

The first NPC body is deliberately zombie-rendered to avoid shipping custom client assets. It is an NPC, not a real Minecraft player, and complex building remains unsupported in this track until separately implemented and tested.

## Build and Startup Audit

Adapter build:

- Gradle `8.10.2` was downloaded from the official Gradle distribution service and its SHA-256 was verified before use.
- Build cache was kept under the local task workspace.
- `gradle --no-daemon build` succeeded.
- Built adapter: `build/libs/remy-playerengine-adapter-0.1.0.jar`
- Adapter SHA-256: `B7CBBDE627B7DA753E7E19A993D9C11777E8042949646FABDF9D4B271D19A03E`

PlayerEngine load audit from the verified jar:

- `PlayerEngine.onInitialize()` loads `playerengine/server_player2.json`, registers commands/events, registers packet receivers, starts the conversation chat hook, and registers mod-intelligence event handlers.
- `MCCommands.onInit()` runs on server start and calls `ModIntelligenceService.initialize(server)`.
- PlayerEngine defaults have `modIntelligenceEnabled=true` and `modIntelligenceInspectOnLaunch=true`, so an unconfigured first launch can run local mod-intelligence inspection.
- `modIntelligenceEnrichmentEnabled` defaults false, so enrichment/API calls are not on by default, but the local inspection is unnecessary for this deterministic adapter test.
- `Player2APIService.trySendHeartbeat()` can call the Player2 API, but it is reached from a `PlayerEngineController` instance. This adapter does not construct `PlayerEngineController`, `Player2APIService`, `LLMCompleter`, or the Player2 natural-language service path.
- Packet handlers for client proxy/STT/TTS register at load, but registration alone does not perform an outbound call.

For first runtime staging, copy `config/playerengine/server_player2.json` into the duplicate profile's `playerengine/server_player2.json` before launching with PlayerEngine. This disables mod-intelligence startup inspection/enrichment while leaving the adapter's deterministic `/remyengine` commands available.

## Install-Only Checkpoint

Installed into the duplicate Homestead profile only after Minecraft was fully closed and the current `New World - Remy LAN Test` save was backed up.

Installed files:

- `mods/playerengine-fabric-1.20.1-1.4.0.jar`
  - SHA-256: `0D160A4C5991DC40CEDA0889DA8C12E9997E60E5CB466BC2DA220CEDF12103CF`
- `mods/remy-playerengine-adapter-0.1.0.jar`
  - SHA-256: `B7CBBDE627B7DA753E7E19A993D9C11777E8042949646FABDF9D4B271D19A03E`
- `playerengine/server_player2.json`
  - SHA-256: `AA27DA90057054FCCF1139CA12710C9C9A3AB72B422DFF4F784F71432C72065F`

No existing PlayerEngine config was present, so no config file needed to be backed up. Runtime compatibility has not been verified yet; next launch is the first PlayerEngine+adapter startup test.
