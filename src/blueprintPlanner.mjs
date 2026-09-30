export const orientations = new Set(['north', 'east', 'south', 'west']);
export const defaultMaxPlacementSteps = 256;

const namespacedIdPattern = /^[a-z0-9_.-]+:[a-z0-9_./-]+$/;
const paletteKeyPattern = /^[a-z][a-z0-9_]*$/;
const stateKeyPattern = /^[a-z0-9_]+$/;
const airBlocks = new Set(['minecraft:air', 'minecraft:cave_air', 'minecraft:void_air']);

export function normalizeAnchor(anchor) {
  if (Array.isArray(anchor) && anchor.length === 3) {
    return anchor.map((value) => normalizeInteger(value, 'anchor coordinate'));
  }
  if (anchor && typeof anchor === 'object') {
    return [
      normalizeInteger(anchor.x, 'anchor.x'),
      normalizeInteger(anchor.y, 'anchor.y'),
      normalizeInteger(anchor.z, 'anchor.z'),
    ];
  }
  throw new Error('Blueprint requires an explicit anchor [x,y,z]');
}

export function normalizeOrientation(orientation) {
  if (!orientations.has(orientation)) {
    throw new Error('Blueprint orientation must be north, east, south, or west');
  }
  return orientation;
}

function normalizeInteger(value, label) {
  const number = Number(value);
  if (!Number.isInteger(number)) throw new Error(`${label} must be an integer`);
  return number;
}

function assertNamespacedId(value, label) {
  if (typeof value !== 'string' || !namespacedIdPattern.test(value)) {
    throw new Error(`${label} must be a namespaced Minecraft id`);
  }
  return value;
}

export function normalizeBlockState(state = {}) {
  if (!state || typeof state !== 'object' || Array.isArray(state)) {
    throw new Error('Block state must be an object');
  }
  const normalized = {};
  for (const key of Object.keys(state).sort()) {
    if (!stateKeyPattern.test(key)) throw new Error(`Invalid block state key: ${key}`);
    const value = state[key];
    if (!['string', 'number', 'boolean'].includes(typeof value)) {
      throw new Error(`Invalid block state value for ${key}`);
    }
    normalized[key] = String(value);
  }
  return normalized;
}

export function normalizePalette(palette) {
  if (!palette || typeof palette !== 'object' || Array.isArray(palette)) {
    throw new Error('Blueprint requires a palette object');
  }
  const normalized = {};
  for (const key of Object.keys(palette).sort()) {
    if (!paletteKeyPattern.test(key)) throw new Error(`Invalid palette key: ${key}`);
    const entry = palette[key];
    if (!entry || typeof entry !== 'object' || Array.isArray(entry)) {
      throw new Error(`Palette entry ${key} must be an object`);
    }
    const block = assertNamespacedId(entry.block, `Palette entry ${key}.block`);
    const item = assertNamespacedId(entry.item ?? block, `Palette entry ${key}.item`);
    normalized[key] = {
      block,
      item,
      state: normalizeBlockState(entry.state ?? {}),
    };
  }
  return normalized;
}

export function defaultShelterPalette() {
  return {
    floor: { block: 'minecraft:oak_planks' },
    wall: { block: 'minecraft:oak_planks' },
    roof: { block: 'minecraft:oak_slab', state: { type: 'bottom' } },
    torch: { block: 'minecraft:torch' },
  };
}

export function createSmallShelterSpec({
  anchor,
  orientation = 'north',
  dimensions = {},
  palette = defaultShelterPalette(),
} = {}) {
  return {
    version: 1,
    kind: 'small_shelter',
    anchor: normalizeAnchor(anchor),
    orientation: normalizeOrientation(orientation),
    dimensions: {
      width: dimensions.width ?? 5,
      depth: dimensions.depth ?? 5,
      height: dimensions.height ?? 3,
    },
    palette,
  };
}

function normalizeDimensions(dimensions = {}) {
  const width = normalizeInteger(dimensions.width, 'dimensions.width');
  const depth = normalizeInteger(dimensions.depth, 'dimensions.depth');
  const height = normalizeInteger(dimensions.height, 'dimensions.height');
  if (width < 3 || depth < 3 || height < 2) {
    throw new Error('Small shelter dimensions must be at least width 3, depth 3, height 2');
  }
  if (width * depth * (height + 2) > 512) {
    throw new Error('Small shelter dimensions exceed offline planner volume limit');
  }
  return { width, depth, height };
}

