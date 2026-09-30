export const defaultAttackReach = 4.5;
export const defaultAttackCooldownMs = 650;
export const defaultGuardRadius = 12;
export const defaultMaxOwnerDistance = 24;

const stopAliases = new Set(['stop', 'guard off', 'guard stop', 'fight stop']);
const guardAliases = new Set(['guard', 'guard on', 'guard me']);
const statusAliases = new Set(['status']);

export function initialGuardState() {
  return {
    mode: 'idle',
    generation: 0,
    targetId: null,
    targetPolicy: null,
    cooldownUntilMs: 0,
    status: 'idle',
  };
}

export function parseOwnerCommand(message, { speakerName, ownerName } = {}) {
  if (!ownerName || speakerName !== ownerName || typeof message !== 'string') return null;

  const normalized = message.trim().replace(/\s+/g, ' ').toLowerCase();
  let body = null;
  if (normalized.startsWith('remy ')) body = normalized.slice('remy '.length);
  if (normalized.startsWith('/remy_bridge ')) body = normalized.slice('/remy_bridge '.length);
  if (!body) return null;

  if (stopAliases.has(body)) return { type: 'stop', reason: 'owner_command' };
  if (guardAliases.has(body)) return { type: 'guard', policy: 'nearest_hostile' };
  if (statusAliases.has(body)) return { type: 'status' };

  if (body === 'fight' || body === 'fight nearest' || body === 'fight nearest hostile') {
    return { type: 'fight', policy: 'nearest_hostile', targetId: null };
  }

  const fightTarget = body.match(/^fight ([a-z0-9_:\-.]+)$/);
  if (fightTarget) {
    return { type: 'fight', policy: 'explicit_target', targetId: fightTarget[1] };
  }

  return null;
}

export function applyGuardCommand(state, command, nowMs = Date.now()) {
  const current = state ?? initialGuardState();
  if (!command) return current;

  if (command.type === 'stop') {
    return {
      ...current,
      mode: 'idle',
      generation: current.generation + 1,
      targetId: null,
      targetPolicy: null,
      cooldownUntilMs: 0,
      status: command.reason ?? 'stopped',
      stoppedAtMs: nowMs,
    };
  }

  if (command.type === 'guard') {
    return {
      ...current,
      mode: 'guard',
      generation: current.generation + 1,
      targetId: null,
      targetPolicy: command.policy ?? 'nearest_hostile',
      status: 'guarding',
      startedAtMs: nowMs,
    };
  }

  if (command.type === 'fight') {
    return {
      ...current,
      mode: 'fight',
      generation: current.generation + 1,
      targetId: command.targetId ?? null,
      targetPolicy: command.policy ?? (command.targetId ? 'explicit_target' : 'nearest_hostile'),
      status: 'fighting',
      startedAtMs: nowMs,
    };
  }

  return current;
}

export function isAllowedCombatTarget(entity, options = {}) {
  if (!entity || entity.alive === false) return false;
  if (entity.id === options.ownerId || entity.id === options.remyId) return false;
  if (entity.kind === 'player' && !options.allowPlayers) return false;
  if (entity.kind === 'pet' && !options.allowPets) return false;
  if (entity.category === 'passive' && !options.allowPassive) return false;
  return entity.category === 'hostile' || entity.hostile === true || options.allowAnyHostileMarkedTarget === true;
}

export function selectGuardTarget(perception, options = {}) {
  const entities = perception?.entities ?? [];
  const ownerDimension = perception?.owner?.dimension;
  const guardRadius = options.guardRadius ?? defaultGuardRadius;
  const candidates = entities
    .filter((entity) => isAllowedCombatTarget(entity, {
      ...options,
      ownerId: perception?.owner?.id,
      remyId: perception?.remy?.id,
    }))
    .filter((entity) => !ownerDimension || entity.dimension === ownerDimension)
    .filter((entity) => Number.isFinite(entity.distanceToOwner) && entity.distanceToOwner <= guardRadius)
    .sort((a, b) => a.distanceToOwner - b.distanceToOwner);

  return candidates[0] ?? null;
}

