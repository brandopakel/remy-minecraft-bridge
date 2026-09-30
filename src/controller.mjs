import { randomUUID } from 'node:crypto';
import { mkdir, readFile, readdir, rename, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const allowedActions = new Set(['spawn', 'status', 'look', 'move', 'stop']);
export const lookDirections = new Set(['north', 'south', 'east', 'west', 'up', 'down']);
export const moveDirections = new Set(['forward', 'backward', 'left', 'right']);
export const maxMoveDurationMs = 1000;
export const defaultCommandTtlMs = 5000;
export const maxCommandTtlMs = 10000;
export const reservedCommandFields = new Set(['id', 'action', 'createdMs', 'expiresMs']);
export const expectedBridgeVersion = '0.1.4';
export const defaultHeartbeatMaxAgeMs = 5 * 60 * 1000;
export const defaultMaxAckFiles = 64;
export const defaultMaxHeartbeatFiles = 12;

function isPathInside(child, parent) {
  const relative = path.relative(parent, child);
  return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative));
}

export function resolveWorldPath(worldPath, options = {}) {
  const resolved = path.resolve(worldPath);
  if (path.basename(path.dirname(resolved)).toLowerCase() !== 'saves') {
    throw new Error('World path must be one save directory inside a saves folder');
  }
  if (options.allowedRoot) {
    const allowedRoot = path.resolve(options.allowedRoot);
    if (!isPathInside(resolved, allowedRoot)) {
      throw new Error('World path is outside the allowed root');
    }
  }
  return resolved;
}

export function bridgeDir(worldPath, options = {}) {
  return path.join(resolveWorldPath(worldPath, options), 'scripts', 'remy_bridge.data');
}

export async function readBridgeJson(worldPath, fileName, options = {}) {
  return readJsonIfPresent(path.join(bridgeDir(worldPath, options), fileName));
}

export async function readLatestBridgeJson(worldPath, prefix, options = {}) {
  const dir = bridgeDir(worldPath, options);
  let entries = [];
  try {
    entries = await readdir(dir, { withFileTypes: true });
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw error;
  }

  const candidates = [];
  for (const entry of entries) {
    if (!entry.isFile() || !entry.name.startsWith(`${prefix}_`) || !entry.name.endsWith('.json')) continue;
    const file = path.join(dir, entry.name);
    const value = await readJsonIfPresent(file);
    if (!value) continue;
    const fileStat = await stat(file);
    candidates.push({
      value,
      sortTime: Number(value.unixMs) || fileStat.mtimeMs,
    });
  }
  candidates.sort((a, b) => b.sortTime - a.sortTime);
  return candidates[0]?.value ?? null;
}

export async function pruneBridgeFiles(worldPath, prefix, maxFiles, options = {}) {
  const dir = bridgeDir(worldPath, options);
  let entries = [];
  try {
    entries = await readdir(dir, { withFileTypes: true });
  } catch (error) {
    if (error.code === 'ENOENT') return 0;
    throw error;
  }

  const candidates = [];
  for (const entry of entries) {
    if (!entry.isFile() || !entry.name.startsWith(`${prefix}_`) || !entry.name.endsWith('.json')) continue;
    const file = path.join(dir, entry.name);
    const fileStat = await stat(file);
    candidates.push({ file, mtimeMs: fileStat.mtimeMs });
  }

  candidates.sort((a, b) => b.mtimeMs - a.mtimeMs);
  let removed = 0;
  for (const candidate of candidates.slice(maxFiles)) {
    try {
      await rm(candidate.file, { force: true });
      removed += 1;
    } catch (error) {
      if (error.code !== 'ENOENT' && error.code !== 'EPERM' && error.code !== 'EBUSY') throw error;
    }
  }
  return removed;
}

