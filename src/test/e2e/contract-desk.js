import assert from 'node:assert/strict';
import { poll, waitUntil } from '@drownek/plugwright';

const CONTRACT_NPC_CLICK_RADIUS = 4.5;
const CONTRACT_DESK_INVENTORY_START = 54;

export const CONTRACT_DESK_SELL_SLOT = 4;
export const CONTRACT_DESK_DEPOSIT_SLOT = 5;

export function contractDeskContents(player) {
  const gui = player.getCurrentGui();
  if (!gui) return '(no current GUI)';
  const items = gui.items.map(item =>
    `slot=${item.slot} ${item.name} x${item.count} name=${JSON.stringify(item.getDisplayName())} lore=${JSON.stringify(item.getLore())}`,
  );
  return `title=${JSON.stringify(gui.title)}\n${items.length ? items.join('\n') : '(no inventory items received)'}`;
}

export async function clickContractDesk(player, signal) {
  let desk;
  await waitUntil(() => {
    desk = Object.values(player.bot.entities).find(entity =>
      entity.type === 'player' && entity.username === 'E2E_Desk');
    return !!desk;
  }, {
    signal,
    timeout: 15000,
    message: 'Contract test desk NPC did not reach the client',
  });
  assert.ok(desk, 'Contract test desk NPC was not observed by the client');
  await waitUntil(() => player.bot.entity?.onGround === true, {
    signal,
    timeout: 5000,
    message: 'Player did not settle on the Origin ground before the desk click',
  });
  const distance = player.bot.entity.position.distanceTo(desk.position);
  assert.ok(
    distance <= CONTRACT_NPC_CLICK_RADIUS,
    `Player is ${distance.toFixed(2)} blocks from the contract desk; click radius is ${CONTRACT_NPC_CLICK_RADIUS}`,
  );
  await player.bot.lookAt(desk.position.offset(0, 1, 0), true);
  player.bot.activateEntity(desk);
  // The Citizens click grants the real NPC route and opens its cloud-storage
  // chest. `player.gui()` waits only for the title; it does not wait for item
  // slots or lore, so wait for this order's live locator before reading price.
  const gui = await player.gui({ title: /Заказы/i });
  const order = gui.locator(item => item.getDisplayName().includes('E2E stone order'));
  await poll(
    () => {
      const lore = order.loreText();
      return lore.includes('За штуку:') ? lore : undefined;
    },
    {
      signal,
      timeout: 5000,
      interval: 100,
      message: () => `Contract desk title opened, but order lore was not received. order=${JSON.stringify(order.displayName())}, lore=${JSON.stringify(order.loreText())}\n${contractDeskContents(player)}`,
    },
  );
  return gui;
}

export async function offerStoneBatch(player, quantity, signal) {
  const window = player.bot.currentWindow;
  assert.ok(window, 'The contract desk inventory is not open');
  const inventoryStart = window.inventoryStart ?? Math.max(
    CONTRACT_DESK_INVENTORY_START,
    window.slots.length - 36,
  );
  const stoneSlot = window.slots.findIndex((item, slot) =>
    slot >= inventoryStart && item?.name === 'stone');
  assert.ok(stoneSlot >= inventoryStart, 'No real stone stack is available to offer');
  const stack = window.slots[stoneSlot];
  const initialCount = stack.count;
  assert.ok(
    initialCount === quantity || initialCount === quantity * 2,
    `Expected ${quantity} stones or a ${quantity * 2}-stone stack; found ${initialCount}`,
  );

  // Right-click splits the four-stone fixture stack in half; when the two
  // remaining stones are reused, left-click moves that exact batch.
  await player.bot.clickWindow(stoneSlot, initialCount === quantity ? 0 : 1, 0);
  await player.bot.clickWindow(CONTRACT_DESK_DEPOSIT_SLOT, 0, 0);
  await waitUntil(() => {
    const current = player.bot.currentWindow;
    const offered = current?.slots[CONTRACT_DESK_DEPOSIT_SLOT];
    return offered?.name === 'stone' && offered.count === quantity &&
      player.bot.inventory.items().filter(item => item.name === 'stone')
        .reduce((total, item) => total + item.count, 0) === initialCount - quantity;
  }, { signal, message: `The NPC desk did not receive exactly ${quantity} stones` });
}

export function contractUnitPriceMinor(desk, contractName = 'E2E stone order') {
  const order = desk.locator(item => item.getDisplayName().includes(contractName));
  const lore = order.loreText();
  const match = lore.match(/За штуку:\s*([\d\s.,]+)/i);
  if (!match) return null;
  const amount = Number(match[1].replace(/\s/g, '').replace(',', '.'));
  return Number.isFinite(amount) ? Math.round(amount * 100) : null;
}
