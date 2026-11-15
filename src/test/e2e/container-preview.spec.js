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

test('native container preview follows a physical full orbit without replacing its icons or counts', async context => {
  const { player, signal } = context;
  const { opener, position, contents, place, target } = await fixture(context);
  const bot = player.bot;
  await opener.teleport(7.5, 65, 7.5);
  await place('barrel');
  await waitPreview(player, contents, signal);
  const center = position.offset(0.5, 0.5, 0.5);
  const metadata = (entity, key) => entity.metadata[bot.registry.entitiesByName[entity.name].metadataKeys.indexOf(key)];
  const item = entity => bot.registry.items[metadata(entity, 'item_stack')?.itemId]?.name;
  const text = entity => plain(unwrap(metadata(entity, 'text')));
  const names = contents.map(([name]) => name).sort();
  const counts = contents.map(([, count]) => String(count)).sort();
  const displays = Object.values(bot.entities);
  const icons = displays.filter(entity => entity.name === 'item_display' && names.includes(item(entity)));
  assert.equal(icons.length, contents.length, 'The orbit must start with exactly the selected content icons');
  const labels = displays.filter(entity => entity.name === 'text_display' && counts.includes(text(entity)) &&
    entity.position.distanceTo(icons[0].position) < 0.001);
  assert.equal(labels.length, contents.length, 'Track only the count entities at this preview anchor');
  const iconIds = icons.map(entity => entity.id), labelIds = labels.map(entity => entity.id);
  const ids = [...iconIds, ...labelIds], destroyed = new Set(), samples = [];
  const removed = packet => packet.entityIds.forEach(id => { if (ids.includes(id)) destroyed.add(id); });
  bot._client.on('entity_destroy', removed);
  const parts = () => {
    assert.equal(destroyed.size, 0, `Orbit removed preview entities: ${[...destroyed]}`);
    const current = ids.map(id => bot.entities[id]);
    assert.ok(current.every(Boolean), `Orbit lost preview entity IDs: ${ids.filter(id => !bot.entities[id])}`);
    assert.deepEqual(iconIds.map(id => item(bot.entities[id])).sort(), names);
    assert.ok(iconIds.every(id => metadata(bot.entities[id], 'item_stack').itemCount === 1));
    assert.deepEqual(labelIds.map(id => text(bot.entities[id])).sort(), counts);
    return current;
  };
  let travel = 0;
  const sample = async mark => {
    let anchor;
    await waitUntil(() => {
      const current = parts();
      anchor = current[0].position.clone();
      const viewer = bot.entity.position;
      const facing = (anchor.x - center.x) * (viewer.x - center.x) + (anchor.z - center.z) * (viewer.z - center.z);
      return current.every(entity => entity.position.distanceTo(anchor) < 0.001) && facing > 0;
    }, { signal, timeout: 3000, interval: 25, message: `Orbit ${mark}: all parts must share a viewer-facing world anchor` });
    samples.push({ mark, anchor, viewer: bot.entity.position.clone() });
    console.log('Container orbit sample', JSON.stringify(samples.at(-1)));
    return anchor;
  };
  try {
    const initial = await sample(0);
    const yaw = bot.entity.yaw, pitch = bot.entity.pitch;
    for (const delta of [-0.04, 0.04]) {
      await bot.look(yaw + delta, pitch, true);
      await bot.waitForTicks(4);
      assert.equal(bot.blockAtCursor(4.5)?.name, 'barrel', 'Head-only motion must keep the same barrel targeted');
      const anchor = await sample(`head ${delta}`);
      assert.ok(anchor.distanceTo(initial) < 0.001, 'Head-only rotation must not translate the panel');
    }
    await target(player);
    let previous = Math.atan2(bot.entity.position.z - center.z, bot.entity.position.x - center.x);
    let mark = 90;
    for (let tick = 0; tick < 400 && mark <= 360; tick++) {
      await target(player);
      const radius = Math.hypot(bot.entity.position.x - center.x, bot.entity.position.z - center.z);
      assert.ok(radius > 2.4 && radius < 3.9, `Orbit left its reachable walking ring: ${radius}`);
      bot.setControlState('right', true);
      bot.setControlState('forward', radius > 3.1);
      bot.setControlState('back', radius < 2.9);
      await bot.waitForTicks(1);
      const angle = Math.atan2(bot.entity.position.z - center.z, bot.entity.position.x - center.x);
      travel += Math.atan2(Math.sin(angle - previous), Math.cos(angle - previous));
      previous = angle;
      parts();
      if (Math.abs(travel) >= mark * Math.PI / 180) {
        bot.clearControlStates();
        await bot.waitForTicks(3);
        await target(player);
        await sample(mark);
        mark += 90;
      }
    }
    assert.deepEqual(samples.filter(entry => typeof entry.mark === 'number').map(entry => entry.mark), [0, 90, 180, 270, 360],
      `The client must physically walk a full orbit; travelled ${travel * 180 / Math.PI} degrees`);
    assert.ok(samples.some(entry => entry.anchor.distanceTo(initial) > 0.05), 'Walking around the barrel must change the world anchor');
  } catch (failure) {
    console.error('Container orbit diagnostics', JSON.stringify({ degrees: travel * 180 / Math.PI,
      viewer: bot.entity.position, destroyed: [...destroyed], samples }).slice(0, 6000));
    throw failure;
  } finally {
    bot.clearControlStates();
    bot._client.off('entity_destroy', removed);
  }
});
