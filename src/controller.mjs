import { randomUUID } from 'node:crypto';
import { mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const allowedActions = new Set(['spawn', 'status', 'look', 'move', 'stop']);
export const lookDirections = new Set(['north', 'south', 'east', 'west', 'up', 'down']);
export const moveDirections = new Set(['forward', 'backward', 'left', 'right']);
export const maxMoveDurationMs = 1000;
export const defaultCommandTtlMs = 5000;
export const maxCommandTtlMs = 10000;

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

export function buildCommand(action, fields = {}, options = {}) {
  if (!allowedActions.has(action)) throw new Error(`Action not allowlisted: ${action}`);
  if (action === 'look' && !lookDirections.has(fields.direction)) {
    throw new Error('Invalid look direction');
  }
  if (action === 'move' && !moveDirections.has(fields.direction)) {
    throw new Error('Invalid move direction');
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
  const ackPath = path.join(dir, 'ack.json');
  const command = buildCommand(action, fields, options);

  await mkdir(dir, { recursive: true });
  await rm(ackPath, { force: true });
  await writeJsonAtomic(commandPath, command);

  const timeoutMs = options.timeoutMs ?? 10000;
  const pollIntervalMs = options.pollIntervalMs ?? 100;
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const ack = await readJsonIfPresent(ackPath);
    if (ack?.id === command.id) return ack;
    await new Promise((resolve) => setTimeout(resolve, pollIntervalMs));
  }
  throw new Error(`Timed out waiting for ack for ${action}; is remy_bridge.sc loaded in the world?`);
}

export async function runSmoke(worldPath) {
  const results = [];
  results.push(await sendCommand(worldPath, 'spawn'));
  results.push(await sendCommand(worldPath, 'status'));
  results.push(await sendCommand(worldPath, 'look', { direction: 'east' }));
  let moveIssued = false;
  try {
    results.push(await sendCommand(worldPath, 'move', { direction: 'forward', durationMs: 400 }));
    moveIssued = true;
    await new Promise((resolve) => setTimeout(resolve, 700));
  } finally {
    if (moveIssued) {
      results.push(await sendCommand(worldPath, 'stop'));
    }
  }
  results.push(await sendCommand(worldPath, 'status'));
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
    console.log(JSON.stringify(await sendCommand(worldPath, action, { direction: rest[0] }), null, 2));
    return;
  }

  if (action === 'move') {
    console.log(JSON.stringify(await sendCommand(worldPath, action, {
      direction: rest[0],
      durationMs: rest[1] ? Number(rest[1]) : 400,
    }), null, 2));
    return;
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
