const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const svg = fs.readFileSync(__dirname + '/logo.svg', 'utf8');
  const b = await chromium.launch();
  const p = await b.newPage();
  const shot = async (html, w, h, out) => {
    await p.setViewportSize({ width: w, height: h });
    await p.setContent(`<html><body style="margin:0;background:transparent">${html}</body></html>`);
    await p.screenshot({ path: out, omitBackground: true });
  };
  const sized = (s) => svg.replace('width="96" height="96"', `width="${s}" height="${s}"`);
  for (const s of [16, 24, 32, 48, 64, 128, 256, 512]) await shot(sized(s), s, s, `${__dirname}/logo-${s}.png`);
  const res = process.argv[2];
  for (const [d, s] of [['mdpi', 48], ['hdpi', 72], ['xhdpi', 96], ['xxhdpi', 144], ['xxxhdpi', 192]]) {
    fs.mkdirSync(`${res}/mipmap-${d}`, { recursive: true });
    await shot(sized(s), s, s, `${res}/mipmap-${d}/ic_launcher.png`);
  }
  fs.mkdirSync(`${res}/drawable-xhdpi`, { recursive: true });
  await shot(`<div style="width:320px;height:180px;background:#0E1430;display:flex;align-items:center;justify-content:center;gap:18px;font-family:sans-serif">
    ${sized(84)}<div style="color:#F3F5FF;font-size:40px;font-weight:600">Driftcast</div></div>`, 320, 180, `${res}/drawable-xhdpi/banner.png`);
  await b.close();
})();
