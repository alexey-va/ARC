import assert from 'node:assert/strict';
import { test, expect, waitUntil } from '@drownek/plugwright';

const count = (player, name) => player.bot.inventory.items()
  .filter(item => item.name === name)
  .reduce((total, item) => total + item.count, 0);

test('direct contract submission is unavailable; the board opens the real contract GUI', async ({ player }) => {
  await player.deOp();
  const before = player.messageBuffer.length;
  player.chat('/arc contracts submit e2e_stone 1');
  await expect(player).toHaveReceivedMessage(/\/arc contracts.*open|status|donate/i, { since: before });

  player.chat('/arc board');
  const board = await player.gui({ title: /Доска объявлений/i });
  const card = board.locator(item => item.getDisplayName().includes('E2E stone order'));
  await card.click();
  await player.gui({ title: /Книга заказов/i });
  await player.deOp();
});

test('an Origin quote consumes two real items once and a second click cannot duplicate it', async ({ player, signal }) => {
  await player.makeOp();
  await player.giveItem('stone', 4);
  await waitUntil(() => count(player, 'stone') === 4, { signal, message: 'Setup stone was not delivered' });
  await player.deOp();
  const initialBalance = player.messageBuffer.length;
  player.chat('/balance');
  await expect(player).toHaveReceivedMessage(/\b0(?:[.,]0{1,2})?\b/, { since: initialBalance });
  player.chat('/arc contracts open spawn');
  const list = await player.gui({ title: /Книга заказов/i });
  await list.locator(item => item.getDisplayName().includes('E2E stone order')).click();
  const detail = await player.gui({ title: /Сдать ресурсы/i });
  const firstQuote = await detail.locator(item => item.getDisplayName().includes('Выплата:')).displayName();
  const confirm = detail.locator(item => item.getDisplayName().includes('Подтвердить'));
  await confirm.click();
  await confirm.click({ timeout: 1000 }).catch(() => undefined);
  await waitUntil(() => count(player, 'stone') === 2, {
    signal,
    message: 'Contract confirmation did not escrow exactly two stones',
  });
  assert.equal(count(player, 'stone'), 2, 'double click consumed more than one batch');
  const paidBalance = player.messageBuffer.length;
  player.chat('/balance');
  await expect(player).toHaveReceivedMessage(/\b249[.,]92\b/, { since: paidBalance });
  await player.gui({ title: /Книга заказов/i });
  await player.gui({ title: /Книга заказов/i }).then(gui =>
    gui.locator(item => item.getDisplayName().includes('E2E stone order')).click());
  const repriced = await player.gui({ title: /Сдать ресурсы/i });
  const nextQuote = await repriced.locator(item => item.getDisplayName().includes('Выплата:')).displayName();
  const quoteValue = text => Number.parseFloat(text.replace(',', '.').replace(/[^0-9.]/g, ''));
  assert.equal(quoteValue(firstQuote), 249.92);
  assert.equal(quoteValue(nextQuote), 249.62);
  assert.ok(quoteValue(nextQuote) < quoteValue(firstQuote), 'dynamic price did not decrease after accepted supply');
});

test('leaving Origin leaves the contract GUI read-only', async ({ player, signal }) => {
  await player.makeOp();
  await player.giveItem('stone', 1);
  player.chat('/execute in minecraft:the_nether run tp @s 0 80 0');
  await player.deOp();
  player.chat('/arc contracts open spawn');
  const list = await player.gui({ title: /Книга заказов/i });
  await list.locator(item => item.getDisplayName().includes('E2E stone order')).click();
  const detail = await player.gui({ title: /Сдать ресурсы/i });
  const confirm = detail.locator(item => item.getDisplayName().includes('Подтвердить'));
  await expect(confirm).toHaveLore('Сдача недоступна');
  await expect(confirm).toHaveLore('только у конторщика на спавне');
});
