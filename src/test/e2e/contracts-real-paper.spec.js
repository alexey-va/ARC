import assert from 'node:assert/strict';
import { test, expect, waitUntil } from '@drownek/plugwright';
import { observeNativeDialog } from './native-dialog.js';

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
    await native.wait();
    assert.ok(native.action('E2E stone order'), 'Board card did not open the native contract catalog');
    await player.deOp();
  } finally { native.close(); }
});

test('an Origin quote consumes two real items once and a second click cannot duplicate it', async ({ player, signal }) => {
  const native = observeNativeDialog(player, signal);
  try {
    await player.makeOp();
    await player.giveItem('stone', 4);
    await waitUntil(() => count(player, 'stone') === 4, { signal, message: 'Setup stone was not delivered' });
    await player.deOp();
    const initialBalance = player.messageBuffer.length;
    player.chat('/balance');
    await expect(player).toHaveReceivedMessage(/You have 0(?:[.,]0{1,2})?(?: coins?)?!/i, { since: initialBalance });
    await native.command('/arc contracts open spawn');
    await native.click('E2E stone order');
    const quantity = native.inputInitial('quantity');
    assert.equal(quantity, 2, 'Contract dialog default must select the two-item batch');
    await native.click('Проверить сдачу', undefined, { quantity });
    const firstQuote = native.bodyText();
    assert.match(firstQuote, /249[.,]92/);
    const confirm = native.action(text => /^Сдать(?:\s|$)/.test(text));
    assert.ok(confirm, 'Confirmation dialog did not expose a submit action');
    native.send(confirm);
    native.send(confirm);
    await waitUntil(() => count(player, 'stone') === 2, {
      signal,
      message: 'Contract confirmation did not escrow exactly two stones',
    });
    assert.equal(count(player, 'stone'), 2, 'double click consumed more than one batch');
    const paidBalance = player.messageBuffer.length;
    player.chat('/balance');
    await expect(player).toHaveReceivedMessage(/You have 249[.,]92(?: coins?)?!/i, { since: paidBalance });
    await native.command('/arc contracts open spawn');
    await native.click('E2E stone order');
    const nextQuantity = native.inputInitial('quantity');
    assert.equal(nextQuantity, 2, 'Reopened contract dialog default must select the two-item batch');
    await native.click('Проверить сдачу', undefined, { quantity: nextQuantity });
    const nextQuote = native.bodyText();
    const quoteValue = text => Number.parseFloat(text.match(/\b\d+[.,]\d{2}\b/)[0].replace(',', '.'));
    assert.equal(quoteValue(firstQuote), 249.92);
    assert.equal(quoteValue(nextQuote), 249.62);
    assert.ok(quoteValue(nextQuote) < quoteValue(firstQuote), 'dynamic price did not decrease after accepted supply');
  } finally { native.close(); }
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