function findTarget(state, perception, options) {
  if (state.targetPolicy === 'explicit_target' && state.targetId) {
    return (perception?.entities ?? []).find((entity) => entity.id === state.targetId) ?? null;
  }
  return selectGuardTarget(perception, options);
}

function stopResult(state, reason, nowMs) {
  return {
    state: applyGuardCommand(state, { type: 'stop', reason }, nowMs),
    actions: [{ type: 'stopMovement', reason }],
    reason,
  };
}

function hasAttackTrace(target, perception) {
  if (target.traceEntityId) return target.traceEntityId === target.id;
  if (perception?.remy?.traceEntityId) return perception.remy.traceEntityId === target.id;
  return target.traceOk === true;
}

export function stepGuardCombat(state, perception, nowMs = Date.now(), options = {}) {
  const current = state ?? initialGuardState();
  if (current.mode === 'idle') {
    return { state: current, actions: [], reason: 'idle' };
  }

  const owner = perception?.owner;
  const remy = perception?.remy;
  if (!owner || owner.online === false) return stopResult(current, 'owner_offline', nowMs);
  if (!remy || remy.online === false) return stopResult(current, 'remy_offline', nowMs);
  if (remy.fake === false) return stopResult(current, 'remy_not_fake', nowMs);
  if (owner.dimension && remy.dimension && owner.dimension !== remy.dimension) {
    return stopResult(current, 'different_dimension', nowMs);
  }
  if (Number.isFinite(remy.distanceToOwner) && remy.distanceToOwner > (options.maxOwnerDistance ?? defaultMaxOwnerDistance)) {
    return stopResult(current, 'owner_too_far', nowMs);
  }

  const target = findTarget(current, perception, options);
  if (!target) {
    if (current.mode === 'guard') {
      return {
        state: { ...current, status: 'guarding_no_target' },
        actions: [],
        reason: 'no_target',
      };
    }
    return stopResult(current, 'target_missing', nowMs);
  }

  if (target.alive === false) return stopResult(current, 'target_missing', nowMs);
  if (owner.dimension && target.dimension && target.dimension !== owner.dimension) {
    return stopResult(current, 'target_dimension_mismatch', nowMs);
  }
  if (!isAllowedCombatTarget(target, {
    ...options,
    ownerId: owner.id,
    remyId: remy.id,
  })) {
    return stopResult(current, 'target_not_allowed', nowMs);
  }

  const reach = options.attackReach ?? defaultAttackReach;
  const distanceToRemy = target.distanceToRemy ?? Number.POSITIVE_INFINITY;
  const baseActions = [{ type: 'lookAt', targetId: target.id }];
  if (distanceToRemy > reach) {
    return {
      state: { ...current, targetId: target.id, status: 'approach_needed' },
      actions: [...baseActions, { type: 'approachTarget', targetId: target.id }],
      target,
      reason: 'out_of_reach',
    };
  }

  if (!hasAttackTrace(target, perception)) {
    return {
      state: { ...current, targetId: target.id, status: 'trace_mismatch' },
      actions: baseActions,
      target,
      reason: 'trace_mismatch',
    };
  }

  if (nowMs < current.cooldownUntilMs) {
    return {
      state: { ...current, targetId: target.id, status: 'attack_cooldown' },
      actions: baseActions,
      target,
      reason: 'cooldown',
    };
  }

  return {
    state: {
      ...current,
      targetId: target.id,
      cooldownUntilMs: nowMs + (options.attackCooldownMs ?? defaultAttackCooldownMs),
      status: 'attacked_once',
    },
    actions: [...baseActions, { type: 'attackOnce', targetId: target.id }],
    target,
    reason: 'attack_once',
  };
}
