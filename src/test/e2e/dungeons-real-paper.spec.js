import { test, expect } from '@drownek/plugwright';

test('ArcDungeons owns its services and reports native unavailability to an operator when EliteMobs is absent', async ({ player }) => {
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

  // Keep OP: this Redis-disabled fixture cannot initialize ARC's non-OP glyph guard.
  // This verifies operator command routing, not ordinary-player chat acceptance.
  const unavailableSince = player.getMessageBufferIndex();
  player.chat('/dungeonstart');
  await expect(player).toHaveReceivedMessage(/Данжи сейчас недоступны\./, { since: unavailableSince });
});
