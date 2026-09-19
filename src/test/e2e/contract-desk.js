import assert from 'node:assert/strict';
import { waitUntil } from '@drownek/plugwright';

const CONTRACT_NPC_CLICK_RADIUS = 4.5;

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
}
