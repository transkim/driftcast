'use strict';
/* global driftcast, VideoDecoder, EncodedVideoChunk */
const $ = (id) => document.getElementById(id);
const dc = window.driftcast;

// ---------------- idle screen ----------------
let current = null;
function showInfo(i) {
  current = i;
  $('name').textContent = i.name;
  const port = i.port && i.port !== 47900 ? ':' + i.port : '';
  $('addr').textContent = i.addresses.length
    ? 'Address: ' + i.addresses.map((a) => a + port).join('   ')
    : 'Not connected to a network';
  $('forget').hidden = !i.trusted;
}
dc.info().then(showInfo);
dc.onInfo(showInfo);
dc.onStatus((s) => { if (s.error) $('status').textContent = s.error; });

function openRename() {
  $('rename-input').value = current ? current.name : '';
  $('rename-box').hidden = false;
  $('rename-input').focus();
  $('rename-input').select();
}
$('name').addEventListener('click', openRename);
$('rename').addEventListener('click', openRename);
$('rename-cancel').addEventListener('click', () => { $('rename-box').hidden = true; });
$('rename-save').addEventListener('click', () => { dc.rename($('rename-input').value); $('rename-box').hidden = true; });
$('rename-input').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') $('rename-save').click();
  if (e.key === 'Escape') $('rename-cancel').click();
});
$('forget').addEventListener('click', () => { dc.forget(); $('status').textContent = 'Trusted phones forgotten'; });
$('help').addEventListener('click', () => dc.help());

// ---------------- allow / deny ----------------
let askId = null;
dc.onAsk(({ reqId, name }) => {
  askId = reqId;
  $('ask-title').textContent = name + ' wants to cast';
  $('ask').hidden = false;
  $('allow').focus();
});
dc.onAskCancel((reqId) => { if (reqId === askId) { $('ask').hidden = true; askId = null; } });
const answer = (a) => { if (askId) dc.answer(askId, a); askId = null; $('ask').hidden = true; };
$('allow').addEventListener('click', () => answer(1));
$('always').addEventListener('click', () => answer(2));
$('deny').addEventListener('click', () => answer(0));

// ---------------- watching ----------------
const canvas = $('screen');
const ctx = canvas.getContext('2d', { alpha: false, desynchronized: true });
let decoder = null;
let config = null;
let needKey = true;
let lastKeyAsk = 0;
let frames = 0;
const stats = (window.dcStats = { configs: 0, received: 0, decoded: 0, dropped: 0, errors: [] });

dc.onStart(({ name }) => {
  $('from').textContent = 'Showing ' + name;
  $('idle').hidden = true;
  $('watch').hidden = false;
  $('watch').classList.remove('has-video');
  frames = 0;
  showBar();
  startAudio();
});

dc.onEnded(() => {
  closeDecoder();
  config = null;
  stopAudio();
  $('watch').hidden = true;
  $('idle').hidden = false;
  $('status').textContent = 'Ready to receive';
  dc.fullscreen(false);
});

function closeDecoder() {
  if (decoder && decoder.state !== 'closed') { try { decoder.close(); } catch {} }
  decoder = null;
  needKey = true;
}

function askKey() {
  const now = performance.now();
  if (now - lastKeyAsk > 500) { lastKeyAsk = now; dc.requestKey(); }
}

async function makeDecoder() {
  closeDecoder();
  const cfg = { codec: config.codec, optimizeForLatency: true, codedWidth: config.w, codedHeight: config.h };
  let ok = false;
  try { ok = (await VideoDecoder.isConfigSupported(cfg)).supported; } catch {}
  if (!ok) {
    for (const codec of ['avc1.640028', 'avc1.4D4028', 'avc1.42E01F']) {
      try { if ((await VideoDecoder.isConfigSupported({ ...cfg, codec })).supported) { cfg.codec = codec; ok = true; break; } } catch {}
    }
  }
  const d = new VideoDecoder({
    output: (frame) => {
      if (canvas.width !== frame.displayWidth || canvas.height !== frame.displayHeight) {
        canvas.width = frame.displayWidth;
        canvas.height = frame.displayHeight;
      }
      ctx.drawImage(frame, 0, 0);
      frame.close();
      stats.decoded++;
      if (++frames === 1) $('watch').classList.add('has-video');
    },
    error: (e) => {
      console.warn('decoder error', e);
      stats.errors.push(String(e));
      if (decoder === d) { decoder = null; needKey = true; setTimeout(() => { if (config) makeDecoder(); }, 50); }
    },
  });
  d.configure(cfg);
  decoder = d;
  needKey = true;
  askKey();
}

