import assert from 'node:assert/strict';
import test from 'node:test';
import {
  applyGuardCommand,
  initialGuardState,
  parseOwnerCommand,
  selectGuardTarget,
  stepGuardCombat,
} from '../src/guardPrimitives.mjs';

function basePerception(overrides = {}) {
  return {
    owner: {
      id: 'owner',
      name: 'Owner',
      online: true,
      dimension: 'minecraft:overworld',
    },
    remy: {
      id: 'remy',
      online: true,
      fake: true,
      dimension: 'minecraft:overworld',
      distanceToOwner: 3,
    },
    entities: [],
    ...overrides,
  };
}

function hostile(id, fields = {}) {
  return {
    id,
    type: 'minecraft:zombie',
    category: 'hostile',
    hostile: true,
    alive: true,
    dimension: 'minecraft:overworld',
    distanceToOwner: 4,
    distanceToRemy: 3,
    traceEntityId: id,
    ...fields,
  };
}

test('parseOwnerCommand accepts only configured owner chat or slash commands', () => {
  assert.equal(parseOwnerCommand('remy guard', { speakerName: 'Other', ownerName: 'Owner' }), null);
  assert.deepEqual(parseOwnerCommand('remy guard', { speakerName: 'Owner', ownerName: 'Owner' }), {
    type: 'guard',
    policy: 'nearest_hostile',
  });
  assert.deepEqual(parseOwnerCommand('/remy_bridge stop', { speakerName: 'Owner', ownerName: 'Owner' }), {
    type: 'stop',
    reason: 'owner_command',
  });
  assert.deepEqual(parseOwnerCommand('remy fight nearest hostile', { speakerName: 'Owner', ownerName: 'Owner' }), {
    type: 'fight',
    policy: 'nearest_hostile',
    targetId: null,
  });
  assert.deepEqual(parseOwnerCommand('remy fight minecraft:zombie-2', { speakerName: 'Owner', ownerName: 'Owner' }), {
    type: 'fight',
    policy: 'explicit_target',
    targetId: 'minecraft:zombie-2',
  });
});

test('stop command cancels active combat generation before any later tick can act', () => {
  const guarding = applyGuardCommand(initialGuardState(), { type: 'guard' }, 1000);
  const stopped = applyGuardCommand(guarding, { type: 'stop', reason: 'owner_stop' }, 1100);
  assert.equal(stopped.mode, 'idle');
  assert.equal(stopped.generation, guarding.generation + 1);
  assert.equal(stopped.targetId, null);
  assert.equal(stopped.cooldownUntilMs, 0);

  const stepped = stepGuardCombat(stopped, basePerception({
    entities: [hostile('zombie-1')],
  }), 1200);
  assert.equal(stepped.reason, 'idle');
  assert.deepEqual(stepped.actions, []);
});

test('guard target selection chooses nearest allowed hostile and excludes protected categories', () => {
  const selected = selectGuardTarget(basePerception({
    entities: [
      hostile('passive-cow', { category: 'passive', hostile: false, kind: 'passive', distanceToOwner: 1 }),
      hostile('player-1', { category: 'hostile', kind: 'player', distanceToOwner: 2 }),
      hostile('pet-1', { category: 'hostile', kind: 'pet', distanceToOwner: 3 }),
      hostile('zombie-far', { distanceToOwner: 8 }),
      hostile('skeleton-near', { type: 'minecraft:skeleton', distanceToOwner: 5 }),
    ],
  }));
  assert.equal(selected.id, 'skeleton-near');
});

test('explicit fight stops when target disappears', () => {
  const fighting = applyGuardCommand(initialGuardState(), {
    type: 'fight',
    policy: 'explicit_target',
    targetId: 'zombie-1',
  }, 1000);

  const result = stepGuardCombat(fighting, basePerception({ entities: [] }), 1200);
  assert.equal(result.reason, 'target_missing');
  assert.equal(result.state.mode, 'idle');
  assert.deepEqual(result.actions, [{ type: 'stopMovement', reason: 'target_missing' }]);
});

test('guard remains active without a target but does not attack', () => {
  const guarding = applyGuardCommand(initialGuardState(), { type: 'guard' }, 1000);
  const result = stepGuardCombat(guarding, basePerception({ entities: [] }), 1200);
  assert.equal(result.state.mode, 'guard');
  assert.equal(result.state.status, 'guarding_no_target');
  assert.deepEqual(result.actions, []);
});

test('owner logout, dimension mismatch, and non-fake remy stop combat immediately', () => {
  const guarding = applyGuardCommand(initialGuardState(), { type: 'guard' }, 1000);
  assert.equal(stepGuardCombat(guarding, basePerception({
    owner: { id: 'owner', online: false, dimension: 'minecraft:overworld' },
    entities: [hostile('zombie-1')],
  }), 1200).reason, 'owner_offline');
  assert.equal(stepGuardCombat(guarding, basePerception({
    owner: { id: 'owner', online: true, dimension: 'minecraft:nether' },
    entities: [hostile('zombie-1')],
  }), 1200).reason, 'different_dimension');
  assert.equal(stepGuardCombat(guarding, basePerception({
    remy: { id: 'remy', online: true, fake: false, dimension: 'minecraft:overworld', distanceToOwner: 3 },
    entities: [hostile('zombie-1')],
  }), 1200).reason, 'remy_not_fake');
});

test('attack-once action obeys cooldown and never emits continuous attack intent', () => {
  const fighting = applyGuardCommand(initialGuardState(), {
    type: 'fight',
    policy: 'explicit_target',
    targetId: 'zombie-1',
  }, 1000);
  const perception = basePerception({ entities: [hostile('zombie-1')] });

  const first = stepGuardCombat(fighting, perception, 1200, { attackCooldownMs: 650 });
  assert.equal(first.reason, 'attack_once');
  assert.deepEqual(first.actions.map((action) => action.type), ['lookAt', 'attackOnce']);
  assert.equal(first.state.cooldownUntilMs, 1850);

  const second = stepGuardCombat(first.state, perception, 1300, { attackCooldownMs: 650 });
  assert.equal(second.reason, 'cooldown');
  assert.deepEqual(second.actions.map((action) => action.type), ['lookAt']);

  const third = stepGuardCombat(second.state, perception, 1900, { attackCooldownMs: 650 });
  assert.equal(third.reason, 'attack_once');
  assert.deepEqual(third.actions.map((action) => action.type), ['lookAt', 'attackOnce']);
});

test('trace mismatch and reach checks block attacks', () => {
  const fighting = applyGuardCommand(initialGuardState(), {
    type: 'fight',
    policy: 'explicit_target',
    targetId: 'zombie-1',
  }, 1000);

  const traceMismatch = stepGuardCombat(fighting, basePerception({
    entities: [hostile('zombie-1', { traceEntityId: 'block-1' })],
  }), 1200);
  assert.equal(traceMismatch.reason, 'trace_mismatch');
  assert.deepEqual(traceMismatch.actions.map((action) => action.type), ['lookAt']);

  const outOfReach = stepGuardCombat(fighting, basePerception({
    entities: [hostile('zombie-1', { distanceToRemy: 7 })],
  }), 1200);
  assert.equal(outOfReach.reason, 'out_of_reach');
  assert.deepEqual(outOfReach.actions.map((action) => action.type), ['lookAt', 'approachTarget']);
});
