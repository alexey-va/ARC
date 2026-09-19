import assert from 'node:assert/strict';
import { test, waitUntil } from '@drownek/plugwright';

function unwrap(value) {
  if (Array.isArray(value)) return value.map(unwrap);
  if (!value || typeof value !== 'object') return value;
  if (typeof value.type === 'string' && 'value' in value) {
    return unwrap(value.type === 'list' ? value.value.value : value.value);
  }
  return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, unwrap(child)]));
}
function plain(value) {
  if (typeof value === 'string') return value;
  if (Array.isArray(value)) return value.map(plain).join('');
  return value && typeof value === 'object' ? (value.text ?? '') + plain(value.extra) : '';
}

// NBT uses Java modified UTF-8: supplementary glyphs are two encoded UTF-16
// surrogates. The pinned prismarine-nbt decoder reads ordinary UTF-8 and loses
// them, so verify the actual wire bytes instead of accepting replacement text.
function nbtGlyph(codePoint) {
  const text = String.fromCodePoint(codePoint);
  return Buffer.from([text.charCodeAt(0), text.charCodeAt(1)].flatMap(unit => [
    0xe0 | (unit >> 12), 0x80 | ((unit >> 6) & 0x3f), 0x80 | (unit & 0x3f),
  ]));
}
const spacingGlyphs = Array.from({ length: 10 }, (_, index) => nbtGlyph(0xf0f01 + index));

test('table and divider gallery sends all specimens with pack spacing and working page/back commands', async ({ player, signal }) => {
  await player.makeOp();
  let dialog;
  let wireDialog;
  const observe = packet => { dialog = unwrap(packet.dialog?.data ?? packet.dialog ?? packet); };
  const observeWire = buffer => { wireDialog = buffer; };
  player.bot._client.on('show_dialog', observe);
  player.bot._client.on('raw.show_dialog', observeWire);
  async function open(command) {
    dialog = undefined;
    wireDialog = undefined;
    player.chat(command);
    await waitUntil(() => !!dialog?.body && !!wireDialog, { signal, timeout: 15000, message: `No native dialog for ${command}` });
  }
  try {
    await open('/arc dialogdemo');
    const rootCommands = dialog.actions.map(button => button.action?.command);
    assert.ok(rootCommands.includes('/arc dialogdemo tables'));
    assert.ok(rootCommands.includes('/arc dialogdemo dividers'));
    for (const [family, pageCount, perPage, prefix] of [['tables', 4, 3, 'Т'], ['dividers', 3, 6, 'Р']]) {
      await open(`/arc dialogdemo ${family}`);
      for (let page = 0; page < pageCount; page++) {
        const contents = dialog.body.map(body => plain(body.contents)).join('\n');
        for (let specimen = page * perPage + 1; specimen <= (page + 1) * perPage; specimen++) {
          assert.ok(contents.includes(`${prefix}${String(specimen).padStart(2, '0')} ·`));
        }
        assert.ok(spacingGlyphs.some(glyph => wireDialog.includes(glyph)),
          'Native dialog packet must contain a resource-pack spacing glyph');
        assert.ok(!contents.includes('Ресурспак ещё не загружен'));
        assert.equal(dialog.actions.length, pageCount);
        assert.equal(dialog.exit_action.action.command, '/arc dialogdemo root');
        if (page + 1 < pageCount) {
          const next = dialog.actions.find(button => button.action?.command === `/arc dialogdemo ${family}-${page + 2}`);
          assert.ok(next);
          await open(next.action.command);
        }
      }
      await open(dialog.exit_action.action.command);
      assert.ok(plain(dialog.title).includes('витрина'));
    }
  } finally {
    player.bot._client.off('show_dialog', observe);
    player.bot._client.off('raw.show_dialog', observeWire);
  }
});
