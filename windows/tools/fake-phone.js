'use strict';
// Pretends to be the Android sender: finds a receiver by its beacon, then streams
// an H.264 test pattern plus a tone, packetised exactly like CastService does.
// Usage: node tools/fake-phone.js [seconds] [host:port]
const { spawn } = require('child_process');
const dgram = require('dgram');
const net = require('net');
const P = require('../src/protocol');

const seconds = Number(process.argv[2] || 8);
const manual = process.argv[3];

function discover() {
  if (manual) {
    const [host, port] = manual.split(':');
    return Promise.resolve({ host, port: Number(port || P.STREAM_PORT), name: host });
  }
  return new Promise((resolve, reject) => {
    const s = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    const t = setTimeout(() => { s.close(); reject(new Error('no receiver beacon heard')); }, 8000);
    s.on('message', (msg, rinfo) => {
      try {
        const o = JSON.parse(msg);
        if (o.app !== 'driftcast') return;
        clearTimeout(t);
        s.close();
        resolve({ host: rinfo.address, port: o.port, name: o.name, kind: o.kind });
      } catch {}
    });
    s.bind(P.BEACON_PORT);
  });
}

function h264() {
  // 720x1280 portrait like a phone, 30 fps, no repeated headers (like MediaCodec)
  return spawn('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-re', '-f', 'lavfi',
    '-i', `testsrc2=size=720x1280:rate=30:duration=${seconds}`,
    '-c:v', 'libx264', '-preset', 'ultrafast', '-profile:v', 'high', '-bf', '0',
    '-pix_fmt', 'yuv420p', '-g', '60', '-x264-params', 'slices=1:sliced-threads=0:rc-lookahead=0',
    '-bsf:v', 'h264_mp4toannexb', '-f', 'h264', '-'], { stdio: ['ignore', 'pipe', 'inherit'] });
}

(async () => {
  const r = await discover();
  console.log('found', r.name, r.kind || '', r.host + ':' + r.port);
  const sock = net.connect(r.port, r.host);
  sock.setNoDelay(true);
  await new Promise((res, rej) => { sock.once('connect', res); sock.once('error', rej); });
  sock.write(P.HELLO + JSON.stringify({ name: 'Test phone', id: 'fake-phone-1', audio: true }) + '\n');
  const reply = await new Promise((res) => {
    let b = Buffer.alloc(0);
    const on = (d) => { b = Buffer.concat([b, d]); const got = P.takeLine(b); if (got) { sock.off('data', on); res(got.line); } };
    sock.on('data', on);
  });
  console.log('receiver says', reply);
  if (!JSON.parse(reply).ok) process.exit(2);

  let keyAsks = 0;
  const parser = new P.PacketParser();
  sock.on('data', (d) => {
    for (const p of parser.push(d)) {
      if (p.type === P.T.REQUEST_KEY) keyAsks++;
      if (p.type === P.T.STOP) { console.log('receiver pressed Stop'); process.exit(0); }
    }
  });

  const ff = h264();
  let buf = Buffer.alloc(0);
  let sps = null, pps = null, sentConfig = false, frames = 0;
  let au = [], auKey = false;
  const flush = () => {
    if (!au.length) return;
    if (!sentConfig && sps && pps) {
      const head = Buffer.alloc(4);
      head.writeUInt16BE(720, 0); head.writeUInt16BE(1280, 2);
      sock.write(P.packet(P.T.VIDEO_CONFIG, 0, Buffer.concat([head, sps, pps])));
      sentConfig = true;
    }
    sock.write(P.packet(auKey ? P.T.VIDEO_KEY : P.T.VIDEO_DELTA, now(), Buffer.concat(au)));
    frames++;
    au = []; auKey = false;
  };
  const t0 = process.hrtime.bigint();
  const now = () => Number((process.hrtime.bigint() - t0) / 1000n);
  ff.stdout.on('data', (d) => {
    buf = Buffer.concat([buf, d]);
    const nals = P.splitNals(buf);
    if (nals.length < 2) return;
    for (const n of nals.slice(0, -1)) {
      if (n.type === 7) sps = Buffer.from(n.data);
      else if (n.type === 8) pps = Buffer.from(n.data);
      else if (n.type === 5 || n.type === 1) {
        // a new picture starts at a slice whose first_mb_in_slice is 0 (first bit 1)
        const sc = n.data[2] === 1 ? 3 : 4;
        const first = (n.data[sc + 1] & 0x80) !== 0;
        if (first) flush();
        au.push(n.data);
        auKey = auKey || n.type === 5;
      }
    }
    const last = nals[nals.length - 1];
    buf = Buffer.from(last.data);
  });

  // 440 Hz tone, 20 ms chunks
  let phase = 0;
  const tone = setInterval(() => {
    const b = Buffer.alloc(3840);
    for (let i = 0; i < 960; i++) {
      const v = Math.round(Math.sin(phase) * 6000);
      phase += (2 * Math.PI * 440) / 48000;
      b.writeInt16LE(v, i * 4); b.writeInt16LE(v, i * 4 + 2);
    }
    sock.write(P.packet(P.T.AUDIO_PCM, now(), b));
  }, 20);

  ff.on('close', () => {
    flush();
    clearInterval(tone);
    console.log(`sent ${frames} frames, receiver asked for ${keyAsks} key frames`);
    sock.end(P.packet(P.T.BYE));
    setTimeout(() => process.exit(0), 300);
  });
})().catch((e) => { console.error(e.message); process.exit(1); });
