import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {
  bridgeDir,
  buildCommand,
  maxCommandTtlMs,
  readJsonIfPresent,
  resolveWorldPath,
  runSmoke,
  sendCommand,
} from '../src/controller.mjs';

async function makeWorld() {
  const root = await mkdtemp(path.join(os.tmpdir(), 'remy-bridge-test-'));
  const world = path.join(root, 'saves', 'New World - Remy LAN Test');
  await mkdir(world, { recursive: true });
  return { root, world };
}

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

test('buildCommand rejects non-allowlisted actions', () => {
  assert.throws(() => buildCommand('attack'), /not allowlisted/);
  for (const action of ['mine', 'build', 'use', 'drop', 'hotbar']) {
    assert.throws(() => buildCommand(action), /not allowlisted/);
  }
});

test('buildCommand validates look directions', () => {
  assert.throws(() => buildCommand('look', { direction: 'northwest' }), /Invalid look direction/);
  assert.equal(buildCommand('look', { direction: 'north' }, { now: 1000, id: 'x' }).action, 'look');
});

test('buildCommand caps move duration', () => {
  const command = buildCommand('move', { direction: 'forward', durationMs: 5000 }, { now: 1000, id: 'x' });
  assert.equal(command.durationMs, 1000);
  const lowCommand = buildCommand('move', { direction: 'forward', durationMs: -5 }, { now: 1000, id: 'x' });
  assert.equal(lowCommand.durationMs, 1);
});

test('buildCommand rejects bad move directions and unsafe ttls', () => {
  assert.throws(() => buildCommand('move', { direction: 'jump' }), /Invalid move direction/);
  assert.throws(() => buildCommand('status', {}, { ttlMs: 0 }), /ttlMs/);
  assert.throws(() => buildCommand('status', {}, { ttlMs: maxCommandTtlMs + 1 }), /ttlMs/);
  const command = buildCommand('status', {}, { now: 1000, id: 'ttl', ttlMs: 250 });
  assert.equal(command.expiresMs, 1250);
});

test('resolveWorldPath confines writes to save directories', async () => {
  const { root, world } = await makeWorld();
  try {
    assert.equal(resolveWorldPath(world, { allowedRoot: root }), path.resolve(world));
    assert.throws(() => bridgeDir(root), /save directory/);
    assert.throws(() => resolveWorldPath(world, { allowedRoot: path.join(root, 'other') }), /outside/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('sendCommand writes command and waits for matching ack', async () => {
  const { root, world } = await makeWorld();
  try {
    const pending = sendCommand(world, 'status', {}, {
      id: 'status-1',
      now: 1000,
      ttlMs: 5000,
      timeoutMs: 1000,
      pollIntervalMs: 20,
    });

    const commandPath = path.join(bridgeDir(world), 'command.json');
    let command = null;
    for (let i = 0; i < 25; i += 1) {
      try {
        command = JSON.parse(await readFile(commandPath, 'utf8'));
        break;
      } catch {
        await new Promise((resolve) => setTimeout(resolve, 20));
      }
    }

    assert.equal(command.id, 'status-1');
    assert.equal(command.action, 'status');

    const ackPath = path.join(bridgeDir(world), 'ack.json');
    await writeFile(ackPath, JSON.stringify({ id: 'status-1', status: 'ok' }), 'utf8');
    const ack = await pending;
    assert.equal(ack.status, 'ok');
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('sendCommand ignores replayed and malformed acknowledgments', async () => {
  const { root, world } = await makeWorld();
  try {
    const pending = sendCommand(world, 'status', {}, {
      id: 'fresh-command',
      now: 1000,
      ttlMs: 5000,
      timeoutMs: 1000,
      pollIntervalMs: 20,
    });
    let settled = false;
    const observed = pending.then((value) => {
      settled = true;
      return value;
    });

    const commandPath = path.join(bridgeDir(world), 'command.json');
    for (let i = 0; i < 25; i += 1) {
      if (await readJsonIfPresent(commandPath)) break;
      await delay(20);
    }

    const ackPath = path.join(bridgeDir(world), 'ack.json');
    await writeFile(ackPath, JSON.stringify({ id: 'old-command', status: 'ok' }), 'utf8');
    await delay(60);
    assert.equal(settled, false);

    await writeFile(ackPath, '{ malformed', 'utf8');
    await delay(60);
    assert.equal(settled, false);

    await writeFile(ackPath, JSON.stringify({ id: 'fresh-command', status: 'ok' }), 'utf8');
    assert.equal((await observed).status, 'ok');
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('runSmoke sends explicit stop after bounded movement', async () => {
  const { root, world } = await makeWorld();
  const seen = [];
  async function responder() {
    const commandPath = path.join(bridgeDir(world), 'command.json');
    const ackPath = path.join(bridgeDir(world), 'ack.json');
    let lastId = null;
    const deadline = Date.now() + 3000;
    while (seen.length < 6 && Date.now() < deadline) {
      const command = await readJsonIfPresent(commandPath);
      if (command && command.id !== lastId) {
        lastId = command.id;
        seen.push(command);
        await writeFile(ackPath, JSON.stringify({
          id: command.id,
          action: command.action,
          status: 'ok',
          state: { online: command.action !== 'stop', moving: command.action === 'move' },
        }), 'utf8');
      }
      await delay(20);
    }
  }

  try {
    const responding = responder();
    const results = await runSmoke(world);
    await responding;
    assert.deepEqual(seen.map((command) => command.action), ['spawn', 'status', 'look', 'move', 'stop', 'status']);
    assert.equal(seen[3].durationMs, 400);
    assert.equal(results.at(-2).action, 'stop');
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('Scarpet script exposes version and keeps the safety allowlist narrow', async () => {
  const script = await readFile(new URL('../scarpet/remy_bridge.sc', import.meta.url), 'utf8');
  assert.match(script, /global_bridge_version = '0\.1\.2'/);
  assert.match(script, /'bridgeVersion' -> global_bridge_version/);
  assert.match(script, /allowed = \['spawn', 'status', 'look', 'move', 'stop'\]/);
  assert.match(script, /global_is_moving && global_move_expires_ms && now_ms > global_move_expires_ms/);
  assert.match(script, /__stop_remy\(\)/);
  assert.match(script, /'player ' \+ global_remy_name/);
  assert.doesNotMatch(script, /attack|drop|use|hotbar|mine|place/);
});
