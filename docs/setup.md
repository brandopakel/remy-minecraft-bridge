# Setup

## 1. Mods

Into the modpack's `mods/` folder (one test profile: `Homestead - Remy Carpet Test`):

- PlayerEngine for your loader/version, e.g. `playerengine-fabric-1.20.1-1.4.0.jar` (CurseForge project 1322604). Needs Architectury and Fabric API (Homestead already has both).
- `remy-playerengine-adapter-<version>.jar` from `build/libs/`.

Copy `config/playerengine/server_player2.json` from this repo into the instance's `playerengine/` folder. It turns off PlayerEngine's Player2 AI, sign-in prompts and mod-intelligence scanning. Remy doesn't need any of them.

Memory: Homestead runs with a 6 GB heap. On a 16 GB PC, close Chrome and other heavy apps before launching; every Homestead crash on record was Windows running out of commit memory, not a mod bug.

## 2. In game

```
/remyengine spawn          (Remy appears next to you)
remy follow                (plain chat)
/remyengine do get oak_log 4
```

`/remyengine commands` lists every PlayerEngine task Remy can run.

## 3. Brain providers (optional; rules work without any)

`config/remy/brain.json` is created on first launch:

```json
{
  "useModel": true,
  "timeoutSeconds": 8,
  "providers": [
    { "name": "openrouter", "url": "https://openrouter.ai/api/alpha/decisions",
      "model": "typesafe/jev-1.13", "apiKeyEnv": "OPENROUTER_API_KEY", "apiKey": "" },
    { "name": "ollama", "url": "http://127.0.0.1:11434/v1/systemone",
      "model": "tev1:0.8b", "apiKeyEnv": "", "apiKey": "" }
  ]
}
```

Providers are tried in order and skipped when unavailable, so this means "Jev if there's a key, else local tev1, else rules".

- **Jev (hosted, TypeSafe):** $0.042 per million input tokens, output free; about 1,500 tokens per message, so roughly 6¢ per 1,000 commands. Put an OpenRouter key in the `OPENROUTER_API_KEY` environment variable (or `apiKey`). OpenRouter needs prepaid credit.
- **tev1 (local, free):** install Ollama 0.35+, then `ollama pull tev1:0.8b` (~0.8 GB; `tev1` 4B is ~4.5 GB and more accurate). On a 16 GB machine running Homestead, start with 0.8b.
- `/remyengine brain` reloads the file and shows which providers are usable.

The model is only asked to pick an intent and, when ambiguous, which registry item/mob you meant. Counts, names and the final command are filled in by code, and "stop" never goes to a model.

## 4. Village builder (optional)

See `village/README.md`. Short version: put patched agentcraft in `<instance>/remy/agentcraft`, run `/remyengine village setup` once (pip install), then `remy build a village here` or `/remyengine village 48`.

## Building the mod

```
gradle --no-daemon build        # Gradle 8.10.x (Loom 1.7 doesn't work with Gradle 8.14)
scripts/run-checks.sh           # plain-Java checks for the brain and the GDMC chunk encoder
```

`libs/playerengine-fabric-1.20.1-1.4.0.jar` must be present (gitignored).
