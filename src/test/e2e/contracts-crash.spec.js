import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { readFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { once } from 'node:events';
import { test, expect, waitUntil, ServerWrapper } from '@drownek/plugwright';

const enabled = process.env.ARC_CONTRACT_CRASH_FIXTURE === '1';
const phases = [
  ['removal_saved', false, false, 'interrupted_item_removal'],
  ['escrow_saved', false, false, 'interrupted_escrow'],
  ['payment_failure_saved', false, false, 'interrupted_payment_failed'],
  ['payment_succeeded', true, false, 'interrupted_payment'],
  ['paid_saved', true, true, 'none'],
  ['contract_saved', true, true, 'none'],
];
const countStone = player => player.bot.inventory.items()
  .filter(item => item.name === 'stone').reduce((total, item) => total + item.count, 0);

async function properties(name) {
  const file = path.join(process.env.SERVER_DIR, 'plugins', 'ARCContractCrashFixture', `${name}.properties`);
  const text = await readFile(file, 'utf8');
  return Object.fromEntries(text.split(/\r?\n/).filter(line => line && !line.startsWith('#'))
    .map(line => { const split = line.indexOf('='); return [line.slice(0, split), line.slice(split + 1)]; }));
}

async function balance(player, expectedMinor, signal) {
  const since = player.messageBuffer.length;
  player.chat('/balance');
  await waitUntil(() => player.messageBuffer.slice(since).some(message => {
    const match = message.replace(/§./g, '').match(/You have ([\d.,]+)(?: coins?)?!/i);
    return match && Math.round(Number(match[1].replace(',', '.')) * 100) === expectedMinor;
  }), { signal, message: `Provider balance did not equal ${expectedMinor} minor units` });
}

async function snapshot(player, name, signal) {
  await player.makeOp();
  const since = player.messageBuffer.length;
  player.chat(`/contractcrashfixture snapshot ${name}`);
  await expect(player).toHaveReceivedMessage(`CONTRACT_CRASH_SNAPSHOT:${name}`, { since });
  const result = await properties(name);
  await player.deOp();
  return result;
}

async function startReplacement(player, generation, signal) {
  const serverDir = process.env.SERVER_DIR;
  assert.ok(path.isAbsolute(serverDir), 'Only the task-owned absolute Paper directory is supported');
  await mkdir(path.join(serverDir, 'crash-probe-logs'), { recursive: true });
  const log = createWriteStream(path.join(serverDir, 'crash-probe-logs', `generation-${generation}.log`));
  const args = (process.env.JVM_ARGS || '').split(' ').filter(Boolean);
  const child = spawn(process.env.JAVA_PATH || 'java', [...args, '-jar', process.env.SERVER_JAR, '--nogui'], {
    cwd: serverDir, env: process.env, stdio: ['pipe', 'pipe', 'pipe'],
  });
  let ready = false;
  let fixtureReady = false;
  let output = '';
  for (const stream of [child.stdout, child.stderr]) {
    stream.on('data', chunk => {
      log.write(chunk);
      output = (output + chunk.toString()).slice(-16384);
      ready ||= output.includes('Done (');
      fixtureReady ||= output.includes('CONTRACT_CRASH_FIXTURE_READY');
    });
  }
  child.on('close', () => log.end());
  child.on('error', error => { output += `\n${error.message}`; });
  child.stdin.on('error', error => { output += `\n${error.message}`; });
  try {
    await waitUntil(() => {
      if (child.exitCode !== null || child.signalCode !== null) {
        throw new Error(`Replacement Paper exited ${child.exitCode ?? child.signalCode}: ${output}`);
      }
      return ready && fixtureReady;
    }, { signal, timeout: 120000, message: `Replacement Paper ${generation} did not become ready` });
    player.setServerWrapper(new ServerWrapper(command => child.stdin.write(`${command}\n`)));
    await player.rejoin({ clearMessages: false });
    return child;
  } catch (error) {
    child.kill('SIGKILL');
    throw error;
  }
}

async function stopReplacement(child) {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;
  const closed = once(child, 'close');
  if (!child.stdin.destroyed) child.stdin.write('stop\n');
  else child.kill('SIGKILL');
  const timer = setTimeout(() => child.kill('SIGKILL'), 30000);
  try { await closed; } finally { clearTimeout(timer); }
}

if (enabled) test('contract items, provider payment and journal survive real process crashes without replay', async ({ player, signal }) => {
  let replacement;
  let expectedBalance = 0;
  let expectedSpent = 0;
  let expectedHeld = 0;
  let expectedAccepted = 0;
  let expectedReserved = 0;
  let expectedManual = 0;
  let lastSnapshot;
  try {
    for (const [index, [phase, paid, committed, reviewReason]] of phases.entries()) {
      await player.makeOp();
      player.chat('/clear @s');
      await waitUntil(() => countStone(player) === 0, { signal, message: 'Fixture inventory did not clear' });
      await player.giveItem('stone', 4);
      await waitUntil(() => countStone(player) === 4, { signal, message: 'Fixture inventory did not receive four stones' });
      const beforeArm = player.messageBuffer.length;
      player.chat(`/contractcrashfixture arm ${phase}`);
      await expect(player).toHaveReceivedMessage(`CONTRACT_CRASH_ARMED:${phase}`, { since: beforeArm });
      await player.deOp();
      await balance(player, expectedBalance, signal);
      player.chat('/arc contracts open spawn');
      const list = await player.gui({ title: /Книга заказов/i });
      await list.locator(item => item.getDisplayName().includes('E2E stone order')).click();
      const detail = await player.gui({ title: /Сдать ресурсы/i });
      const quote = await detail.locator(item => item.getDisplayName().includes('Выплата:')).displayName();
      const quotedMinor = Math.round(Number(quote.replace(',', '.').replace(/[^\d.]/g, '')) * 100);
      assert.ok(quotedMinor > 0, 'Quote must expose the exact positive payout');
      const ended = once(player.bot, 'end', { signal });
      await detail.locator(item => item.getDisplayName().includes('Подтвердить')).click().catch(() => undefined);
      await ended;
      const crash = await properties('crashed');
      assert.equal(crash.phase, phase, 'Paper must halt at the requested production boundary');
      const crashedPid = Number(crash.processId);
      assert.ok(Number.isSafeInteger(crashedPid) && crashedPid > 0, 'Fixture must identify its own JVM');
      await waitUntil(() => {
        try { process.kill(crashedPid, 0); return false; }
        catch (error) { if (error.code === 'ESRCH') return true; throw error; }
      }, { signal, message: 'Crashed fixture JVM has not released its process and server port' });
      if (replacement) {
        await waitUntil(() => replacement.exitCode !== null || replacement.signalCode !== null,
          { signal, message: 'Crashed replacement did not exit' });
        assert.equal(replacement.exitCode, 86, 'The deliberate JVM halt must own the exit');
      }
      replacement = await startReplacement(player, index + 1, signal);
      expectedBalance += paid ? quotedMinor : 0;
      expectedSpent += committed ? quotedMinor : 0;
      expectedHeld += committed ? 0 : quotedMinor;
      expectedAccepted += committed ? 2 : 0;
      expectedReserved += committed ? 0 : 2;
      expectedManual += committed ? 0 : 1;
      await balance(player, expectedBalance, signal);
      await waitUntil(() => countStone(player) === 2, { signal, message: 'Persisted removal did not survive the crashed JVM' });
      lastSnapshot = await snapshot(player, `after_${phase}`, signal);
      assert.equal(Number(lastSnapshot.records), index + 1);
      assert.equal(lastSnapshot.submissionId, crash.submissionId);
      assert.equal(lastSnapshot.status, committed ? 'contract_committed' : 'manual_review');
      assert.equal(lastSnapshot.reviewReason, reviewReason);
      assert.equal(Number(lastSnapshot.quantity), 2);
      assert.equal(Number(lastSnapshot.payoutMinor), quotedMinor);
      assert.equal(Number(lastSnapshot.items), 2);
      assert.equal(Number(lastSnapshot.acceptedQuantity), expectedAccepted);
      assert.equal(Number(lastSnapshot.spentMinor), expectedSpent);
      assert.equal(Number(lastSnapshot.reservedMinor), expectedHeld);
      assert.equal(Number(lastSnapshot.reservedQuantity), expectedReserved);
      assert.equal(Number(lastSnapshot.manualReviewCount), expectedManual);
    }
    await stopReplacement(replacement);
    replacement = undefined;
    replacement = await startReplacement(player, phases.length + 1, signal);
    await balance(player, expectedBalance, signal);
    await waitUntil(() => countStone(player) === 2, { signal, message: 'Inventory did not synchronize after repeated recovery' });
    assert.deepEqual(await snapshot(player, 'repeat_recovery', signal), lastSnapshot,
      'A second recovery must not change items, payout, held quota or contract progress');
  } finally {
    await stopReplacement(replacement);
  }
});
