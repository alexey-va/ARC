import assert from 'node:assert/strict';
import { test, expect, waitUntil } from '@drownek/plugwright';

function titleText(packet) {
  const text = packet?.text;
  return typeof text === 'string' ? text : JSON.stringify(text ?? '');
}

test('real Parkour run emits ARC HUD for join, checkpoint, death and finish', async ({ player, server, signal }) => {
  await player.makeOp();

  const titles = [];
  const onTitle = packet => {
    const text = titleText(packet);
    titles.push(text);
    console.log('Parkour title:', text);
  };
  const logPosition = label => {
    const position = player.bot.entity.position;
    console.log(label, JSON.stringify({ position, onGround: player.bot.entity.onGround,
      feet: player.bot.blockAt(position)?.name,
      below: player.bot.blockAt(position.offset(0, -1, 0))?.name }));
  };
  const walkToCheckpoint = async z => {
    try {
      // Creating a checkpoint while standing there presses its plate before
      // joining. Native pressure-plate polling must release it before this run.
      await waitUntil(() => player.bot.blockAt(player.bot.entity.position.clone().set(0.5, 65, z))
        ?.getProperties().powered === false, {
        signal, timeout: 10000, message: `Checkpoint plate at z=${z} must be released before entering it`,
      });
      logPosition(`Before walking to ${z}`);
      await player.bot.lookAt(player.bot.entity.position.clone().set(0.5, 66.5, z), true);
      player.bot.setControlState('forward', true);
      await waitUntil(() => Math.abs(player.bot.entity.position.z - z) < 0.25, {
        signal, timeout: 10000, message: `Player must physically reach checkpoint at z=${z}`,
      });
    } finally {
      player.bot.clearControlStates();
      logPosition(`After walking to ${z}`);
    }
  };
  player.bot._client.on('set_title_text', onTitle);
  try {
    server.execute('minecraft:fill -20 64 -20 20 64 20 minecraft:stone');
    await player.teleport(0.5, 65, 0.5);
    player.chat('/pa create course arc-e2e');
    await expect(player).toHaveReceivedMessage(/created|создан/i);

    // Parkour creates checkpoint 0 with the course. The first plate must be ahead
    // of that origin, otherwise starting on an extra checkpoint skips its trigger.
    await player.teleport(0.5, 65, 3.5);
    const firstCheckpointSince = player.getMessageBufferIndex();
    player.chat('/pa create checkpoint arc-e2e');
    await expect(player).toHaveReceivedMessage(/Checkpoint 1/i, { since: firstCheckpointSince });
    await player.teleport(0.5, 65, 6.5);
    const lastCheckpointSince = player.getMessageBufferIndex();
    player.chat('/pa create checkpoint arc-e2e');
    await expect(player).toHaveReceivedMessage(/Checkpoint 2/i, { since: lastCheckpointSince });

    const readySince = player.getMessageBufferIndex();
    player.chat('/pa setcourse arc-e2e ready');
    await expect(player).toHaveReceivedMessage(/Ready Status.*true/i, { since: readySince });
    player.chat('/pa join arc-e2e');
    await waitUntil(() => titles.some(text => text.includes('ТРАССА НАЧАЛАСЬ')), { signal, timeout: 10000 });
    await waitUntil(() => player.bot.entity.onGround && Math.abs(player.bot.entity.position.z - 0.5) < 0.25, {
      signal, timeout: 10000, message: 'Native course join must return the player to the start',
    });

    await walkToCheckpoint(3.5);
    await waitUntil(() => titles.some(text => text.includes('ТОЧКА')), { signal, timeout: 10000 });

    server.execute(`minecraft:kill ${player.username}`);
    await waitUntil(() => titles.some(text => text.includes('Срыв')), { signal, timeout: 10000 });

    await waitUntil(() => Math.abs(player.bot.entity.position.z - 3.5) < 1, {
      signal, timeout: 10000, message: 'Parkour must return the dead player to the acquired checkpoint',
    });
    await walkToCheckpoint(6.5);
    await waitUntil(() => titles.some(text => text.includes('ТРАССА ПРОЙДЕНА')), { signal, timeout: 10000 });
    assert.ok(titles.some(text => text.includes('ТРАССА НАЧАЛАСЬ')));
    assert.ok(titles.some(text => text.includes('ТОЧКА')));
    assert.ok(titles.some(text => text.includes('Срыв')));
    assert.ok(titles.some(text => text.includes('ТРАССА ПРОЙДЕНА')));
  } finally {
    player.bot._client.off('set_title_text', onTitle);
  }
});
