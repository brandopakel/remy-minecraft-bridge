import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {
  assertSuccessfulAck,
  assertFreshHeartbeat,
  bridgeDir,
  buildCommand,
  expectedBridgeVersion,
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

async function writeHeartbeat(world, fields = {}) {
  const heartbeatPath = path.join(bridgeDir(world), 'heartbeat.json');
  await mkdir(path.dirname(heartbeatPath), { recursive: true });
  await writeFile(heartbeatPath, JSON.stringify({
    bridgeVersion: expectedBridgeVersion,
    unixMs: Date.now(),
    worldFolder: path.basename(world),
    ...fields,
  }), 'utf8');
}

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

test('buildCommand rejects reserved and action-specific field overrides', () => {
  assert.throws(() => buildCommand('status', { action: 'move' }), /reserved/);
  assert.throws(() => buildCommand('status', { expiresMs: 999999999 }), /reserved/);
  assert.throws(() => buildCommand('look', { direction: 'north', durationMs: 999999 }), /not valid/);
  assert.throws(() => buildCommand('spawn', { direction: 'north' }), /not valid/);
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

test('sendCommand rejects failed, rejected, and expired acknowledgments', async () => {
  assert.throws(() => assertSuccessfulAck({ id: 'x', action: 'move', status: 'failed', detail: { success: 0 } }), /failed/);
  assert.throws(() => assertSuccessfulAck({ id: 'x', action: 'move', status: 'rejected', detail: { error: 'bad' } }), /rejected/);
  assert.throws(() => assertSuccessfulAck({ id: 'x', action: 'move', status: 'expired', detail: { error: 'old' } }), /expired/);

  const { root, world } = await makeWorld();
  try {
    const pending = sendCommand(world, 'move', { direction: 'forward', durationMs: 400 }, {
      id: 'move-failed',
      now: 1000,
      ttlMs: 5000,
      timeoutMs: 1000,
      pollIntervalMs: 20,
    });

    const commandPath = path.join(bridgeDir(world), 'command.json');
    for (let i = 0; i < 25; i += 1) {
      if (await readJsonIfPresent(commandPath)) break;
      await delay(20);
    }

    const ackPath = path.join(bridgeDir(world), 'ack.json');
    await writeFile(ackPath, JSON.stringify({
      id: 'move-failed',
      action: 'move',
      status: 'ok',
      detail: { success: 0, error: 'movement failed' },
    }), 'utf8');
    await assert.rejects(pending, /Command move failed/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('assertFreshHeartbeat validates version, world, and age', async () => {
  const { root, world } = await makeWorld();
  try {
    const now = Date.now();
    assert.equal(assertFreshHeartbeat({
      bridgeVersion: expectedBridgeVersion,
      unixMs: now,
      worldFolder: path.basename(world),
    }, world, { now }).bridgeVersion, expectedBridgeVersion);
    assert.throws(() => assertFreshHeartbeat({
      bridgeVersion: '0.0.0',
      unixMs: now,
      worldFolder: path.basename(world),
    }, world, { now }), /does not match expected/);
    assert.throws(() => assertFreshHeartbeat({
      bridgeVersion: expectedBridgeVersion,
      unixMs: now,
      worldFolder: 'Other World',
    }, world, { now }), /does not match/);
    assert.throws(() => assertFreshHeartbeat({
      bridgeVersion: expectedBridgeVersion,
      unixMs: now - 10000,
      worldFolder: path.basename(world),
    }, world, { now, maxAgeMs: 1000 }), /stale/);
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
    await writeHeartbeat(world);
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

test('runSmoke sends stop even when movement acknowledgment is lost', async () => {
  const { root, world } = await makeWorld();
  const seen = [];
  async function responder() {
    const commandPath = path.join(bridgeDir(world), 'command.json');
    const ackPath = path.join(bridgeDir(world), 'ack.json');
    let lastId = null;
    const deadline = Date.now() + 3000;
    while (seen.length < 5 && Date.now() < deadline) {
      const command = await readJsonIfPresent(commandPath);
      if (command && command.id !== lastId) {
        lastId = command.id;
        seen.push(command);
        if (command.action !== 'move') {
          await writeFile(ackPath, JSON.stringify({
            id: command.id,
            action: command.action,
            status: 'ok',
            detail: { success: 1 },
            state: { online: command.action !== 'stop', moving: command.action === 'move' },
          }), 'utf8');
        }
      }
      await delay(20);
    }
  }

  try {
    await writeHeartbeat(world);
    const responding = responder();
    await assert.rejects(runSmoke(world, {
      commandOptions: { timeoutMs: 120, pollIntervalMs: 10 },
      waitAfterMoveMs: 20,
    }), /Timed out waiting for ack for move/);
    await responding;
    assert.deepEqual(seen.map((command) => command.action), ['spawn', 'status', 'look', 'move', 'stop']);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('Scarpet script exposes version and keeps the safety allowlist narrow', async () => {
  const script = await readFile(new URL('../scarpet/remy_bridge.sc', import.meta.url), 'utf8');
  assert.match(script, /global_bridge_version = '0\.1\.4'/);
  assert.match(script, /'bridgeVersion' -> global_bridge_version/);
  assert.match(script, /'worldPath' -> system_info\('world_path'\)/);
  assert.match(script, /__heartbeat\(reason\)/);
  assert.match(script, /__on_start\(\)/);
  assert.match(script, /__on_close\(\)/);
  assert.match(script, /query\(p, 'player_type'\)/);
  assert.match(script, /player_type != 'fake'/);
  assert.match(script, /in_dimension\(p, __surroundings\(center\)\)/);
  assert.match(script, /allowed = \['spawn', 'status', 'look', 'move', 'stop'\]/);
  assert.match(script, /global_is_moving && global_move_expires_ms && now_ms >= global_move_expires_ms/);
  assert.match(script, /__stop_remy\(\)/);
  assert.match(script, /result = __command_result\(__run_player\('stop'\)\)/);
  assert.match(script, /if\(result:'success'/);
  assert.match(script, /pre_stop = __stop_remy\(\)/);
  assert.match(script, /expires_ms - created_ms > global_max_command_ttl_ms/);
  assert.match(script, /status = if\(detail:'success' == 0 \|\| detail:'error', 'failed', 'ok'\)/);
  assert.match(script, /'player ' \+ global_remy_name/);
  assert.match(script, /execute as @a\[name!=' \+ global_remy_name \+ ',limit=1\] at @s run player/);
  assert.doesNotMatch(script, /spawn in survival/);
  assert.doesNotMatch(script, /__run_player\('(attack|drop|use|hotbar|mine|place)/);
});
