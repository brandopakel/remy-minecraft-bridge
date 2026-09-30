# Setup

## Prerequisites

- Homestead pack version `1.3.7`.
- Minecraft `1.20.1`.
- Fabric loader compatible with the Homestead instance.
- Java 17 with enough memory for Homestead. The pack docs recommend 6-8 GB allocated.
- Official Carpet release `1.4.112` for Minecraft `1.20` / `1.20.1`.
- Node.js 20 or newer for the local controller.

## Isolated Test Flow

1. Make sure the original Homestead game is fully closed.
2. Use a duplicate launcher profile, not the original profile.
3. Install the official Carpet jar into only the duplicate profile's `mods` folder.
4. Copy only the existing Remy LAN test save into the duplicate profile's `saves` folder.
5. Copy `scarpet/remy_bridge.sc` into the copied save's `scripts` folder.
6. Launch the duplicate profile and open the copied save.
7. If the app does not autoload, run this in-game once:

   ```text
   /script load remy_bridge
   ```

8. From this repository, run:

   ```powershell
   node .\src\controller.mjs --world "<copied-save-path>" smoke
   ```

The smoke test sends: `spawn`, `status`, `look east`, short `move forward`, `stop`, `status`.

## Manual Commands

```powershell
node .\src\controller.mjs --world "<copied-save-path>" status
node .\src\controller.mjs --world "<copied-save-path>" spawn
node .\src\controller.mjs --world "<copied-save-path>" look north
node .\src\controller.mjs --world "<copied-save-path>" move forward 400
node .\src\controller.mjs --world "<copied-save-path>" stop
```

Movement duration is capped at 1000 ms in both the controller and the Scarpet app.
