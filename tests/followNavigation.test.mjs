import assert from 'node:assert/strict';
import test from 'node:test';
import {
  candidateAt,
  isHazardName,
  isPassableName,
  isSupportName,
  planFollowStep,
} from '../src/followNavigation.mjs';

function makeWorld(overrides = {}) {
  return (pos) => {
    const key = pos.join(',');
    if (Object.hasOwn(overrides, key)) return overrides[key];
    const [, y] = pos;
    return y <= 63 ? 'stone' : 'air';
  };
}

test('classifies passable hazards and supports from Scarpet block names', () => {
  assert.equal(isPassableName('air'), true);
  assert.equal(isPassableName('minecraft:air'), true);
  assert.equal(isHazardName('lava'), true);
  assert.equal(isHazardName('minecraft:powder_snow'), true);
  assert.equal(isSupportName('stone'), true);
  assert.equal(isSupportName('air'), false);
  assert.equal(isSupportName('water'), false);
});

test('candidateAt accepts one-block step up and modest safe drop', () => {
  const world = makeWorld({
    '1,64,0': 'stone',
    '1,65,0': 'air',
    '1,66,0': 'air',
    '2,63,0': 'air',
    '2,62,0': 'stone',
  });
  assert.deepEqual(candidateAt(world, 1, 64, 0).pos, [1, 65, 0]);
  assert.deepEqual(candidateAt(world, 2, 64, 0).pos, [2, 63, 0]);
});

test('planFollowStep reproduces observed ledge and chooses a safe drop when supported', () => {
  const world = makeWorld({
    '1,63,0': 'air',
    '1,62,0': 'stone',
  });
  const plan = planFollowStep(world, [0.55, 64, 0.3], [4, 64, 0], {
    minDistance: 1,
    maxRadius: 4,
  });
  assert.equal(plan.ok, true);
  assert.deepEqual(plan.next, [1, 63, 0]);
});

test('planFollowStep routes around a blocked direct path', () => {
  const world = makeWorld({
    '1,64,0': 'stone',
    '1,65,0': 'stone',
    '1,64,1': 'air',
    '1,63,1': 'stone',
    '2,64,1': 'air',
    '2,63,1': 'stone',
  });
  const plan = planFollowStep(world, [0, 64, 0], [3, 64, 0], {
    minDistance: 1,
    maxRadius: 4,
  });
  assert.equal(plan.ok, true);
  assert.notDeepEqual(plan.next, [1, 64, 0]);
  assert.equal(plan.next[2], 1);
});

test('planFollowStep refuses hazards and unsupported cliffs', () => {
  const hazardWorld = makeWorld({
    '1,64,0': 'lava',
    '1,63,0': 'stone',
  });
  const hazard = planFollowStep(hazardWorld, [0, 64, 0], [2, 64, 0], {
    minDistance: 1,
    maxRadius: 2,
  });
  assert.equal(hazard.ok, true);
  assert.notDeepEqual(hazard.next, [1, 64, 0]);

  const cliffWorld = makeWorld({
    '1,63,0': 'air',
    '1,62,0': 'air',
    '1,61,0': 'air',
    '0,63,1': 'air',
    '0,62,1': 'air',
    '0,61,1': 'air',
    '0,63,-1': 'air',
    '0,62,-1': 'air',
    '0,61,-1': 'air',
    '-1,63,0': 'air',
    '-1,62,0': 'air',
    '-1,61,0': 'air',
    '1,63,1': 'air',
    '1,62,1': 'air',
    '1,61,1': 'air',
    '1,63,-1': 'air',
    '1,62,-1': 'air',
    '1,61,-1': 'air',
    '-1,63,1': 'air',
    '-1,62,1': 'air',
    '-1,61,1': 'air',
    '-1,63,-1': 'air',
    '-1,62,-1': 'air',
    '-1,61,-1': 'air',
  });
  const cliff = planFollowStep(cliffWorld, [0, 64, 0], [2, 64, 0], {
    minDistance: 1,
    maxRadius: 1,
  });
  assert.equal(cliff.ok, false);
  assert.equal(cliff.reason, 'no_supported_route');
});

test('planFollowStep returns near_owner without movement inside minimum distance', () => {
  const plan = planFollowStep(makeWorld(), [0, 64, 0], [2, 64, 0], {
    minDistance: 3,
  });
  assert.equal(plan.ok, true);
  assert.equal(plan.reason, 'near_owner');
  assert.equal(plan.next, null);
});
