'use strict';
// Driftcast Receiver for Windows: announces itself on the Wi-Fi and shows a phone's screen.
const { app, BrowserWindow, ipcMain, Menu, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
const net = require('net');
const dgram = require('dgram');
const crypto = require('crypto');
const P = require('./protocol');

app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

if (!app.requestSingleInstanceLock()) app.quit();

let win = null;
let settings = null;
let server = null;
let port = 0;
let beaconTimer = null;
let beaconSock = null;
let session = null; // { socket, name, id }
const pendingAsks = new Map();

function settingsPath() { return path.join(app.getPath('userData'), 'settings.json'); }
function loadSettings() {
  try { settings = JSON.parse(fs.readFileSync(settingsPath(), 'utf8')); } catch { settings = {}; }
  if (!settings.id) settings.id = crypto.randomUUID();
  if (!settings.name) settings.name = defaultName();
  if (!Array.isArray(settings.trusted)) settings.trusted = [];
  saveSettings();
}
function saveSettings() {
  try {
    fs.mkdirSync(path.dirname(settingsPath()), { recursive: true });
    fs.writeFileSync(settingsPath(), JSON.stringify(settings, null, 2));
  } catch (e) { console.error('settings', e); }
}
function defaultName() {
  const h = os.hostname() || 'Laptop';
  return h.length > 2 && h === h.toUpperCase() ? h.charAt(0) + h.slice(1).toLowerCase() : h;
}

function send(channel, payload) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, payload);
}

function info() {
  return {
    name: settings.name,
    port,
    addresses: P.ipv4Interfaces().map((i) => i.address),
    trusted: settings.trusted.length,
    watching: session ? session.name : null,
    version: app.getVersion(),
  };
}

// ---------- discovery ----------
function startBeacon() {
  stopBeacon();
  beaconSock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
  beaconSock.on('error', (e) => console.warn('beacon', e.message));
  beaconSock.bind(() => {
    try { beaconSock.setBroadcast(true); } catch {}
    const tick = () => {
      const msg = Buffer.from(JSON.stringify({
        app: 'driftcast', v: 1, id: settings.id, name: settings.name, kind: 'pc', port,
      }));
      for (const to of P.broadcastTargets()) {
        beaconSock.send(msg, P.BEACON_PORT, to, () => {});
      }
    };
    tick();
    beaconTimer = setInterval(tick, 1000);
  });
}
function stopBeacon() {
  if (beaconTimer) clearInterval(beaconTimer);
  beaconTimer = null;
  if (beaconSock) { try { beaconSock.close(); } catch {} beaconSock = null; }
}

// ---------- stream server ----------
function startServer() {
  const tries = [P.STREAM_PORT, P.STREAM_PORT + 10, P.STREAM_PORT + 20];
  const attempt = (i) => {
    const s = net.createServer(onConnection);
    s.once('error', (e) => {
      if (i + 1 < tries.length) attempt(i + 1);
      else send('status', { error: 'Could not open a network port: ' + e.message });
    });
    s.listen(tries[i], '0.0.0.0', () => {
      server = s;
      port = tries[i];
      startBeacon();
      send('info', info());
    });
  };
  attempt(0);
}

function ask(name) {
  return new Promise((resolve) => {
    const reqId = crypto.randomUUID();
    const timer = setTimeout(() => { pendingAsks.delete(reqId); send('ask-cancel', reqId); resolve(0); }, 60000);
    pendingAsks.set(reqId, (answer) => { clearTimeout(timer); resolve(answer); });
    if (win) {
      if (win.isMinimized()) win.restore();
      win.show();
      win.focus();
      win.flashFrame(true);
    }
    send('ask', { reqId, name });
  });
}

