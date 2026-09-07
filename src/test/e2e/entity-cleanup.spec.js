import assert from 'node:assert/strict';
import { expect, test, waitUntil } from '@drownek/plugwright';

const AGE_OBJECTIVE = 'arc_cleanup_age';

async function command(server, player, value) {
  const marker = `entity-cleanup-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  server.execute(value);
  server.execute(`minecraft:tellraw ${player.username} {"text":"${marker}"}`);
  await expect(player).toHaveReceivedMessage(marker);
}

async function prepare(server, player) {
  await player.makeOp();
  await command(server, player, `minecraft:kill @e[type=item]`);
  await command(server, player, `minecraft:kill @e[type=zombie]`);
  await command(server, player, `minecraft:scoreboard objectives add ${AGE_OBJECTIVE} dummy`);
  await command(server, player, `minecraft:scoreboard players reset arc_cleanup_drop ${AGE_OBJECTIVE}`);
  await command(server, player, 'minecraft:fill -8 64 -8 16 64 16 minecraft:stone');
  await command(server, player, 'minecraft:fill -8 65 -8 16 70 16 minecraft:air');
  await player.teleport(0.5, 65, 10.5);
}

function itemSelector(extra = '') {
  return `@e[type=item,distance=..6,limit=1,sort=nearest${extra ? `,${extra}` : ''}]`;
}

async function tellIf(server, player, predicate, marker, position = '0.5 65 0.5') {
  server.execute(`minecraft:execute positioned ${position} ${predicate} run minecraft:tellraw ${player.username} {"text":"${marker}"}`);
  await expect(player).toHaveReceivedMessage(marker);
}

async function assertAgeRange(server, player, selector, range, label, position = '0.5 65 0.5') {
  await tellIf(server, player, `if entity ${selector}`, `entity-present-${label}`, position);
  await command(server, player,
    `minecraft:execute positioned ${position} store result score arc_cleanup_drop ${AGE_OBJECTIVE} run minecraft:data get entity ${selector} Age 1`);
  const marker = `age-ok-${label}`;
  await tellIf(
    server,
    player,
    `if score arc_cleanup_drop ${AGE_OBJECTIVE} matches ${range}`,
    marker,
    position,
  );
}

async function waitForPredicate(server, player, predicate, marker, position, signal) {
  const seen = player.messageBuffer.length;
  await waitUntil(() => {
    server.execute(`minecraft:execute positioned ${position} ${predicate} run minecraft:tellraw ${player.username} {"text":"${marker}"}`);
    return player.messageBuffer.slice(seen).some((message) => message.includes(marker));
  }, { signal, timeout: 5000, interval: 100, message: `Timed out waiting for ${marker}` });
}

async function toss(player, item) {
  await player.bot.equip(item, 'hand');
  await player.bot.tossStack(item);
}

async function ironSword(player) {
  const item = player.bot.inventory.items().find((entry) => entry.name === 'iron_sword');
  assert.ok(item, 'iron sword was not delivered');
  return item;
}

test('ordinary mob gear receives the configured native age and despawns quickly', async ({ player, server, signal }) => {
  await prepare(server, player);
  await command(server, player,
    'minecraft:summon zombie 0 65 0 {Tags:["arc_cleanup_positive"],PersistenceRequired:1b,CanPickUpLoot:0b,NoAI:1b,equipment:{mainhand:{id:"minecraft:iron_sword",count:1}},drop_chances:{mainhand:2.0f}}');
  await command(server, player, 'minecraft:kill @e[type=zombie,tag=arc_cleanup_positive,limit=1]');
  await assertAgeRange(server, player, itemSelector('nbt={Item:{id:"minecraft:iron_sword"}}'), '5960..5999', 'positive');

  const gone = `gone-positive-${Date.now()}`;
  await waitForPredicate(server, player, 'unless entity @e[type=item,nbt={Item:{id:"minecraft:iron_sword"}},distance=..6]', gone, '0.5 65 0.5', signal);

  player.chat('/arc reload');
  await expect(player).toHaveReceivedMessage(/Перезагруз|reload/i);
  await command(server, player,
    'minecraft:summon zombie 0 65 0 {Tags:["arc_cleanup_after_reload"],PersistenceRequired:1b,CanPickUpLoot:0b,NoAI:1b,equipment:{mainhand:{id:"minecraft:iron_sword",count:1}},drop_chances:{mainhand:2.0f}}');
  await command(server, player, 'minecraft:kill @e[type=zombie,tag=arc_cleanup_after_reload,limit=1]');
  await assertAgeRange(server, player, itemSelector('nbt={Item:{id:"minecraft:iron_sword"}}'), '5960..5999', 'reload');
});

test('player and custom drops keep native age', async ({ player, server, signal }) => {
  await prepare(server, player);
  await player.giveItem('iron_sword', 1);
  await waitUntil(() => player.bot.inventory.items().some((entry) => entry.name === 'iron_sword'), { signal });
  await toss(player, await ironSword(player));
  await assertAgeRange(server, player, itemSelector('nbt={Item:{id:"minecraft:iron_sword"}}'), '0..100', 'player', '0.5 65 10.5');

  await command(server, player, 'minecraft:kill @e[type=item]');
  player.chat(`/give ${player.username} iron_sword[custom_name={text:"ARC custom"}] 1`);
  await waitUntil(() => player.bot.inventory.items().some((entry) => entry.name === 'iron_sword'), { signal });
  await toss(player, await ironSword(player));
  await assertAgeRange(server, player, itemSelector('nbt={Item:{id:"minecraft:iron_sword"}}'), '0..100', 'custom', '0.5 65 10.5');
});

test('pickup history and same-tick deaths preserve custom/native exclusions', async ({ player, server, signal }) => {
  await prepare(server, player);
  await command(server, player,
    'minecraft:summon zombie 0.5 65 9 {Tags:["arc_cleanup_picker"],PersistenceRequired:1b,CanPickUpLoot:1b}');
  await player.giveItem('iron_sword', 1);
  await waitUntil(() => player.bot.inventory.items().some((entry) => entry.name === 'iron_sword'), { signal });
  await toss(player, await ironSword(player));
  await waitForPredicate(server, player, 'if entity @e[type=zombie,tag=arc_cleanup_picker,nbt={equipment:{mainhand:{id:"minecraft:iron_sword"}}}]', 'pickup-confirmed', '0.5 65 0.5', signal);
  await command(server, player, 'minecraft:kill @e[type=zombie,tag=arc_cleanup_picker,limit=1]');
  await assertAgeRange(server, player, itemSelector('nbt={Item:{id:"minecraft:iron_sword"}}'), '0..100', 'pickup', '0.5 65 10.5');
  await command(server, player, 'minecraft:kill @e[type=item]');

  await command(server, player,
    'minecraft:summon zombie 3 65 0 {Tags:["arc_cleanup_batch"],PersistenceRequired:1b,CanPickUpLoot:0b,NoAI:1b,equipment:{mainhand:{id:"minecraft:iron_sword",count:1}},drop_chances:{mainhand:2.0f}}');
  await command(server, player,
    'minecraft:summon zombie 6 65 0 {Tags:["arc_cleanup_batch"],PersistenceRequired:1b,CanPickUpLoot:0b,NoAI:1b,equipment:{mainhand:{id:"minecraft:iron_sword",components:{"minecraft:custom_name":{text:"ARC batch custom"}},count:1}},drop_chances:{mainhand:2.0f}}');
  await command(server, player, 'minecraft:execute as @e[type=zombie,tag=arc_cleanup_batch] run minecraft:kill @s');
  await assertAgeRange(server, player, '@e[type=item,nbt={Item:{id:"minecraft:iron_sword"}},distance=..4,limit=1,sort=nearest]', '5960..5999', 'batch');
  await assertAgeRange(server, player, '@e[type=item,distance=5..8,limit=1,sort=nearest]', '0..100', 'batch-custom');
});
