'use strict';
// End-to-end check: launch the receiver, let tools/fake-phone.js find it and cast,
// press Allow, and save screenshots. Needs a display (xvfb-run on Linux).
const { _electron: electron } = require('playwright');
const { spawn } = require('child_process');
const path = require('path');

(async () => {
  const out = process.argv[2] || path.join(__dirname, '..', 'shots');
  require('fs').mkdirSync(out, { recursive: true });
  const app = await electron.launch({
    executablePath: require('electron'),
    args: ['--no-sandbox', path.join(__dirname, '..')],
    env: { ...process.env, ELECTRON_ENABLE_LOGGING: '1' },
  });
  const win = await app.firstWindow();
  win.on('console', (m) => { if (!/Security Warning/.test(m.text())) console.log('[renderer]', m.text()); });
  await win.setViewportSize({ width: 1120, height: 720 }).catch(() => {});
  await win.waitForSelector('#name:not(:empty)');
  await win.waitForTimeout(1500);
  await win.screenshot({ path: `${out}/receiver-idle.png` });

  const phone = spawn('node', [path.join(__dirname, 'fake-phone.js'), '10'], { stdio: 'inherit' });
  await win.waitForSelector('#ask:not([hidden])', { timeout: 15000 });
  await win.screenshot({ path: `${out}/receiver-allow.png` });
  await win.click('#allow');
  await win.waitForTimeout(3000);
  console.log('stats', await win.evaluate(() => window.dcStats));
  await win.waitForSelector('#watch.has-video', { timeout: 15000 });
  await win.waitForTimeout(2500);
  await win.mouse.move(500, 400);
  await win.waitForTimeout(300);
  await win.screenshot({ path: `${out}/receiver-watching.png` });
  const stats = await win.evaluate(() => ({ w: document.getElementById('screen').width, h: document.getElementById('screen').height }));
  console.log('canvas', stats);
  await new Promise((r) => phone.on('close', r));
  await win.waitForSelector('#idle:not([hidden])', { timeout: 5000 });
  console.log('back to idle after the phone said bye');
  await app.close();
})().catch((e) => { console.error(e); process.exit(1); });
