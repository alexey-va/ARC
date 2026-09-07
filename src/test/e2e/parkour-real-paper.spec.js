import assert from 'node:assert/strict';
import { test, expect, waitUntil } from '@drownek/plugwright';

function titleText(packet) {
  const text = packet?.text;
  return typeof text === 'string' ? text : JSON.stringify(text ?? '');
}

async function walkForward(player, milliseconds = 700) {
  await player.bot.look(Math.PI, 0, true);
  player.bot.setControlState('forward', true);
  await new Promise(resolve => setTimeout(resolve, milliseconds));
  player.bot.clearControlStates();
}

test('real Parkour run emits ARC HUD for join, checkpoint, death and finish', async ({ player, server, signal }) => {
  await player.makeOp();

  const titles = [];
  const onTitle = packet => titles.push(titleText(packet));
  player.bot._client.on('set_title_text', onTitle);
  try {
    server.execute('minecraft:fill -20 64 -20 20 64 20 minecraft:stone');
    await player.teleport(0, 65, 0);
    player.chat('/pa create course arc-e2e');
    await expect(player).toHaveReceivedMessage(/created|создан/i);

    await player.teleport(0, 65, 3);
    player.chat('/pa create checkpoint arc-e2e');
    await expect(player).toHaveReceivedMessage(/checkpoint|контрольн/i);
    await player.teleport(0, 65, 6);
    player.chat('/pa create checkpoint arc-e2e');
    await expect(player).toHaveReceivedMessage(/checkpoint|контрольн/i);

    player.chat('/pa setcourse arc-e2e ready');
    await expect(player).toHaveReceivedMessage(/ready|готов/i);
    player.chat('/pa join arc-e2e');
    await waitUntil(() => titles.some(text => text.includes('ТРАССА НАЧАЛАСЬ')), { signal, timeout: 10000 });

    await walkForward(player);
    await waitUntil(() => titles.some(text => text.includes('ТОЧКА')), { signal, timeout: 10000 });

    server.execute(`minecraft:kill ${player.username}`);
    await waitUntil(() => titles.some(text => text.includes('Срыв')), { signal, timeout: 10000 });

    await player.teleport(0, 65, 5);
    await walkForward(player, 500);
    await waitUntil(() => titles.some(text => text.includes('ТРАССА ПРОЙДЕНА')), { signal, timeout: 10000 });
    assert.ok(titles.some(text => text.includes('ТРАССА НАЧАЛАСЬ')));
    assert.ok(titles.some(text => text.includes('ТОЧКА')));
    assert.ok(titles.some(text => text.includes('Срыв')));
    assert.ok(titles.some(text => text.includes('ТРАССА ПРОЙДЕНА')));
  } finally {
    player.bot._client.off('set_title_text', onTitle);
  }
});
