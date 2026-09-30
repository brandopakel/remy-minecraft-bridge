import assert from 'node:assert/strict';
import test from 'node:test';
import {
  blockKey,
  cancelBuildGoal,
  compileBlueprint,
  createSmallShelterSpec,
  initialBuildState,
  isCurrentBuildAction,
  missingMaterials,
  nextPlacementAction,
  reconcileProgress,
  startBuildGoal,
  transformLocalPosition,
} from '../src/blueprintPlanner.mjs';

test('createSmallShelterSpec requires explicit anchor and orientation', () => {
  assert.throws(() => createSmallShelterSpec(), /explicit anchor/);
  assert.throws(() => createSmallShelterSpec({ anchor: [1, 2, 3], orientation: 'up' }), /orientation/);

  const spec = createSmallShelterSpec({
    anchor: { x: 10, y: 64, z: 20 },
    orientation: 'east',
  });
  assert.deepEqual(spec.anchor, [10, 64, 20]);
  assert.equal(spec.orientation, 'east');
});

test('transformLocalPosition applies cardinal orientation deterministically', () => {
  assert.deepEqual(transformLocalPosition([0, 64, 0], 'north', [2, 1, 3]), [2, 65, -3]);
  assert.deepEqual(transformLocalPosition([0, 64, 0], 'east', [2, 1, 3]), [3, 65, 2]);
  assert.deepEqual(transformLocalPosition([0, 64, 0], 'south', [2, 1, 3]), [-2, 65, 3]);
  assert.deepEqual(transformLocalPosition([0, 64, 0], 'west', [2, 1, 3]), [-3, 65, -2]);
});

test('compileBlueprint validates namespaced palette and emits ordered bounded steps', () => {
  assert.throws(() => compileBlueprint({
    version: 1,
    kind: 'small_shelter',
    anchor: [0, 64, 0],
    orientation: 'north',
    dimensions: { width: 3, depth: 3, height: 2 },
    palette: {
      floor: { block: 'oak_planks' },
      wall: { block: 'minecraft:oak_planks' },
      roof: { block: 'minecraft:oak_slab', state: { type: 'bottom' } },
      torch: { block: 'minecraft:torch' },
    },
  }), /namespaced/);

  const plan = compileBlueprint(createSmallShelterSpec({
    anchor: [100, 70, 100],
    orientation: 'north',
    dimensions: { width: 3, depth: 3, height: 2 },
  }), { maxSteps: 64 });

  assert.equal(plan.steps.length, 33);
  assert.deepEqual(plan.steps[0], {
    id: 'place:0:0,0,0:floor',
    sequence: 0,
    local: [0, 0, 0],
    pos: [100, 70, 100],
    paletteKey: 'floor',
    block: 'minecraft:oak_planks',
    state: {},
    item: 'minecraft:oak_planks',
  });
  assert.equal(plan.steps.at(-1).paletteKey, 'torch');
  assert.equal(plan.steps.at(-1).block, 'minecraft:torch');
  assert.deepEqual(plan.materialCounts, {
    'minecraft:oak_planks': 23,
    'minecraft:oak_slab': 9,
    'minecraft:torch': 1,
  });
  assert.throws(() => compileBlueprint(createSmallShelterSpec({
    anchor: [0, 64, 0],
    dimensions: { width: 5, depth: 5, height: 3 },
  }), { maxSteps: 10 }), /exceeding max/);
});

test('blockKey normalizes state key order', () => {
  assert.equal(blockKey('minecraft:oak_slab', { waterlogged: false, type: 'bottom' }), 'minecraft:oak_slab[type=bottom,waterlogged=false]');
});

test('reconcileProgress reports complete pending unknown and mismatched steps', () => {
  const plan = compileBlueprint(createSmallShelterSpec({
    anchor: [0, 64, 0],
    dimensions: { width: 3, depth: 3, height: 2 },
  }));
  const observed = {
    '0,64,0': 'minecraft:oak_planks',
    '1,64,0': 'minecraft:air',
    '2,64,0': 'minecraft:stone',
  };

  const progress = reconcileProgress(plan, observed);
  assert.equal(progress.total, 33);
  assert.equal(progress.completeCount, 1);
  assert.equal(progress.pendingCount, 31);
  assert.equal(progress.unknownCount, 30);
  assert.equal(progress.mismatchCount, 1);
  assert.equal(progress.mismatched[0].observed.block, 'minecraft:stone');
});

