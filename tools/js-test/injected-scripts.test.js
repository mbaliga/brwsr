// Exercises the injected page scripts (page.js, zappr.js, desktop.js) in headless Chromium:
// zaps, text zaps on late content, site CSS, GPC, the cosmetic survey, Nakd, Zappr tap/parent/
// zap/search, and the desktop viewport rewrite.
//
//   cd tools/js-test && npm install && node injected-scripts.test.js

const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const J = path.join(__dirname, '../../app/src/main/assets/js/');
const html = `<!doctype html><html><head><style>body{background:red}</style><link rel=stylesheet href="data:text/css,p{color:blue}"></head><body>
<div class="ad-box"><span>buy</span></div>
<ul id="feed"><li class="post"><b>Sponsored</b> thing</li><li class="post">real news</li><li class="post"><i>Sponsored</i> x</li></ul>
<div class="card c1"><p style="color:green">one</p></div><div class="card c1"><p>two</p></div>
<div class="sc-AbC12x hash"><a href="#" id="target">link</a></div>
</body></html>`;
let ok = 0, bad = 0;
const check = (name, cond) => { cond ? ok++ : bad++; console.log((cond ? 'PASS ' : 'FAIL ') + name); };
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ hasTouch: true });
  const page = await ctx.newPage();
  const surveyed = [];
  await page.exposeFunction('__survey', (t, j) => surveyed.push([t, JSON.parse(j)]));
  const zmsgs = [];
  await page.exposeFunction('__zappr', (t, j) => zmsgs.push([t, JSON.parse(j)]));
  await page.addInitScript(() => { window.BrwsrBridge = { survey: (t, j) => window.__survey(t, j), zappr: (t, j) => window.__zappr(t, j) }; });
  const cfg = { sites: { 'example.test': { zaps: ['.ad-box'], textZaps: [{ t: 'sponsored', u: 1 }], css: '.card{border:3px solid rgb(1,2,3)}' } }, cosmetic: true, lowly: '[data-x]{display:none!important}\n', globalCss: '', gpc: true };
  const pageJs = fs.readFileSync(J + 'page.js', 'utf8').replace('__CFG__', JSON.stringify(cfg)).replace('__TOKEN__', 'tok');
  await page.addInitScript(pageJs);
  await page.route('http://example.test/', r => r.fulfill({ body: html, contentType: 'text/html' }));
  await page.goto('http://example.test/');
  await page.waitForTimeout(300);
  check('zap hides .ad-box', await page.$eval('.ad-box', e => getComputedStyle(e).display) === 'none');
  check('text zap hides li with Sponsored (up 1)', (await page.$$eval('li.post', ls => ls.map(l => getComputedStyle(l).display))).join() === 'none,list-item,none');
  check('site css applied', await page.$eval('.card', e => getComputedStyle(e).borderTopColor) === 'rgb(1, 2, 3)');
  check('GPC exposed', await page.evaluate(() => navigator.globalPrivacyControl === true));
  check('survey sent with token', surveyed.length > 0 && surveyed[0][0] === 'tok' && surveyed[0][1].includes('.ad-box') && surveyed[0][1].includes('#feed'));
  // dynamic content: text zap + survey keep up
  await page.evaluate(() => { const li = document.createElement('li'); li.className = 'post newk'; li.innerHTML = '<b>Sponsored</b> late'; document.getElementById('feed').appendChild(li); });
  await page.waitForTimeout(400);
  check('text zap catches late content', await page.$eval('li.newk', e => getComputedStyle(e).display) === 'none');
  check('survey reports new keys', surveyed.some(s => s[1].includes('.newk')));
  // native pushes cosmetic css
  await page.evaluate(() => window.__brwsr.addCss('.c1{display:none!important}'));
  check('addCss works', await page.$eval('.c1', e => getComputedStyle(e).display) === 'none');
  // live re-apply with nakd
  const cfg2 = JSON.parse(JSON.stringify(cfg)); cfg2.sites['example.test'].nakd = true;
  await page.evaluate(fs.readFileSync(J + 'page.js', 'utf8').replace('__CFG__', JSON.stringify(cfg2)).replace('__TOKEN__', 'tok'));
  await page.waitForTimeout(100);
  check('nakd strips author styles', await page.evaluate(() => document.querySelectorAll('style:not([data-brwsr]),link[rel=stylesheet]').length === 0 && !document.querySelector('p[style]')));
  check('nakd keeps brwsr styles (zaps still hidden)', await page.$eval('.ad-box', e => getComputedStyle(e).display) === 'none');
  await page.evaluate(() => { const s = document.createElement('style'); s.textContent = 'body{color:pink}'; document.head.appendChild(s); });
  await page.waitForTimeout(50);
  check('nakd removes late styles', await page.evaluate(() => document.querySelectorAll('style:not([data-brwsr])').length === 0));

  // ---- Zappr ----
  await page.evaluate(fs.readFileSync(J + 'zappr.js', 'utf8').replace('__TOKEN__', 'tok'));
  await page.$eval('.c1', e => e.style.cssText = ''); await page.evaluate(() => document.querySelectorAll('[data-brwsr="2"]').forEach(s => s.textContent = ''));
  const box = await page.$eval('#target', e => { const r = e.getBoundingClientRect(); return { x: r.x + 3, y: r.y + 3 }; });
  await page.touchscreen.tap(box.x, box.y);
  await page.waitForTimeout(150);
  const sel = zmsgs.filter(m => m[1].type === 'select').pop();
  check('tap selects element', !!sel && sel[1].selector === '#target');
  check('tap did not navigate', page.url() === 'http://example.test/');
  await page.evaluate(() => __brwsrZappr.parent());
  await page.waitForTimeout(100);
  const sel2 = zmsgs.filter(m => m[1].type === 'select').pop()[1].selector;
  check('parent selector skips hashed class & is unique', !sel2.includes('sc-AbC12x') && await page.evaluate(s => document.querySelectorAll(s).length === 1, sel2));
  const zapped = await page.evaluate(() => __brwsrZappr.zap(false));
  check('zap returns selector and hides now', zapped === sel2 && await page.evaluate(s => getComputedStyle(document.querySelector(s)).display === 'none', sel2));
  const sr = JSON.parse(await page.evaluate(() => __brwsrZappr.search('.card', 0)));
  check('css search counts', sr.mode === 'css' && sr.count === 2);
  const tr = JSON.parse(await page.evaluate(() => __brwsrZappr.search('real news', 0)));
  check('text search finds', tr.mode === 'text' && tr.count === 1);
  const zs = JSON.parse(await page.evaluate(() => __brwsrZappr.zapSearch()));
  check('zapSearch returns text rule', zs.type === 'text' && zs.text === 'real news');
  await page.evaluate(() => __brwsrZappr.stop());
  check('stop removes overlay', await page.evaluate(() => [...document.querySelectorAll('[data-brwsr-ui]')].every(e => e.style.display === 'none' || e.parentElement.style.display === 'none')));

  // desktop viewport
  const p2 = await ctx.newPage();
  await p2.addInitScript(fs.readFileSync(J + 'desktop.js', 'utf8'));
  await p2.route('http://d.test/', r => r.fulfill({ body: '<meta name=viewport content="width=device-width"><p>x', contentType: 'text/html' }));
  await p2.goto('http://d.test/'); await p2.waitForTimeout(100);
  check('desktop rewrites viewport', await p2.$eval('meta[name=viewport]', m => m.content) === 'width=1280');
  console.log(`\n${ok} passed, ${bad} failed`);
  await b.close();
  process.exit(bad ? 1 : 0);
})();