dc.onConfig((c) => {
  stats.configs++;
  config = { w: c.w, h: c.h, codec: c.codec, csd: new Uint8Array(c.csd) };
  makeDecoder();
});

dc.onFrame(({ key, pts, data }) => {
  stats.received++;
  if (!decoder || decoder.state !== 'configured') { if (key) askKey(); return; }
  if (!key && needKey) return;
  if (!key && decoder.decodeQueueSize > 6) { needKey = true; askKey(); return; } // behind: wait for a fresh picture
  let bytes = new Uint8Array(data);
  if (key) {
    // Annex B key frames must carry SPS/PPS for WebCodecs
    const joined = new Uint8Array(config.csd.length + bytes.length);
    joined.set(config.csd, 0);
    joined.set(bytes, config.csd.length);
    bytes = joined;
  }
  try {
    decoder.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp: pts, data: bytes }));
    if (key) needKey = false;
  } catch (e) {
    console.warn('decode', e);
    needKey = true;
    askKey();
  }
});

// ---------------- sound ----------------
let audio = null;
let nextTime = 0;
let muted = false;
let gain = null;

function startAudio() {
  stopAudio();
  audio = new AudioContext({ sampleRate: 48000, latencyHint: 'interactive' });
  gain = audio.createGain();
  gain.gain.value = muted ? 0 : 1;
  gain.connect(audio.destination);
  nextTime = 0;
}
function stopAudio() {
  if (audio) { audio.close().catch(() => {}); audio = null; }
}

dc.onPcm((buf) => {
  if (!audio) return;
  const pcm = new Int16Array(buf.buffer, buf.byteOffset, buf.byteLength >> 1);
  const n = pcm.length >> 1;
  const ab = audio.createBuffer(2, n, 48000);
  const l = ab.getChannelData(0), r = ab.getChannelData(1);
  for (let i = 0; i < n; i++) { l[i] = pcm[2 * i] / 32768; r[i] = pcm[2 * i + 1] / 32768; }
  const now = audio.currentTime;
  if (nextTime < now + 0.03 || nextTime > now + 0.25) nextTime = now + 0.06; // keep about 60 ms of buffer
  const src = audio.createBufferSource();
  src.buffer = ab;
  src.connect(gain);
  src.start(nextTime);
  nextTime += ab.duration;
});

$('mute').addEventListener('click', () => {
  muted = !muted;
  if (gain) gain.gain.value = muted ? 0 : 1;
  $('mute').textContent = muted ? 'Unmute' : 'Mute';
});

// ---------------- controls ----------------
let barTimer = null;
function showBar() {
  $('bar').classList.remove('hide');
  $('watch').classList.remove('hide-cursor');
  clearTimeout(barTimer);
  barTimer = setTimeout(() => { $('bar').classList.add('hide'); $('watch').classList.add('hide-cursor'); }, 3000);
}
$('watch').addEventListener('mousemove', showBar);
$('watch').addEventListener('dblclick', () => dc.fullscreen());
$('full').addEventListener('click', () => dc.fullscreen());
$('stop').addEventListener('click', () => dc.stop());
dc.onFullscreen((on) => { $('full').textContent = on ? 'Exit full screen' : 'Full screen'; });
document.addEventListener('keydown', (e) => {
  if ($('watch').hidden) return;
  if (e.key === 'F11' || e.key === 'f') dc.fullscreen();
  if (e.key === 'Escape') dc.fullscreen(false);
  if (e.key === 'm') $('mute').click();
  showBar();
});
