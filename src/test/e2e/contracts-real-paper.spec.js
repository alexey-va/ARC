import assert from 'node:assert/strict';
import { test, expect, waitUntil } from '@drownek/plugwright';
import {
  clickContractDesk,
  contractUnitPriceMinor,
  CONTRACT_DESK_DEPOSIT_SLOT,
  CONTRACT_DESK_SELL_SLOT,
  offerStoneBatch,
} from './contract-desk.js';
import { observeNativeDialog, plain } from './native-dialog.js';

const count = (player, name) => player.bot.inventory.items()
  .filter(item => item.name === name)
  .reduce((total, item) => total + item.count, 0);

test('direct contract submission is unavailable; the board opens the real contract GUI', async ({ player, signal }) => {
  const native = observeNativeDialog(player, signal);
  try {
    await player.makeOp();
    await player.giveItem('stone', 4);
    await waitUntil(() => count(player, 'stone') === 4, { signal, message: 'Setup stone was not delivered' });
    await player.deOp();
    const initialBalance = player.messageBuffer.length;
    player.chat('/balance');
    await expect(player).toHaveReceivedMessage(/You have 0(?:[.,]0{1,2})?(?: coins?)?!/i, { since: initialBalance });
    const before = player.messageBuffer.length;
    player.chat('/arc contracts submit e2e_stone 1');
    await expect(player).toHaveReceivedMessage(/\/arc contracts.*(?:status|open)/i, { since: before });
    const afterAttempt = player.messageBuffer.length;
    player.chat('/balance');
    await expect(player).toHaveReceivedMessage(/You have 0(?:[.,]0{1,2})?(?: coins?)?!/i, { since: afterAttempt });
    assert.equal(count(player, 'stone'), 4, 'disabled command consumed contract resources');

    player.chat('/arc board');
    const board = await player.gui({ title: /Доска объявлений/i });
    const card = board.locator(item => item.getDisplayName().includes('E2E stone order'));
    await card.click();
    const detail = await native.wait();
    assert.ok(plain(detail.title).includes('E2E stone order'), 'Board card did not open the selected contract');
    assert.ok(native.action(/Недоступно.*Сдать ресурсы/i), 'Board navigation must not mint an NPC submission grant');
    await player.deOp();
  } finally { native.close(); }
});

test('the real NPC desk sells one exact batch once and shows the updated price', async ({ player, signal }) => {
  await player.makeOp();
  await player.giveItem('stone', 4);
  await waitUntil(() => count(player, 'stone') === 4, { signal, message: 'Setup stone was not delivered' });
  await player.deOp();
  const initialBalance = player.messageBuffer.length;
  player.chat('/balance');
  await expect(player).toHaveReceivedMessage(/You have 0(?:[.,]0{1,2})?(?: coins?)?!/i, { since: initialBalance });

  const desk = await clickContractDesk(player, signal);
  const firstUnitPrice = contractUnitPriceMinor(desk);
  assert.equal(firstUnitPrice, 12496, 'The actual NPC desk must show the current per-stone payout');
  await offerStoneBatch(player, 2, signal);

  // Submit the same desk action twice while the first sale is in flight. Only
  // the two stones staged in the desk can be sold; the other two stay with the player.
  const client = player.bot._client;
  const originalWrite = client.write;
  const salePackets = [];
  const windowId = player.bot.currentWindow?.id;
  assert.ok(windowId !== undefined, 'The sale clicks must target the open NPC desk window');
  client.write = function (name, data, ...args) {
    const isSaleClick = name === 'window_click' && data?.slot === CONTRACT_DESK_SELL_SLOT;
    const result = originalWrite.call(this, name, data, ...args);
    if (isSaleClick) salePackets.push(data);
    return result;
  };
  let clickResults;
  try {
    clickResults = await Promise.allSettled([
      player.bot.clickWindow(CONTRACT_DESK_SELL_SLOT, 0, 0),
      player.bot.clickWindow(CONTRACT_DESK_SELL_SLOT, 0, 0),
    ]);
  } finally {
    client.write = originalWrite;
  }
  assert.equal(salePackets.length, 2, 'Both sell clicks must be sent as protocol window-click attempts');
  assert.ok(salePackets.every(packet => packet.windowId === windowId && packet.slot === CONTRACT_DESK_SELL_SLOT),
    'Both protocol clicks must target the same desk sell slot');
  assert.ok(clickResults.some(result => result.status === 'fulfilled'),
    `At least one sell click must be acknowledged; outcomes: ${clickResults.map(result => result.status).join(', ')}`);
  await waitUntil(() => count(player, 'stone') === 2, {
    signal,
    message: 'The NPC desk did not consume exactly the offered two stones',
  });
  const paidBalance = player.messageBuffer.length;
  player.chat('/balance');
  await expect(player).toHaveReceivedMessage(/You have 249[.,]92(?: coins?)?!/i, { since: paidBalance });

  await waitUntil(() => player.bot.currentWindow?.id === windowId && desk.title !== undefined &&
    player.bot.currentWindow?.slots[CONTRACT_DESK_DEPOSIT_SLOT] == null, {
    signal,
    message: 'The completed sale left items in the NPC desk deposit slot',
  });

  await waitUntil(() => {
    const nextUnitPrice = contractUnitPriceMinor(desk);
    return nextUnitPrice !== null && nextUnitPrice < firstUnitPrice;
  }, { signal, message: 'The live NPC desk did not show its reduced post-sale price' });
  assert.equal(contractUnitPriceMinor(desk), 12481, 'The NPC desk price must reflect the accepted supply');
  assert.equal(count(player, 'stone'), 2, 'The repeated sell action consumed the untouched player batch');
});

test('leaving Origin leaves the contract GUI read-only', async ({ player, signal }) => {
  const native = observeNativeDialog(player, signal);
  try {
    await player.makeOp();
    await player.giveItem('stone', 1);
    await waitUntil(() => count(player, 'stone') === 1, { signal, message: 'Setup stone was not delivered' });
    player.chat('/execute in minecraft:the_nether run tp @s 0 80 0');
    await waitUntil(() => player.bot.game.dimension === 'the_nether', { signal, message: 'Player did not leave Origin' });
    await player.deOp();
    await native.command('/arc contracts open spawn');
    await native.click('E2E stone order');
    assert.match(native.bodyText(), /Сдача у конторщика на спавне/i);
    const confirm = native.action(/Недоступно.*Сдать ресурсы/i);
    assert.ok(confirm, 'Read-only contract did not expose an unavailable action');
    native.send(confirm);
    const afterAttempt = player.messageBuffer.length;
    player.chat('/balance');
    await expect(player).toHaveReceivedMessage(/You have 0(?:[.,]0{1,2})?(?: coins?)?!/i, { since: afterAttempt });
    assert.equal(count(player, 'stone'), 1, 'read-only contract GUI consumed resources outside Origin');
  } finally { native.close(); }
});
