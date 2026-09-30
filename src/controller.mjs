import { randomUUID } from 'node:crypto';
import { mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const allowedActions = new Set(['spawn', 'status', 'look', 'move', 'stop']);
export const lookDirections = new Set(['north', 'south', 'east', 'west', 'up', 'down']);
export const moveDirections = new Set(['forward', 'backward', 'left', 'right']);

export function bridgeDir(worldPath) {
  return path.join(path.resolve(worldPath), 'scripts', 'remy_bridge.data');
}

export function buildCommand(action, fields = {}, options = {}) {
  if (!allowedActions.has(action)) throw new Error(`Action not allowlisted: ${action}`);
  if (action === 'look' && !lookDirections.has(fields.direction)) {
    throw new Error('Invalid look direction');
  }
  if (action === 'move' && !moveDirections.has(fields.direction)) {
    throw new Error('Invalid move direction');
  }

  const now = options.now ?? Date.now();
  const id = options.id ?? randomUUID();
  const command = {
    id,
    action,
    createdMs: now,
    expiresMs: now + (options.ttlMs ?? 5000),
    ...fields,
  };

  if (action === 'move') {
    command.durationMs = Math.max(1, Math.min(Number(command.durationMs || 400), 1000));
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
  const dir = bridgeDir(worldPath);
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
  results.push(await sendCommand(worldPath, 'move', { direction: 'forward', durationMs: 400 }));
  await new Promise((resolve) => setTimeout(resolve, 700));
  results.push(await sendCommand(worldPath, 'stop'));
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
