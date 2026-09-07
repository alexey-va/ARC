import assert from 'node:assert/strict';
import { test, expect } from '@drownek/plugwright';

test('measurement rollover requires explicit permission and generation confirmation', async ({ player }) => {
  await player.deOp();
  let offset = player.messageBuffer.length;
  player.chat('/arc audit reset-period confirm 0');
  await expect(player).toHaveReceivedMessage(/прав|permission/i, { since: offset });
  await player.makeOp();
  offset = player.messageBuffer.length;
  player.chat('/arc audit reset-period');
  await expect(player).toHaveReceivedMessage(/confirm 0/, { since: offset });
  offset = player.messageBuffer.length;
  player.chat('/arc audit reset-period typo');
  await expect(player).toHaveReceivedMessage(/Использование|usage/i, { since: offset });
  offset = player.messageBuffer.length;
  player.chat('/arc audit reset-period status');
  await expect(player).toHaveReceivedMessage(/поколение.*0/i, { since: offset });
  offset = player.messageBuffer.length;
  player.chat('/arc audit reset-period confirm 0');
  await expect(player).toHaveReceivedMessage(/Период измерений сброшен|Новый период начат/i, { since: offset });
  offset = player.messageBuffer.length;
  player.chat('/arc audit reset-period confirm 0');
  await expect(player).toHaveReceivedMessage(/Запрос не завершён/i, { since: offset });
  offset = player.messageBuffer.length;
  player.chat('/arc audit reset-period status');
  await expect(player).toHaveReceivedMessage(/поколение.*1/i, { since: offset });
});