function onConnection(socket) {
  socket.setNoDelay(true);
  socket.setTimeout(10000);
  let head = Buffer.alloc(0);
  let stage = 'hello';
  let parser = null;
  let mine = null;

  const finish = () => {
    if (mine && session === mine) {
      session = null;
      send('ended', {});
      send('info', info());
    }
  };
  socket.on('timeout', () => socket.destroy());
  socket.on('error', () => {});
  socket.on('close', finish);

  socket.on('data', async (buf) => {
    if (stage === 'hello') {
      head = Buffer.concat([head, buf]);
      if (head.length > 4096) return socket.destroy();
      const got = P.takeLine(head);
      if (!got) return;
      stage = 'asking';
      socket.pause();
      if (!got.line.startsWith(P.HELLO)) return socket.destroy();
      let hello;
      try { hello = JSON.parse(got.line.slice(P.HELLO.length)); } catch { return socket.destroy(); }
      const name = String(hello.name || 'A phone').slice(0, 60);
      const id = String(hello.id || '');
      if (session) {
        socket.end('{"ok":false,"reason":"busy"}\n');
        return;
      }
      mine = { socket, name, id };
      session = mine;
      let answer = settings.trusted.includes(id) ? 1 : 0;
      if (!answer) {
        socket.setTimeout(0);
        answer = await ask(name);
      }
      if (!answer || session !== mine || socket.destroyed) {
        if (session === mine) session = null;
        mine = null;
        socket.end('{"ok":false,"reason":"denied"}\n');
        return;
      }
      if (answer === 2 && id && !settings.trusted.includes(id)) { settings.trusted.push(id); saveSettings(); }
      socket.write(JSON.stringify({ ok: true, name: settings.name }) + '\n');
      socket.setTimeout(15000);
      stage = 'stream';
      parser = new P.PacketParser();
      send('start', { name });
      send('info', info());
      if (got.rest.length) handlePackets(parser.push(got.rest));
      socket.resume();
      return;
    }
    if (stage === 'stream') {
      try { handlePackets(parser.push(buf)); } catch (e) { socket.destroy(); }
    }
  });

  function handlePackets(list) {
    for (const p of list) {
      switch (p.type) {
        case P.T.VIDEO_CONFIG: {
          const w = p.data.readUInt16BE(0), h = p.data.readUInt16BE(2);
          const csd = Buffer.from(p.data.subarray(4));
          const sps = P.splitNals(csd).find((n) => n.type === 7);
          send('config', { w, h, csd, codec: sps ? P.avcCodecString(sps.data) : 'avc1.42E01F' });
          break;
        }
        case P.T.VIDEO_KEY:
        case P.T.VIDEO_DELTA:
          send('frame', { key: p.type === P.T.VIDEO_KEY, pts: p.pts, data: Buffer.from(p.data) });
          break;
        case P.T.AUDIO_PCM:
          send('pcm', Buffer.from(p.data));
          break;
        case P.T.BYE:
          socket.end();
          break;
        default:
          break;
      }
    }
  }
}

function stopSession() {
  const s = session;
  if (!s) return;
  try { s.socket.write(P.packet(P.T.STOP)); } catch {}
  setTimeout(() => s.socket.destroy(), 300);
  session = null;
  send('ended', {});
  send('info', info());
}

// ---------- IPC ----------
ipcMain.handle('info', () => info());
ipcMain.on('answer', (_e, { reqId, answer }) => {
  const f = pendingAsks.get(reqId);
  if (f) { pendingAsks.delete(reqId); f(answer); }
});
ipcMain.on('request-key', () => {
  if (session) try { session.socket.write(P.packet(P.T.REQUEST_KEY)); } catch {}
});
ipcMain.on('stop', () => stopSession());
ipcMain.on('rename', (_e, name) => {
  name = String(name || '').trim().slice(0, 40);
  if (!name) return;
  settings.name = name;
  saveSettings();
  startBeacon();
  send('info', info());
});
ipcMain.on('forget', () => { settings.trusted = []; saveSettings(); send('info', info()); });
ipcMain.on('fullscreen', (_e, on) => { if (win) win.setFullScreen(on === undefined ? !win.isFullScreen() : !!on); });
ipcMain.on('open-help', () => shell.openExternal('https://github.com/transkim/driftcast#readme'));

function createWindow() {
  win = new BrowserWindow({
    width: 1120,
    height: 720,
    minWidth: 480,
    minHeight: 320,
    backgroundColor: '#0E1430',
    title: 'Driftcast Receiver',
    icon: path.join(__dirname, '..', 'ui', 'icon.png'),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  Menu.setApplicationMenu(null);
  win.loadFile(path.join(__dirname, '..', 'ui', 'index.html'));
  win.on('closed', () => { win = null; });
  win.on('enter-full-screen', () => send('fullscreen', true));
  win.on('leave-full-screen', () => send('fullscreen', false));
}

app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
app.whenReady().then(() => {
  loadSettings();
  createWindow();
  startServer();
});
app.on('window-all-closed', () => {
  stopBeacon();
  if (server) server.close();
  app.quit();
});
