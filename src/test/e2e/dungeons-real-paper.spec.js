import { test, expect } from '@drownek/plugwright';

test('ArcDungeons owns its commands and shared service when EliteMobs is absent', async ({ player }) => {
  await player.makeOp();
  const bootstrapSince = player.getMessageBufferIndex();
  player.chat('/arc-dungeons-e2e-check');
  await expect(player).toHaveReceivedMessage(/ARC_DUNGEONS_E2E_READY/, { since: bootstrapSince });

  const reloadSince = player.getMessageBufferIndex();
  player.chat('/arc reload');
  await expect(player).toHaveReceivedMessage(/Перезагружено|reload.*(?:success|complete)/i, { since: reloadSince });
  const afterReload = player.getMessageBufferIndex();
  player.chat('/arc-dungeons-e2e-check');
  await expect(player).toHaveReceivedMessage(/ARC_DUNGEONS_E2E_READY/, { since: afterReload });

  await player.deOp();

  const unavailableSince = player.getMessageBufferIndex();
  player.chat('/dungeonstart');
  await expect(player).toHaveReceivedMessage(/Данжи сейчас недоступны\./, { since: unavailableSince });
});