export function transformLocalPosition(anchor, orientation, local) {
  const [ax, ay, az] = normalizeAnchor(anchor);
  const [lx, ly, lz] = local.map((value) => normalizeInteger(value, 'local coordinate'));
  const vectors = {
    north: { right: [1, 0], forward: [0, -1] },
    east: { right: [0, 1], forward: [1, 0] },
    south: { right: [-1, 0], forward: [0, 1] },
    west: { right: [0, -1], forward: [-1, 0] },
  }[normalizeOrientation(orientation)];
  return [
    ax + vectors.right[0] * lx + vectors.forward[0] * lz,
    ay + ly,
    az + vectors.right[1] * lx + vectors.forward[1] * lz,
  ];
}

function addStep(steps, palette, placement) {
  if (!palette[placement.palette]) throw new Error(`Unknown palette key in placement: ${placement.palette}`);
  const sequence = steps.length;
  steps.push({
    id: `place:${sequence}:${placement.local.join(',')}:${placement.palette}`,
    sequence,
    local: placement.local,
    pos: placement.pos,
    paletteKey: placement.palette,
    block: palette[placement.palette].block,
    state: palette[placement.palette].state,
    item: palette[placement.palette].item,
  });
}

function generateSmallShelterPlacements(spec, palette) {
  const { width, depth, height } = normalizeDimensions(spec.dimensions);
  const anchor = normalizeAnchor(spec.anchor);
  const orientation = normalizeOrientation(spec.orientation);
  const placements = [];
  const doorX = Math.floor(width / 2);

  for (let z = 0; z < depth; z += 1) {
    for (let x = 0; x < width; x += 1) {
      placements.push({ local: [x, 0, z], palette: 'floor' });
    }
  }

  for (let y = 1; y <= height; y += 1) {
    for (let z = 0; z < depth; z += 1) {
      for (let x = 0; x < width; x += 1) {
        const perimeter = x === 0 || z === 0 || x === width - 1 || z === depth - 1;
        const doorway = z === 0 && x === doorX && y <= 2;
        if (perimeter && !doorway) placements.push({ local: [x, y, z], palette: 'wall' });
      }
    }
  }

  for (let z = 0; z < depth; z += 1) {
    for (let x = 0; x < width; x += 1) {
      placements.push({ local: [x, height + 1, z], palette: 'roof' });
    }
  }

  placements.push({ local: [doorX, 2, 1], palette: 'torch' });

  return placements.map((placement) => ({
    ...placement,
    pos: transformLocalPosition(anchor, orientation, placement.local),
  }));
}

export function compileBlueprint(spec, options = {}) {
  if (!spec || typeof spec !== 'object') throw new Error('Blueprint spec must be an object');
  if (spec.version !== 1) throw new Error('Blueprint version must be 1');
  if (spec.kind !== 'small_shelter') throw new Error('Only small_shelter blueprints are supported locally');

  const palette = normalizePalette(spec.palette);
  const placements = generateSmallShelterPlacements(spec, palette);
  const maxSteps = options.maxSteps ?? defaultMaxPlacementSteps;
  if (placements.length > maxSteps) {
    throw new Error(`Blueprint has ${placements.length} placement steps, exceeding max ${maxSteps}`);
  }

  const steps = [];
  for (const placement of placements) addStep(steps, palette, placement);
  return {
    version: 1,
    kind: spec.kind,
    anchor: normalizeAnchor(spec.anchor),
    orientation: normalizeOrientation(spec.orientation),
    dimensions: normalizeDimensions(spec.dimensions),
    palette,
    steps,
    materialCounts: materialCounts(steps),
  };
}

export function blockKey(block, state = {}) {
  const normalizedState = normalizeBlockState(state);
  const stateText = Object.keys(normalizedState)
    .map((key) => `${key}=${normalizedState[key]}`)
    .join(',');
  return stateText ? `${block}[${stateText}]` : block;
}