test('missingMaterials reports exact item deficits', () => {
  assert.deepEqual(missingMaterials({
    'minecraft:oak_planks': 23,
    'minecraft:oak_slab': 9,
    'minecraft:torch': 1,
  }, {
    'minecraft:oak_planks': 23,
    'minecraft:oak_slab': 2,
  }), [
    { item: 'minecraft:oak_slab', required: 9, available: 2, missing: 7 },
    { item: 'minecraft:torch', required: 1, available: 0, missing: 1 },
  ]);
});

test('nextPlacementAction gates placement on materials and mismatches', () => {
  const state = startBuildGoal(createSmallShelterSpec({
    anchor: [0, 64, 0],
    dimensions: { width: 3, depth: 3, height: 2 },
  }), initialBuildState(), 1000);

  const emptyObserved = Object.fromEntries(state.plan.steps.map((step) => [step.pos.join(','), 'minecraft:air']));
  const missing = nextPlacementAction(state, emptyObserved, { 'minecraft:oak_planks': 23 });
  assert.equal(missing.reason, 'missing_materials');
  assert.deepEqual(missing.missing, [
    { item: 'minecraft:oak_slab', required: 9, available: 0, missing: 9 },
    { item: 'minecraft:torch', required: 1, available: 0, missing: 1 },
  ]);

  const mismatchObserved = { ...emptyObserved, '0,64,0': 'minecraft:stone' };
  const mismatch = nextPlacementAction(state, mismatchObserved, {
    'minecraft:oak_planks': 23,
    'minecraft:oak_slab': 9,
    'minecraft:torch': 1,
  });
  assert.equal(mismatch.reason, 'mismatch');

  const ready = nextPlacementAction(state, emptyObserved, {
    'minecraft:oak_planks': 23,
    'minecraft:oak_slab': 9,
    'minecraft:torch': 1,
  });
  assert.equal(ready.reason, 'place_next');
  assert.equal(ready.action.type, 'placeBlock');
  assert.equal(ready.action.generation, state.generation);
  assert.equal(ready.action.step.id, 'place:0:0,0,0:floor');
});

test('build cancellation increments generation and rejects stale placement actions', () => {
  const building = startBuildGoal(createSmallShelterSpec({
    anchor: [0, 64, 0],
    dimensions: { width: 3, depth: 3, height: 2 },
  }), initialBuildState(), 1000);
  const observed = Object.fromEntries(building.plan.steps.map((step) => [step.pos.join(','), 'minecraft:air']));
  const next = nextPlacementAction(building, observed, {
    'minecraft:oak_planks': 23,
    'minecraft:oak_slab': 9,
    'minecraft:torch': 1,
  });

  assert.equal(isCurrentBuildAction(building, next.action), true);
  const cancelled = cancelBuildGoal(building, 'owner_cancel', 1100);
  assert.equal(cancelled.mode, 'idle');
  assert.equal(cancelled.generation, building.generation + 1);
  assert.equal(isCurrentBuildAction(cancelled, next.action), false);
});

test('complete observed plan returns complete without another action', () => {
  const building = startBuildGoal(createSmallShelterSpec({
    anchor: [0, 64, 0],
    dimensions: { width: 3, depth: 3, height: 2 },
  }), initialBuildState(), 1000);
  const observed = Object.fromEntries(building.plan.steps.map((step) => [step.pos.join(','), {
    block: step.block,
    state: step.state,
  }]));

  const result = nextPlacementAction(building, observed, {
    'minecraft:oak_planks': 23,
    'minecraft:oak_slab': 9,
    'minecraft:torch': 1,
  });
  assert.equal(result.reason, 'complete');
  assert.equal(result.state.mode, 'idle');
  assert.equal(result.progress.done, true);
});
