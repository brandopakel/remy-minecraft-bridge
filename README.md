# remy-minecraft-bridge

Remy is an AI companion that lives inside modded Minecraft with you: follows you, fights with you, gathers, mines, farms, and can grow a whole village. Built and tested on **Homestead 1.3.7 (Fabric, Minecraft 1.20.1)**; designed to carry over to other packs.

## How it works

```
 you, in chat ──► "remy chop 8 wood"
                     │
          ┌──────────▼───────────┐   free, instant: plain-code rules
          │  Remy brain (in mod) │──► unsure? one call to a decision model:
          └──────────┬───────────┘      Jev (hosted, OpenRouter) or tev1 (local, Ollama)
                     │ "get oak_log 8"   — the model can only pick from our options
          ┌──────────▼───────────┐
          │ PlayerEngine hands   │   pathing, combat, mining, crafting, farming
          └──────────┬───────────┘   (no model while Remy works)
                     ▼
               Remy's body (player-model NPC in your world)
```

- **Mod** (`src/main/java`): `remy-playerengine-adapter`, a Fabric mod built on [PlayerEngine](https://www.curseforge.com/minecraft/mc-mods/playerengine) (Fabric/Forge 1.20.1, Fabric/NeoForge 1.21.1). PlayerEngine's own Player2 LLM/sign-in path is never used; Remy calls its deterministic task commands directly.
- **Brain** (`brain/RemyBrain.java`): rules first, then an optional decision model. Items, blocks and mobs come from the live modpack registry, so modded content works (`remy get create:andesite_alloy 4`).
- **Village builder** (`village/`): [agentcraft](https://github.com/bytewife/agentcraft) (GDMC 2021, 2nd place) patched for 1.20, driven through a localhost-only, token-protected GDMC endpoint inside the mod.

## Talking to Remy

| In chat | What Remy does |
|---|---|
| `remy follow` / `remy come` / `remy stop` | Handled in code, never by a model |
| `remy protect me` | Defender follow mode: fights hostiles near you |
| `remy kill all the monsters` | Clears hostiles nearby |
| `remy chop 16 wood`, `remy get 4 torches` | Gathers or crafts the item |
| `remy mine some iron` | Mines ore and collects drops |
| `remy set up a farm` / `remy harvest` | Builds a 9x9 farm / harvests it |
| `remy give me 5 bread` | Hands you items from Remy's inventory |
| `remy build a village here` | Runs agentcraft around you (see `village/README.md`) |

Direct commands: `/remyengine spawn | come | follow | stop | status | commands | do <task> | ask <text> | brain | village [radius|stop|setup]`.

## Docs

- `docs/setup.md`: install, brain providers (Jev / tev1), village builder.
- `docs/status.md`: what's verified in-game, with dates.
- `docs/architecture.md`: design and safety notes.
- `village/README.md`: agentcraft bridge details.

## Boundaries

- Third-party jars, Minecraft saves, logs and API keys stay out of this repo.
- The village builder's local endpoint exists only while a village is being generated, is bound to 127.0.0.1, needs a per-run token, and refuses browser requests.
