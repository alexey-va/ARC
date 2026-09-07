import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import net from 'node:net';
import { createRequire } from 'node:module';
import { test, waitUntil } from '@drownek/plugwright';

const require = createRequire(import.meta.url);

// The fixture exposes only its disposable loopback Redis, never production data.
function redis(endpoint, ...args) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection(endpoint);
    let response = Buffer.alloc(0);
    socket.setTimeout(5000, () => socket.destroy(new Error('Redis fixture timeout')));
    socket.on('error', reject);
    socket.on('connect', () => socket.write(Buffer.concat([
      Buffer.from(`*${args.length}\r\n`), ...args.map(arg => {
        const value = Buffer.from(String(arg));
        return Buffer.concat([Buffer.from(`$${value.length}\r\n`), value, Buffer.from('\r\n')]);
      }),
    ])));
    socket.on('data', chunk => {
      response = Buffer.concat([response, chunk]);
      const end = response.indexOf('\r\n');
      if (end < 0) return;
      const header = response.subarray(0, end).toString();
      let value = header.slice(1);
      if (header[0] === '$') {
        const size = Number(value);
        if (size >= 0 && response.length < end + 2 + size + 2) return;
        value = size < 0 ? null : response.subarray(end + 2, end + 2 + size).toString();
      }
      socket.destroy();
      if (header[0] === '-') reject(new Error(header)); else resolve(value);
    });
  });
}

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
function varint(value) {
  const bytes = [];
  do { const byte = value & 127; value >>>= 7; bytes.push(byte | (value ? 128 : 0)); } while (value);
  return Buffer.from(bytes);
}
function nbtString(value) {
  const bytes = Buffer.from(value); const length = Buffer.alloc(2); length.writeUInt16BE(bytes.length);
  return Buffer.concat([length, bytes]);
}
function sendClick(player, id, input) {
  const mappings = require('minecraft-data')(player.bot.version).protocol.play.toServer.types.packet[1][0].type[1].mappings;
  const packetId = Object.keys(mappings).find(key => mappings[key] === 'custom_click_action');
  assert.ok(packetId, 'Client protocol has no native dialog action packet');
  const payload = input === undefined ? Buffer.from([10, 0]) : Buffer.concat([
    Buffer.from([10, 8]), nbtString('message'), nbtString(input), Buffer.from([0]),
  ]);
  const key = Buffer.from(id);
  // Minecraft uses a byte-length-prefixed anonymous NBT payload, not option<NBT>.
  player.bot._client.writeRaw(Buffer.concat([varint(Number(packetId)), varint(key.length), key, varint(payload.length), payload]));
}

async function seed(endpoint, playerName) {
  const entry = (id, message) => ({ id, message, displayName: id, material: 'PAPER', rank: 'Для всех' });
  const catalog = { catalogId: 'catalog', schemaVersion: 1, revision: 'join-e2e', updatedAt: Date.now(),
    joinPrefix: '<green>● ', leavePrefix: '<red>◆ ',
    join: Array.from({ length: 13 }, (_, i) => entry(`join_${i}`, `%player_name% фраза ${i}`)),
    leave: [entry('leave_0', '%player_name% ушёл')] };
  const personal = { player: playerName, joinMessages: ['%player_name% legacy welcome'], leaveMessages: [],
    customJoinMessages: ['legacy welcome'], customLeaveMessages: [], timestamp: Date.now() };
  for (const [key, id, data] of [['arc.join_message_catalog', 'catalog', catalog], ['arc.join_messages', playerName, personal]]) {
    await redis(endpoint, 'HSET', key, id, JSON.stringify(data));
    await redis(endpoint, 'PUBLISH', `${key}_update`, JSON.stringify({ type: 'UPDATE', id, data: JSON.stringify(data) }));
  }
}

test('native join editor replaces and removes the full prefix and persists the selection', async ({ player, signal }) => {
  await player.makeOp();
  const endpoint = JSON.parse(await fs.readFile(new URL('../../../build/plugwright-e2e-generated/redis-test.json', import.meta.url)));
  assert.ok(['localhost', '127.0.0.1'].includes(endpoint.host));
  const username = player.bot.username;
  await seed(endpoint, username);
  let dialog;
  const observe = packet => { dialog = unwrap(packet.dialog?.data ?? packet.dialog ?? packet); };
  player.bot._client.on('show_dialog', observe);
  const actions = () => [...(dialog?.actions ?? []), dialog?.exit_action].filter(Boolean);
  const button = key => actions().find(action => action.action?.id?.endsWith(`/${key}`));
  const ready = key => waitUntil(() => !!button(key), { signal, timeout: 15000, message: `Native dialog missing ${key}` });
  async function click(key, destination, input) {
    const action = button(key); assert.ok(action, `No ${key} action`);
    dialog = undefined;
    sendClick(player, action.action.id, input);
    await ready(destination);
  }
  try {
    player.chat('/arc joinmessage');
    await ready('own_0');
    assert.match(plain(button('own_0').label), /★ ✔ ● .*legacy welcome/);
    await click('previous', 'previous');
    assert.match(plain(dialog.body[1].contents), /3\/3/);
    await click('next', 'own_0');
    assert.match(plain(dialog.body[1].contents), /1\/3/);
    await click('custom', 'custom_0');
    await click('custom_0', 'edit');
    await click('edit', 'preview');
    assert.equal(dialog.inputs[0].initial, '<green>● %player_name% legacy welcome');
    for (const template of ['<gold>◆ %player_name% <gray>updated', '<aqua>%player_name% без точки']) {
      await click('preview', 'save', template);
      const shown = plain(dialog.body[0].contents);
      assert.ok(!shown.includes('●'), 'Network dot leaked into full preview');
      if (template.includes('◆')) assert.ok(shown.startsWith('◆ '));
      else assert.equal(shown, `${username} без точки`);
      await click('save', 'custom_0');
      const stored = JSON.parse(await redis(endpoint, 'HGET', 'arc.join_messages', username));
      assert.deepEqual(stored.customJoinMessages, [template]);
      assert.deepEqual(stored.joinMessages, [`<reset>${template}`]);
      await click('custom_0', 'edit');
      await click('edit', 'preview');
      assert.equal(dialog.inputs[0].initial, template);
    }
  } finally {
    player.bot._client.off('show_dialog', observe);
    await player.deOp();
  }
});
