import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { bridgeDir, buildCommand, sendCommand } from '../src/controller.mjs';

test('buildCommand rejects non-allowlisted actions', () => {
  assert.throws(() => buildCommand('attack'), /not allowlisted/);
});

test('buildCommand validates look directions', () => {
  assert.throws(() => buildCommand('look', { direction: 'northwest' }), /Invalid look direction/);
  assert.equal(buildCommand('look', { direction: 'north' }, { now: 1000, id: 'x' }).action, 'look');
});

test('buildCommand caps move duration', () => {
  const command = buildCommand('move', { direction: 'forward', durationMs: 5000 }, { now: 1000, id: 'x' });
  assert.equal(command.durationMs, 1000);
});

test('sendCommand writes command and waits for matching ack', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'remy-bridge-test-'));
  try {
    const pending = sendCommand(root, 'status', {}, {
      id: 'status-1',
      now: 1000,
      ttlMs: 5000,
      timeoutMs: 1000,
      pollIntervalMs: 20,
    });

    const commandPath = path.join(bridgeDir(root), 'command.json');
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

    const ackPath = path.join(bridgeDir(root), 'ack.json');
    await writeFile(ackPath, JSON.stringify({ id: 'status-1', status: 'ok' }), 'utf8');
    const ack = await pending;
    assert.equal(ack.status, 'ok');
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
