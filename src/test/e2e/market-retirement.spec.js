import assert from 'node:assert/strict';
import { test, waitUntil } from '@drownek/plugwright';

test('retired investment commands are absent even from the operator command tree', async ({ player, signal }) => {
  let commands;
  const onCommands = packet => { commands = packet; };
  player.bot._client.on('declare_commands', onCommands);
  try {
    await player.makeOp();
    await waitUntil(() => commands !== undefined, { signal, timeout: 10000 });
    const { nodes, rootIndex } = commands;
    const roots = nodes[rootIndex].children.map(index => nodes[index]);
    const names = roots.map(node => node.extraNodeData.name);
    assert.ok(names.includes('arc'), 'ARC must load and register its main command');
    assert.ok(!names.includes('arc-invest'), 'Retired market root is still registered');
    assert.ok(!names.includes('arc:arc-invest'), 'Retired namespaced market command is still registered');
    const arc = roots.find(node => node.extraNodeData.name === 'arc');
    const children = arc.children.map(index => nodes[index].extraNodeData?.name);
    assert.ok(!children.includes('invest'), 'Retired invest subcommand is still advertised');
  } finally {
    player.bot._client.off('declare_commands', onCommands);
  }
});
