# Blueprint Planner

This is an offline planning layer for future building. It does not place blocks in Minecraft yet.

## Current Scope

The planner supports one deterministic shape:

- `kind: "small_shelter"`
- explicit `anchor: [x, y, z]`
- explicit `orientation: "north" | "east" | "south" | "west"`
- supplied dimensions: `width`, `depth`, `height`
- namespaced palette entries such as `minecraft:oak_planks`
- optional block states, normalized in sorted key order

The compiled plan contains ordered placement steps with absolute positions, palette keys, block ids, state, and item id. The planner also reports material counts before any construction action is attempted.

## Description Translation Boundary

User descriptions should be translated into a validated blueprint spec, not executed as code. For example, "make a tiny oak shelter facing east" can become:

```json
{
  "version": 1,
  "kind": "small_shelter",
  "anchor": [100, 70, 100],
  "orientation": "east",
  "dimensions": { "width": 5, "depth": 5, "height": 3 },
  "palette": {
    "floor": { "block": "minecraft:oak_planks" },
    "wall": { "block": "minecraft:oak_planks" },
    "roof": { "block": "minecraft:oak_slab", "state": { "type": "bottom" } },
    "torch": { "block": "minecraft:torch" }
  }
}
```

The planner rejects missing anchors, unknown orientations, non-namespaced blocks, invalid state values, unsupported blueprint kinds, excessive step counts, and unknown palette keys.

## Reconciliation

The planner compares expected placements against observed block state:

- `complete`: expected block and state already exist.
- `pending`: target is air, unknown, or not observed yet.
- `mismatched`: another non-air block is present.

By default, a mismatch blocks the next placement action. Future live builders should stop and ask for a decision rather than overwrite world content unexpectedly.

## Materials

Material counts are item-based, not magic construction authority. If inventory data is available, `missingMaterials` reports exact deficits before a placement action is emitted.

## Cancellation

Build goals carry a generation number. Cancelling a build increments the generation, so stale placement actions cannot be accepted after a stop/cancel.

## Future Live Construction

A future live builder should consume one validated placement step at a time, verify the observed target block, verify inventory/material availability, place one block, then reconcile again. Direct Scarpet construction helpers and survival-realistic fake-player placement are different modes and must be labeled clearly.