export function materialCounts(steps) {
  const counts = {};
  for (const step of steps) {
    counts[step.item] = (counts[step.item] ?? 0) + 1;
  }
  return Object.fromEntries(Object.entries(counts).sort(([a], [b]) => a.localeCompare(b)));
}

function posKey(pos) {
  return pos.join(',');
}

function normalizeObservedBlock(value) {
  if (!value) return null;
  if (typeof value === 'string') return { block: value, state: {} };
  if (typeof value === 'object') {
    return {
      block: value.block ?? value.name,
      state: normalizeBlockState(value.state ?? {}),
    };
  }
  return null;
}

export function observedBlockAt(observed, pos) {
  if (!observed) return null;
  const key = posKey(pos);
  if (observed instanceof Map) return normalizeObservedBlock(observed.get(key));
  if (Array.isArray(observed)) {
    const found = observed.find((entry) => posKey(entry.pos) === key);
    return normalizeObservedBlock(found);
  }
  return normalizeObservedBlock(observed[key]);
}

export function reconcileProgress(plan, observed) {
  const complete = [];
  const pending = [];
  const mismatched = [];
  const unknown = [];

  for (const step of plan.steps) {
    const observedBlock = observedBlockAt(observed, step.pos);
    if (!observedBlock) {
      unknown.push(step);
      pending.push(step);
      continue;
    }
    if (observedBlock.block === step.block && blockKey(observedBlock.block, observedBlock.state) === blockKey(step.block, step.state)) {
      complete.push(step);
      continue;
    }
    if (airBlocks.has(observedBlock.block)) {
      pending.push(step);
      continue;
    }
    mismatched.push({ step, observed: observedBlock });
  }

  return {
    total: plan.steps.length,
    completeCount: complete.length,
    pendingCount: pending.length,
    mismatchCount: mismatched.length,
    unknownCount: unknown.length,
    complete,
    pending,
    mismatched,
    unknown,
    done: complete.length === plan.steps.length,
  };
}

export function missingMaterials(requiredCounts, inventoryCounts = {}) {
  return Object.entries(requiredCounts)
    .map(([item, required]) => ({
      item,
      required,
      available: Number(inventoryCounts[item] ?? 0),
      missing: Math.max(0, required - Number(inventoryCounts[item] ?? 0)),
    }))
    .filter((entry) => entry.missing > 0);
}

export function initialBuildState() {
  return {
    mode: 'idle',
    generation: 0,
    status: 'idle',
    plan: null,
  };
}

export function startBuildGoal(spec, state = initialBuildState(), nowMs = Date.now(), options = {}) {
  const plan = compileBlueprint(spec, options);
  return {
    mode: 'building',
    generation: (state.generation ?? 0) + 1,
    status: 'building',
    startedAtMs: nowMs,
    plan,
  };
}

export function cancelBuildGoal(state, reason = 'cancelled', nowMs = Date.now()) {
  return {
    ...state,
    mode: 'idle',
    generation: (state?.generation ?? 0) + 1,
    status: reason,
    cancelledAtMs: nowMs,
    plan: null,
  };
}

export function isCurrentBuildAction(state, action) {
  return state?.mode === 'building' && action?.generation === state.generation;
}

export function nextPlacementAction(state, observed, inventoryCounts = {}, options = {}) {
  if (!state || state.mode !== 'building' || !state.plan) {
    return { action: null, reason: 'idle', state };
  }

  const progress = reconcileProgress(state.plan, observed);
  if (progress.done) {
    return {
      action: null,
      reason: 'complete',
      state: { ...state, mode: 'idle', status: 'complete' },
      progress,
    };
  }

  if (progress.mismatched.length && !options.allowOverwrite) {
    return {
      action: null,
      reason: 'mismatch',
      state: { ...state, status: 'blocked_mismatch' },
      progress,
    };
  }

  const remainingCounts = materialCounts(progress.pending);
  const missing = missingMaterials(remainingCounts, inventoryCounts);
  if (missing.length) {
    return {
      action: null,
      reason: 'missing_materials',
      state: { ...state, status: 'missing_materials' },
      progress,
      missing,
    };
  }

  const step = progress.pending[0];
  return {
    action: {
      type: 'placeBlock',
      generation: state.generation,
      step,
    },
    reason: 'place_next',
    state,
    progress,
    missing: [],
  };
}