export function buildCommand(action, fields = {}, options = {}) {
  if (!allowedActions.has(action)) throw new Error(`Action not allowlisted: ${action}`);

  for (const key of Object.keys(fields)) {
    if (reservedCommandFields.has(key)) {
      throw new Error(`Field is reserved and cannot be overridden: ${key}`);
    }
  }

  const allowedFieldsByAction = {
    spawn: new Set(),
    status: new Set(),
    look: new Set(['direction']),
    move: new Set(['direction', 'durationMs']),
    stop: new Set(),
  };
  for (const key of Object.keys(fields)) {
    if (!allowedFieldsByAction[action].has(key)) {
      throw new Error(`Field is not valid for ${action}: ${key}`);
    }
  }

  if (action === 'look') {
    if (!lookDirections.has(fields.direction)) throw new Error('Invalid look direction');
  }
  if (action === 'move') {
    if (!moveDirections.has(fields.direction)) throw new Error('Invalid move direction');
  }

  const ttlMs = options.ttlMs ?? defaultCommandTtlMs;
  if (!Number.isFinite(ttlMs) || ttlMs <= 0 || ttlMs > maxCommandTtlMs) {
    throw new Error(`Command ttlMs must be between 1 and ${maxCommandTtlMs}`);
  }

  const now = options.now ?? Date.now();
  const id = options.id ?? randomUUID();
  const command = {
    id,
    action,
    createdMs: now,
    expiresMs: now + ttlMs,
    ...fields,
  };

  if (action === 'move') {
    command.durationMs = Math.max(1, Math.min(Number(command.durationMs || 400), maxMoveDurationMs));
  }

  return command;
}

export function assertSuccessfulAck(ack) {
  if (ack?.status !== 'ok') {
    throw new Error(`Command ${ack?.action ?? 'unknown'} was ${ack?.status ?? 'not acknowledged'}: ${JSON.stringify(ack?.detail ?? null)}`);
  }
  if (ack.detail?.success === 0 || ack.detail?.success === false) {
    throw new Error(`Command ${ack.action} failed: ${JSON.stringify(ack.detail)}`);
  }
  return ack;
}

export function assertFreshHeartbeat(heartbeat, worldPath, options = {}) {
  if (!heartbeat) throw new Error('Missing remy_bridge heartbeat; reload the deployed script before live control');
  const expectedVersion = options.expectedVersion ?? expectedBridgeVersion;
  if (heartbeat.bridgeVersion !== expectedVersion) {
    throw new Error(`remy_bridge heartbeat version ${heartbeat.bridgeVersion ?? 'unknown'} does not match expected ${expectedVersion}`);
  }
  const expectedWorldFolder = path.basename(resolveWorldPath(worldPath, options));
  if (heartbeat.worldFolder !== expectedWorldFolder) {
    throw new Error(`remy_bridge heartbeat world ${heartbeat.worldFolder ?? 'unknown'} does not match ${expectedWorldFolder}`);
  }
  const now = options.now ?? Date.now();
  const maxAgeMs = options.maxAgeMs ?? defaultHeartbeatMaxAgeMs;
  if (!Number.isFinite(Number(heartbeat.unixMs)) || now - Number(heartbeat.unixMs) > maxAgeMs) {
    throw new Error('remy_bridge heartbeat is stale; reload the deployed script before live control');
  }
  return heartbeat;
}

export async function requireFreshHeartbeat(worldPath, options = {}) {
  const heartbeat = await readLatestBridgeJson(worldPath, 'heartbeat', options);
  await pruneBridgeFiles(worldPath, 'heartbeat', options.maxHeartbeatFiles ?? defaultMaxHeartbeatFiles, options);
  return assertFreshHeartbeat(heartbeat, worldPath, options);
}

export async function readJsonIfPresent(file) {
  try {
    return JSON.parse(await readFile(file, 'utf8'));
  } catch (error) {
    if (error.code === 'ENOENT' || error instanceof SyntaxError) return null;
    throw error;
  }
}

export async function writeJsonAtomic(file, value) {
  await mkdir(path.dirname(file), { recursive: true });
  const tmp = `${file}.${process.pid}.${Date.now()}.tmp`;
  await writeFile(tmp, JSON.stringify(value, null, 2), 'utf8');
  await rename(tmp, file);
}

