import assert from 'node:assert/strict';
import { test, expect, waitUntil } from '@drownek/plugwright';

test('ordinary players cannot execute plugin-list commands, including namespaced aliases', async ({ player }) => {
  for (const command of ['plugins', 'pl', 'bukkit:plugins', 'bukkit:pl']) {
    const since = player.messageBuffer.length;
    player.chat(`/${command}`);
    await expect(player).toHaveReceivedMessage('Неизвестная команда.', { since });
  }
});

test('operator bypass restores command execution and the real Brigadier tree; deop hides them again', async ({ player, signal }) => {
  let roots;
  const onCommands = packet => {
    roots = packet.nodes[packet.rootIndex].children.map(index => packet.nodes[index].extraNodeData.name);
  };
  player.bot._client.on('declare_commands', onCommands);
  try {
    await player.makeOp();
    await waitUntil(() => roots?.includes('plugins'), { signal, timeout: 10000 });
    const since = player.messageBuffer.length;
    player.chat('/plugins');
    await expect(player).toHaveReceivedMessage(/ARC/, { since });
    roots = undefined;
    await player.deOp();
    await waitUntil(() => roots !== undefined, { signal, timeout: 10000 });
    assert.ok(!roots.includes('plugins'), 'Restricted command leaked into the client command tree');
    assert.ok(!roots.includes('pl'), 'Restricted alias leaked into the client command tree');
    assert.ok(roots.every(name => !name.includes(':')), 'Namespaced command roots leaked');
    const restricted = player.messageBuffer.length;
    player.chat('/bukkit:plugins');
    await expect(player).toHaveReceivedMessage('Неизвестная команда.', { since: restricted });
  } finally {
    player.bot._client.off('declare_commands', onCommands);
  }
});
