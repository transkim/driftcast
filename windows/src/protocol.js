'use strict';
// Driftcast wire protocol (mirror of android/.../Proto.java).
const os = require('os');

const STREAM_PORT = 47900;
const BEACON_PORT = 47901;
const HELLO = 'DRIFTCAST1 ';
const T = {
  VIDEO_CONFIG: 1, VIDEO_DELTA: 2, VIDEO_KEY: 3, AUDIO_PCM: 4, BYE: 5, PING: 6,
  REQUEST_KEY: 16, STOP: 17,
};
const HEADER = 13;
const MAX_PACKET = 8 * 1024 * 1024;

function packet(type, pts = 0, data = null) {
  const len = data ? data.length : 0;
  const b = Buffer.alloc(HEADER + len);
  b.writeUInt8(type, 0);
  b.writeUInt32BE(len, 1);
  b.writeBigUInt64BE(BigInt(Math.max(0, Math.floor(pts))), 5);
  if (len) Buffer.from(data.buffer, data.byteOffset, data.length).copy(b, HEADER);
  return b;
}

/** Turns a byte stream into packets. push() returns the complete packets. */
class PacketParser {
  constructor() { this.chunks = []; this.size = 0; }
  push(buf) {
    this.chunks.push(buf);
    this.size += buf.length;
    const out = [];
    while (this.size >= HEADER) {
      const head = this._peek(HEADER);
      const len = head.readUInt32BE(1);
      if (len > MAX_PACKET) throw new Error('bad packet length ' + len);
      if (this.size < HEADER + len) break;
      const all = this._take(HEADER + len);
      out.push({ type: all[0], pts: Number(all.readBigUInt64BE(5)), data: all.subarray(HEADER) });
    }
    return out;
  }
  _peek(n) {
    if (this.chunks[0].length >= n) return this.chunks[0];
    const b = Buffer.concat(this.chunks);
    this.chunks = [b];
    return b;
  }
  _take(n) {
    let b = this.chunks[0];
    if (b.length < n) { b = Buffer.concat(this.chunks); this.chunks = [b]; }
    const out = b.subarray(0, n);
    if (b.length === n) this.chunks.shift(); else this.chunks[0] = b.subarray(n);
    this.size -= n;
    return out;
  }
}

/** Reads the first \n-terminated line; returns { line, rest } or null if incomplete. */
function takeLine(buf) {
  const i = buf.indexOf(10);
  if (i < 0) return null;
  return { line: buf.subarray(0, i).toString('utf8').trim(), rest: buf.subarray(i + 1) };
}

function ipv4Interfaces() {
  const out = [];
  for (const [name, list] of Object.entries(os.networkInterfaces())) {
    for (const a of list || []) {
      if (a.family !== 'IPv4' && a.family !== 4) continue;
      if (a.internal) continue;
      out.push({ name, address: a.address, netmask: a.netmask });
    }
  }
  return out;
}

function broadcastAddress(address, netmask) {
  const a = address.split('.').map(Number);
  const m = netmask.split('.').map(Number);
  return a.map((x, i) => (x | (~m[i] & 255)) & 255).join('.');
}

function broadcastTargets() {
  const set = new Set(['255.255.255.255']);
  for (const i of ipv4Interfaces()) set.add(broadcastAddress(i.address, i.netmask));
  return [...set];
}

/** Codec string for WebCodecs from an Annex B SPS NAL (with start code). */
function avcCodecString(sps) {
  let i = 0;
  while (i < sps.length && sps[i] === 0) i++;
  i++; // the 0x01 of the start code
  // sps[i] is the NAL header (type 7), then profile, constraints, level
  const hex = (x) => x.toString(16).padStart(2, '0').toUpperCase();
  return 'avc1.' + hex(sps[i + 1]) + hex(sps[i + 2]) + hex(sps[i + 3]);
}

/** Splits Annex B data into NAL units (each keeps its start code). */
function splitNals(d, from = 0) {
  const starts = [];
  for (let i = from; i + 3 <= d.length; i++) {
    if (d[i] === 0 && d[i + 1] === 0) {
      if (d[i + 2] === 1) { starts.push([i, 3]); i += 2; }
      else if (i + 4 <= d.length && d[i + 2] === 0 && d[i + 3] === 1) { starts.push([i, 4]); i += 3; }
    }
  }
  return starts.map(([s, sc], k) => {
    const end = k + 1 < starts.length ? starts[k + 1][0] : d.length;
    return { type: d[s + sc] & 0x1f, data: d.subarray(s, end) };
  });
}

module.exports = {
  STREAM_PORT, BEACON_PORT, HELLO, T, HEADER, packet, PacketParser, takeLine,
  ipv4Interfaces, broadcastAddress, broadcastTargets, avcCodecString, splitNals,
};