export async function sendCommand(worldPath, action, fields = {}, options = {}) {
  const dir = bridgeDir(worldPath, options);
  const commandPath = path.join(dir, 'command.json');
  const command = buildCommand(action, fields, options);
  const ackPath = path.join(dir, `ack_${command.id}.json`);

  await mkdir(dir, { recursive: true });
  await pruneBridgeFiles(worldPath, 'ack', options.maxAckFiles ?? defaultMaxAckFiles, options);
  try {
    await stat(ackPath);
    throw new Error(`Refusing to reuse existing ack file for command id ${command.id}`);
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  await writeJsonAtomic(commandPath, command);

  const timeoutMs = options.timeoutMs ?? 10000;
  const pollIntervalMs = options.pollIntervalMs ?? 100;
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const ack = await readJsonIfPresent(ackPath);
    if (ack?.id === command.id) {
      const result = options.allowFailureAck ? ack : assertSuccessfulAck(ack);
      await pruneBridgeFiles(worldPath, 'ack', options.maxAckFiles ?? defaultMaxAckFiles, options);
      return result;
    }
    await new Promise((resolve) => setTimeout(resolve, pollIntervalMs));
  }
  throw new Error(`Timed out waiting for ack for ${action}; is remy_bridge.sc loaded in the world?`);
}

export async function runSmoke(worldPath, options = {}) {
  const commandOptions = options.commandOptions ?? {};
  const waitAfterMoveMs = options.waitAfterMoveMs ?? 700;
  await requireFreshHeartbeat(worldPath, options.heartbeatOptions ?? {});
  const results = [];
  results.push(await sendCommand(worldPath, 'spawn', {}, commandOptions));
  results.push(await sendCommand(worldPath, 'status', {}, commandOptions));
  results.push(await sendCommand(worldPath, 'look', { direction: 'east' }, commandOptions));
  let moveIssued = false;
  try {
    moveIssued = true;
    results.push(await sendCommand(worldPath, 'move', { direction: 'forward', durationMs: 400 }, commandOptions));
    await new Promise((resolve) => setTimeout(resolve, waitAfterMoveMs));
  } finally {
    if (moveIssued) {
      results.push(await sendCommand(worldPath, 'stop', {}, commandOptions));
    }
  }
  results.push(await sendCommand(worldPath, 'status', {}, commandOptions));
  return results;
}

function usage() {
  console.log(`Usage:
  node src/controller.mjs --world <save-path> status
  node src/controller.mjs --world <save-path> spawn
  node src/controller.mjs --world <save-path> look <north|south|east|west|up|down>
  node src/controller.mjs --world <save-path> move <forward|backward|left|right> [durationMs<=1000]
  node src/controller.mjs --world <save-path> stop
  node src/controller.mjs --world <save-path> smoke`);
}

function parseArgs(argv) {
  const args = [...argv];
  const worldFlag = args.indexOf('--world');
  if (worldFlag === -1 || !args[worldFlag + 1]) {
    throw new Error('Missing --world <save-path>');
  }
  const worldPath = path.resolve(args[worldFlag + 1]);
  args.splice(worldFlag, 2);
  const action = args.shift();
  if (!action) throw new Error('Missing action');
  return { worldPath, action, rest: args };
}

export async function main(argv = process.argv.slice(2)) {
  const { worldPath, action, rest } = parseArgs(argv);

  if (action === 'smoke') {
    console.log(JSON.stringify(await runSmoke(worldPath), null, 2));
    return;
  }

  if (action === 'look') {
    await requireFreshHeartbeat(worldPath);
    console.log(JSON.stringify(await sendCommand(worldPath, action, { direction: rest[0] }), null, 2));
    return;
  }

  if (action === 'move') {
    await requireFreshHeartbeat(worldPath);
    console.log(JSON.stringify(await sendCommand(worldPath, action, {
      direction: rest[0],
      durationMs: rest[1] ? Number(rest[1]) : 400,
    }), null, 2));
    return;
  }

  if (action === 'spawn') {
    await requireFreshHeartbeat(worldPath);
  }

  console.log(JSON.stringify(await sendCommand(worldPath, action), null, 2));
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main().catch((error) => {
    console.error(error.message);
    usage();
    process.exitCode = 1;
  });
}
