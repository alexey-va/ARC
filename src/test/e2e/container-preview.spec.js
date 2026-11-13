import assert from 'node:assert/strict';
import { expect, test, waitUntil } from '@drownek/plugwright';
import { plain } from './native-dialog.js';

function unwrap(value) {
  if (Array.isArray(value)) return value.map(unwrap);
  if (!value || typeof value !== 'object') return value;
  if (typeof value.type === 'string' && 'value' in value) {
    return unwrap(value.type === 'list' ? value.value.value : value.value);
  }
  return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, unwrap(child)]));
}

function preview(player) {
  const bot = player.bot;
  const icons = [], labels = [];
  for (const entity of Object.values(bot.entities)) {
    if (!['item_display', 'text_display'].includes(entity.name)) continue;
    const keys = bot.registry.entitiesByName[entity.name].metadataKeys;
    const metadata = key => {
      const index = keys.indexOf(key);
      assert.ok(index >= 0, `Missing ${entity.name} metadata key ${key}`);
      return entity.metadata[index];
    };
    if (entity.name === 'item_display') {
      const slot = metadata('item_stack');
      if (slot?.itemCount > 0) {
        assert.ok(bot.registry.items[slot.itemId], `Unknown display item: ${JSON.stringify(slot)}`);
        icons.push({ name: bot.registry.items[slot.itemId].name, amount: slot.itemCount });
      }
    } else {
      const text = plain(unwrap(metadata('text')));
      if (/^[1-9]\d*$/.test(text)) labels.push(text);
    }
  }
  return { icons: icons.sort((a, b) => a.name.localeCompare(b.name)), labels: labels.sort() };
}

async function waitPreview(player, contents, signal) {
  const expected = {
    icons: contents.map(([name]) => ({ name, amount: 1 })).sort((a, b) => a.name.localeCompare(b.name)),
    labels: contents.map(([, count]) => String(count)).sort(),
  };
  try {
    await waitUntil(() => JSON.stringify(preview(player)) === JSON.stringify(expected), {
      signal, timeout: 8000, message: `Preview for ${player.username} must equal ${JSON.stringify(expected)}`,
    });
  } catch (failure) {
    console.error('Container preview packet diagnostics', JSON.stringify({ version: player.bot.version,
      displays: Object.values(player.bot.entities).filter(entity => entity.name?.endsWith('_display'))
        .slice(0, 12).map(entity => ({ id: entity.id, name: entity.name, metadata: entity.metadata })) }).slice(0, 6000));
    throw failure;
  }
}

async function command(server, player, value) {
  const marker = `container-preview-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  server.execute(value);
  server.execute(`minecraft:tellraw ${player.username} {"text":"${marker}"}`);
  await expect(player).toHaveReceivedMessage(marker);
}

async function fixture({ player, server, createPlayer, signal }) {
  const opener = await createPlayer();
  await player.setGameMode('creative');
  await opener.setGameMode('creative');
  await command(server, player, 'minecraft:fill -8 64 -8 8 64 8 minecraft:stone');
  await command(server, player, 'minecraft:fill -8 65 -8 8 70 8 minecraft:air');
  await player.teleport(0.5, 65, -2.5);
  await opener.teleport(2.0, 65, -1.0);
  const position = player.bot.entity.position.clone().set(0, 65, 0);
  const contents = [['diamond', 7], ['coal', 11], ['emerald', 3]];
  const target = async viewer => viewer.bot.lookAt(position.offset(0.5, 0.5, 0.5), true);
  const place = async (type, state = '') => {
    await command(server, player, `minecraft:setblock 0 65 0 minecraft:${type}${state}`);
    for (const [slot, [name, count]] of contents.entries()) {
      await command(server, player, `minecraft:item replace block 0 65 0 container.${slot} with minecraft:${name} ${count}`);
    }
    await waitUntil(() => player.bot.blockAt(position)?.name === type && opener.bot.blockAt(position)?.name === type,
      { signal, message: `Both clients must receive ${type}` });
    await target(player);
  };
  return { opener, position, contents, target, place };
}

test('native furnace previews include all slots and remain private after permission denial', async context => {
  const { player, signal } = context;
  const { opener, position, contents, target, place } = await fixture(context);
  try {
    for (const type of ['furnace', 'blast_furnace', 'smoker']) {
      // Diamond is not a recipe input, so the native furnace cannot consume input or fuel.
      await place(type);
      const window = await opener.bot.openFurnace(opener.bot.blockAt(position));
      assert.deepEqual(window.slots.slice(0, 3).map(item => [item?.name, item?.count]), contents,
        `${type} must contain the real input, fuel and output slots`);
      await waitPreview(player, contents, signal);
      await opener.bot.closeWindow(window);
      await target(opener);
      await waitPreview(opener, contents, signal);
    }
    // The fixture grants this capability only to non-OPs; OP must not receive the other viewer's private scene.
    await player.makeOp();
    await waitPreview(player, [], signal);
    await waitPreview(opener, contents, signal);
    await player.deOp();
    await waitPreview(player, contents, signal);
  } finally {
    if (opener.bot.currentWindow) await opener.bot.closeWindow(opener.bot.currentWindow);
  }
});

test('native shulker preview refreshes while another player views it and hides behind a blocked closed lid', async context => {
  const { player, server, signal } = context;
  const { opener, position, contents, place } = await fixture(context);
  try {
    await opener.teleport(-2, 65, 0.5);
    await place('shulker_box', '[facing=east]');
    await waitPreview(player, contents, signal);
    const window = await opener.bot.openContainer(opener.bot.blockAt(position));
    assert.equal(opener.bot.currentWindow?.id, window.id, 'The second client must hold a real shulker window');
    await opener.bot.waitForTicks(12); // Let the normal lid finish opening before the preview assertion.
    const changed = contents.map(([name, count], index) => [name, index === 0 ? 19 : count]);
    await command(server, player, 'minecraft:item replace block 0 65 0 container.0 with minecraft:diamond 19');
    await waitUntil(() => opener.bot.currentWindow?.slots[0]?.count === 19,
      { signal, message: 'The real open shulker window must receive the changed stack' });
    await waitPreview(player, changed, signal);
    await opener.bot.closeWindow(window);
    await opener.bot.waitForTicks(12);
    // East blocks the lid sweep without covering the observer's north-facing target ray.
    await command(server, player, 'minecraft:setblock 1 65 0 minecraft:stone');
    assert.equal(player.bot.blockAtCursor(4.5)?.name, 'shulker_box', 'The obstruction must leave the shulker targeted');
    await waitPreview(player, [], signal);
  } finally {
    if (opener.bot.currentWindow) await opener.bot.closeWindow(opener.bot.currentWindow);
  }
});
