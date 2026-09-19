import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { waitUntil } from '@drownek/plugwright';

const require = createRequire(import.meta.url);

function unwrap(value) {
  if (Array.isArray(value)) return value.map(unwrap);
  if (!value || typeof value !== 'object') return value;
  if (typeof value.type === 'string' && 'value' in value) {
    return unwrap(value.type === 'list' ? value.value.value : value.value);
  }
  return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, unwrap(child)]));
}

export function plain(value) {
  if (typeof value === 'string') return value;
  if (Array.isArray(value)) return value.map(plain).join('');
  return value && typeof value === 'object' ? (value.text ?? '') + plain(value.extra) : '';
}

function varint(value) {
  const bytes = [];
  do {
    const byte = value & 127;
    value >>>= 7;
    bytes.push(byte | (value ? 128 : 0));
  } while (value);
  return Buffer.from(bytes);
}

function nbtString(value) {
  const bytes = Buffer.from(value);
  const length = Buffer.alloc(2);
  length.writeUInt16BE(bytes.length);
  return Buffer.concat([length, bytes]);
}

function nbtCompound(fields) {
  return Buffer.concat([
    Buffer.from([10]),
    ...fields.flatMap(([name, type, value]) => [Buffer.from([type]), nbtString(name), value]),
    Buffer.from([0]),
  ]);
}

function inputNbt(inputs) {
  return Object.entries(inputs ?? {}).map(([name, value]) => {
    if (typeof value === 'number') {
      const encoded = Buffer.alloc(4);
      encoded.writeFloatBE(value);
      return [name, 5, encoded];
    }
    if (typeof value === 'string') return [name, 8, nbtString(value)];
    throw new TypeError(`Unsupported native dialog input ${name}`);
  });
}

export function sendNativeClick(player, id, inputs = {}) {
  const mappings = require('minecraft-data')(player.bot.version)
    .protocol.play.toServer.types.packet[1][0].type[1].mappings;
  const packetId = Object.keys(mappings).find(key => mappings[key] === 'custom_click_action');
  assert.ok(packetId, 'Client protocol has no native dialog action packet');
  const payload = nbtCompound(inputNbt(inputs));
  const key = Buffer.from(id);
  player.bot._client.writeRaw(Buffer.concat([
    varint(Number(packetId)), varint(key.length), key, varint(payload.length), payload,
  ]));
}

function matches(value, matcher) {
  if (typeof matcher === 'function') return matcher(value);
  if (matcher instanceof RegExp) return matcher.test(value);
  return value.includes(matcher);
}

export function observeNativeDialog(player, signal) {
  let dialog;
  let client;
  const observe = packet => {
    dialog = unwrap(packet.dialog?.data ?? packet.dialog ?? packet);
  };
  const reattach = () => {
    const next = player.bot._client;
    if (client === next) return;
    client?.off('show_dialog', observe);
    client = next;
    client.on('show_dialog', observe);
    dialog = undefined;
  };
  reattach();

  const wait = async (message = 'No native dialog arrived') => {
    reattach();
    await waitUntil(() => !!dialog?.body, { signal, timeout: 15000, message });
    return dialog;
  };
  const clear = () => { dialog = undefined; };
  const command = commandText => {
    reattach();
    clear();
    player.chat(commandText);
    return wait(`No native dialog after ${commandText}`);
  };
  const actions = () => [...(dialog?.actions ?? []), dialog?.exit_action].filter(Boolean);
  const action = matcher => actions().find(button => matches(plain(button.label), matcher));
  const bodyText = () => (dialog?.body ?? []).map(body => plain(body.contents)).join('\n');
  const numericDefaults = () => Object.fromEntries((dialog?.inputs ?? [])
    .filter(input => typeof input.key === 'string' && typeof input.initial === 'number')
    .map(input => [input.key, input.initial]));
  const inputInitial = id => {
    reattach();
    const input = (dialog?.inputs ?? []).find(candidate => candidate.key === id);
    assert.ok(input, `No native dialog input named ${id}`);
    assert.equal(typeof input.initial, 'number', `Native dialog input ${id} has no numeric default`);
    return input.initial;
  };
  const click = async (matcher, message, inputs) => {
    reattach();
    const button = action(matcher);
    assert.ok(button?.action?.id, `No native dialog action matching ${matcher}`);
    const payload = inputs ?? numericDefaults();
    clear();
    sendNativeClick(player, button.action.id, payload);
    return wait(message ?? `No native dialog after clicking ${matcher}`);
  };
  const send = (matcher, inputs) => {
    reattach();
    const button = typeof matcher === 'object' && matcher?.action?.id ? matcher : action(matcher);
    assert.ok(button?.action?.id, `No native dialog action matching ${matcher}`);
    sendNativeClick(player, button.action.id, inputs ?? numericDefaults());
    return button;
  };

  return {
    wait,
    command,
    reattach,
    clear,
    action,
    actions,
    bodyText,
    inputInitial,
    click,
    send,
    close: () => {
      client?.off('show_dialog', observe);
      client = undefined;
    },
  };
}
