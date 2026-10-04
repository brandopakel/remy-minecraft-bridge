# Village builder (agentcraft bridge)

[agentcraft](https://github.com/bytewife/agentcraft) is an agent-based settlement generator from the 2021 GDMC competition (2nd place). Simulated settlers pick a site, lay roads, and raise houses, barns, churches and market stalls from bundled schematics while you watch. It was written for Minecraft 1.16.5 and the GDMC-HTTP 0.4.x mod, neither of which exists for Homestead's 1.20.1 Fabric.

Remy bridges both gaps:

- **The mod speaks GDMC-HTTP 0.4.x** (`GdmcBridge.java`): `/blocks`, `/command`, `/buildarea`, `/chunks`. `/chunks` re-encodes 1.20 chunks into the 1.16 NBT layout agentcraft parses (sections y 0..255, 9-bit heightmaps, non-spanning packed states). The encoder is checked against agentcraft's own decoder in `src/checks`.
- **agentcraft-remy.patch** updates agentcraft for current Python: configurable endpoint + token header (`REMY_GDMC_URL`, `REMY_GDMC_TOKEN`), NumPy 2 fixes, bitarray 3 fix, and a stand-in for the `names` package (it no longer builds on Python 3.12+). Base commit `cb92518`.

## Install

```
git clone https://github.com/bytewife/agentcraft <instance>/remy/agentcraft
cd <instance>/remy/agentcraft
git checkout cb92518
git apply <this repo>/village/agentcraft-remy.patch
```

Then in game, once: `/remyengine village setup` (runs `py -3 -m pip install --user -r requirements.txt`; log in `logs/remy-village-setup.log`).

## Use

- Chat `remy build a village here`, or `/remyengine village [radius 24-100]` (default 48, so a 96×96 area centered on you).
- `/remyengine village stop` cancels. Progress lines appear in chat; full output is in `logs/remy-village.log`.
- Settings: `config/remy/village.json` (`python` command, `agentcraftDir`, `port`, `steps`, `seconds`, `frameSeconds`).

It edits terrain in the area (roads, foundations, buildings), and the settlers show up as named armor stands.

## Safety

The endpoint runs only while a village is being generated. It binds to 127.0.0.1, requires a fresh random token per run (passed to the Python process via its environment), and rejects any request with a browser `Origin` header, so a web page can't reach it.

## Testing without Minecraft

`mock_gdmc.py` implements the same contract over a flat world:

```
REMY_GDMC_TOKEN=t python3 village/mock_gdmc.py 9000 &
REMY_GDMC_TOKEN=t python3 run.py -a 0,0,96,96 -t 200 -s 600 -f 0 --norender --nochronicle
```

Verified 2026-10-03: 600 steps in about 6 s, main street placed, settlers building (e.g. `hay_1_flex`).
