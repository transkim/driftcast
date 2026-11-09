'use strict';
const test = require('node:test');
const assert = require('node:assert');
const P = require('../src/protocol');

test('packets survive arbitrary chunking', () => {
  const a = P.packet(P.T.VIDEO_KEY, 123456789, Buffer.from([1, 2, 3, 4, 5]));
  const b = P.packet(P.T.PING, 0);
  const c = P.packet(P.T.AUDIO_PCM, 42, Buffer.alloc(3840, 7));
  const all = Buffer.concat([a, b, c]);
  for (const step of [1, 2, 7, 13, 100, all.length]) {
    const parser = new P.PacketParser();
    const got = [];
    for (let i = 0; i < all.length; i += step) got.push(...parser.push(all.subarray(i, i + step)));
    assert.deepStrictEqual(got.map((p) => p.type), [P.T.VIDEO_KEY, P.T.PING, P.T.AUDIO_PCM]);
    assert.strictEqual(got[0].pts, 123456789);
    assert.deepStrictEqual([...got[0].data], [1, 2, 3, 4, 5]);
    assert.strictEqual(got[2].data.length, 3840);
  }
});

test('header layout matches the Android side', () => {
  const h = P.packet(3, 0x0102030405, Buffer.from([9]));
  assert.deepStrictEqual([...h], [3, 0, 0, 0, 1, 0, 0, 0, 1, 2, 3, 4, 5, 9]);
});

test('rejects absurd packet sizes', () => {
  const bad = Buffer.from([2, 0x7f, 0xff, 0xff, 0xff, 0, 0, 0, 0, 0, 0, 0, 0]);
  assert.throws(() => new P.PacketParser().push(bad));
});

test('hello line split from the stream', () => {
  const got = P.takeLine(Buffer.from('DRIFTCAST1 {"name":"x"}\nabc'));
  assert.strictEqual(got.line, 'DRIFTCAST1 {"name":"x"}');
  assert.strictEqual(got.rest.toString(), 'abc');
  assert.strictEqual(P.takeLine(Buffer.from('partial')), null);
});

test('codec string and NAL split', () => {
  const csd = Buffer.from([0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28, 0xac, 0, 0, 0, 1, 0x68, 0xee, 0x3c, 0x80]);
  const nals = P.splitNals(csd);
  assert.deepStrictEqual(nals.map((n) => n.type), [7, 8]);
  assert.strictEqual(P.avcCodecString(nals[0].data), 'avc1.640028');
});

test('broadcast address', () => {
  assert.strictEqual(P.broadcastAddress('192.168.1.23', '255.255.255.0'), '192.168.1.255');
  assert.strictEqual(P.broadcastAddress('10.0.5.9', '255.255.0.0'), '10.0.255.255');
});
