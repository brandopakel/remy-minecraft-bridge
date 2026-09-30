export const passableNames = new Set([
  'air',
  'minecraft:air',
  'cave_air',
  'minecraft:cave_air',
  'void_air',
  'minecraft:void_air',
  'grass',
  'minecraft:grass',
  'tall_grass',
  'minecraft:tall_grass',
  'fern',
  'minecraft:fern',
  'large_fern',
  'minecraft:large_fern',
  'snow',
  'minecraft:snow',
]);

export const hazardNames = new Set([
  'lava',
  'minecraft:lava',
  'fire',
  'minecraft:fire',
  'soul_fire',
  'minecraft:soul_fire',
  'magma_block',
  'minecraft:magma_block',
  'cactus',
  'minecraft:cactus',
  'campfire',
  'minecraft:campfire',
  'soul_campfire',
  'minecraft:soul_campfire',
  'sweet_berry_bush',
  'minecraft:sweet_berry_bush',
  'powder_snow',
  'minecraft:powder_snow',
  'water',
  'minecraft:water',
]);

export function blockNameAt(world, pos) {
  const key = pos.join(',');
  const value = typeof world === 'function' ? world(pos) : world[key];
  if (!value) return null;
  if (typeof value === 'string') return value;
  return value.name ?? value.block ?? null;
}

export function isPassableName(name) {
  return passableNames.has(name);
}

export function isHazardName(name) {
  return hazardNames.has(name);
}

export function isSupportName(name) {
  return Boolean(name) && !isPassableName(name) && !isHazardName(name);
}

export function flatDistance(a, b) {
  const dx = b[0] - a[0];
  const dz = b[2] - a[2];
  return Math.hypot(dx, dz);
}

export function candidateAt(world, x, baseY, z, options = {}) {
  const maxDrop = options.maxDrop ?? 2;
  const levels = [baseY, baseY + 1];
  for (let drop = 1; drop <= maxDrop; drop += 1) levels.push(baseY - drop);

  for (const y of levels) {
    const foot = blockNameAt(world, [x, y, z]);
    const head = blockNameAt(world, [x, y + 1, z]);
    const below = blockNameAt(world, [x, y - 1, z]);
    if ([foot, head, below].some(isHazardName)) {
      continue;
    }
    if (isPassableName(foot) && isPassableName(head) && isSupportName(below)) {
      return {
        ok: true,
        pos: [x, y, z],
        foot,
        head,
        below,
      };
    }
  }

  return { ok: false, reason: 'unsupported' };
}

function posKey(pos) {
  return pos.join(',');
}

function floorPos(pos) {
  return pos.map((value) => Math.floor(Number(value)));
}

export function planFollowStep(world, remyPos, ownerPos, options = {}) {
  const minDistance = options.minDistance ?? 3;
  const maxRadius = options.maxRadius ?? 4;
  const maxNodes = options.maxNodes ?? 80;
  const maxDrop = options.maxDrop ?? 2;
  const start = floorPos(remyPos);
  const owner = floorPos(ownerPos);
  const startDistance = flatDistance(start, owner);

  if (startDistance <= minDistance) {
    return {
      ok: true,
      reason: 'near_owner',
      next: null,
      distance: startDistance,
      visited: 1,
    };
  }

  const directions = [
    [1, 0],
    [-1, 0],
    [0, 1],
    [0, -1],
    [1, 1],
    [1, -1],
    [-1, 1],
    [-1, -1],
  ].sort((a, b) => (
    flatDistance([start[0] + a[0], start[1], start[2] + a[1]], owner)
    - flatDistance([start[0] + b[0], start[1], start[2] + b[1]], owner)
  ));

  const queue = [{ pos: start, first: null, depth: 0 }];
  const visited = new Set([posKey(start)]);
  let best = null;
  let bestScore = Number.POSITIVE_INFINITY;

  for (let cursor = 0; cursor < queue.length && cursor < maxNodes; cursor += 1) {
    const node = queue[cursor];
    for (const [dx, dz] of directions) {
      const x = node.pos[0] + dx;
      const z = node.pos[2] + dz;
      if (Math.abs(x - start[0]) > maxRadius || Math.abs(z - start[2]) > maxRadius) continue;

      const candidate = candidateAt(world, x, node.pos[1], z, { maxDrop });
      if (!candidate.ok) continue;

      const key = posKey(candidate.pos);
      if (visited.has(key)) continue;
      visited.add(key);

      const first = node.first ?? candidate.pos;
      const depth = node.depth + 1;
      const distance = flatDistance(candidate.pos, owner);
      const score = distance + depth * 0.15;
      if (score < bestScore) {
        bestScore = score;
        best = {
          ok: true,
          reason: 'best_effort',
          next: first,
          target: candidate.pos,
          distance,
          depth,
          visited: visited.size,
        };
      }

      if (distance <= minDistance) {
        return {
          ok: true,
          reason: 'route',
          next: first,
          target: candidate.pos,
          distance,
          depth,
          visited: visited.size,
        };
      }

      if (depth < maxRadius) {
        queue.push({ pos: candidate.pos, first, depth });
      }
    }
  }

  if (best && best.distance <= startDistance + 1.5) {
    return best;
  }

  return {
    ok: false,
    reason: 'no_supported_route',
    distance: startDistance,
    visited: visited.size,
  };
}
