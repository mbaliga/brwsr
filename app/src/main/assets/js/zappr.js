// Zappr — tap an element to select it, walk up/down the tree, zap it or style it.
// Or search: a CSS selector, or plain text ("Sponsored") to catch every element that says it.
// Injected on demand into the top frame; TOKEN is filled in natively (bridge secret).
(function () {
  'use strict';
  if (window.__brwsrZappr) { window.__brwsrZappr.start(); return; }
  var TOKEN = '__TOKEN__';
  var Z = 2147483646;
  var on = false, cur = null, downStack = [], startX = 0, startY = 0, moved = false, hoverEl = null;
  var layer, box, label, marks = [], search = null;

  function mk(tag, css) { var e = document.createElement(tag); e.setAttribute('data-brwsr-ui', ''); e.style.cssText = css; return e; }

  function build() {
    layer = mk('div', 'position:fixed;inset:0;pointer-events:none;z-index:' + Z + ';');
    box = mk('div', 'position:fixed;pointer-events:none;border:2px solid #8E7BFF;background:rgba(142,123,255,.18);border-radius:3px;transition:all .12s ease-out;display:none;box-shadow:0 0 0 1px rgba(0,0,0,.6);');
    label = mk('div', 'position:fixed;pointer-events:none;font:600 11px/1.3 ui-monospace,monospace;color:#ECE8E4;background:#121212;padding:3px 6px;border-radius:4px;max-width:80vw;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;display:none;');
    layer.appendChild(box); layer.appendChild(label);
    (document.body || document.documentElement).appendChild(layer);
  }

  function ours(e) { return e && e.closest && e.closest('[data-brwsr-ui]'); }

  function at(x, y) {
    var e = document.elementFromPoint(x, y);
    if (!e || ours(e) || e === document.documentElement) return null;
    return e;
  }

  function describe(e) {
    if (!e) return '';
    var s = e.tagName.toLowerCase();
    if (e.id) s += '#' + e.id;
    var c = Array.prototype.slice.call(e.classList || [], 0, 2);
    if (c.length) s += '.' + c.join('.');
    var r = e.getBoundingClientRect();
    return s + '  ' + Math.round(r.width) + '×' + Math.round(r.height);
  }

  function place(e) {
    if (!e) { box.style.display = 'none'; label.style.display = 'none'; return; }
    var r = e.getBoundingClientRect();
    box.style.display = 'block';
    box.style.left = r.left + 'px'; box.style.top = r.top + 'px';
    box.style.width = r.width + 'px'; box.style.height = r.height + 'px';
    label.textContent = describe(e);
    label.style.display = 'block';
    label.style.left = Math.max(4, r.left) + 'px';
    label.style.top = (r.top > 24 ? r.top - 22 : Math.min(innerHeight - 22, r.bottom + 4)) + 'px';
  }

  // ---- selectors -------------------------------------------------------------------------
  function esc(s) { return window.CSS && CSS.escape ? CSS.escape(s) : s.replace(/[^\w-]/g, '\\$&'); }
  // Generated class names (css-1x2y3z, sc-AbCdE, jsx-1234, Ab3dE9) change between deploys,
  // so a selector built on them would stop matching. Skip them.
  function stable(name) {
    if (!name || name.length > 40) return false;
    if (/\d{4,}/.test(name)) return false;
    if (/^(css|sc|jsx|svelte|emotion|tw|styles?)[-_]/i.test(name)) return false;
    if (/[A-Z]/.test(name) && /\d/.test(name) && !/[-_]/.test(name)) return false;
    return true;
  }
  function seg(e) {
    var s = e.tagName.toLowerCase();
    var cls = Array.prototype.filter.call(e.classList || [], stable).slice(0, 3);
    if (cls.length) s += '.' + cls.map(esc).join('.');
    return s;
  }
  function count(sel) { try { return document.querySelectorAll(sel).length; } catch (_) { return 0; } }
  function unique(e) {
    if (e.id && stable(e.id) && count('#' + esc(e.id)) === 1) return '#' + esc(e.id);
    var path = [], c = e;
    while (c && c.nodeType === 1 && c !== document.documentElement && c !== document.body) {
      var s = seg(c), p = c.parentElement;
      if (c.id && stable(c.id) && count('#' + esc(c.id)) === 1) { path.unshift('#' + esc(c.id)); }
      else {
        if (p) {
          var sib = Array.prototype.filter.call(p.children, function (k) { try { return k.matches(s); } catch (_) { return false; } });
          if (sib.length > 1) s += ':nth-child(' + (Array.prototype.indexOf.call(p.children, c) + 1) + ')';
        }
        path.unshift(s);
      }
      var sel = path.join(' > ');
      if (path[0].charAt(0) === '#' || count(sel) === 1) return sel;
      c = p;
    }
    if (c === document.body) path.unshift('body');
    return path.join(' > ');
  }
  function similar(e) {
    var s = seg(e), p = e.parentElement;
    if (s.indexOf('.') < 0 && p && p !== document.body) s = seg(p) + ' > ' + s;
    return s;
  }

  function post(o) { try { window.BrwsrBridge.zappr(TOKEN, JSON.stringify(o)); } catch (_) {} }
  function report() {
    if (!cur) { post({ type: 'none' }); return; }
    var sim = similar(cur);
    post({ type: 'select', desc: describe(cur), selector: unique(cur), similar: sim, similarCount: count(sim) });
  }

  // ---- touch handling: a still tap selects, a drag scrolls as usual -----------------------
  function onStart(ev) {
    if (!on || ours(ev.target)) return;
    var t = ev.touches ? ev.touches[0] : ev;
    startX = t.clientX; startY = t.clientY; moved = false;
    hoverEl = at(startX, startY); place(hoverEl || cur);
  }
  function onMove(ev) {
    if (!on) return;
    var t = ev.touches ? ev.touches[0] : ev;
    if (Math.abs(t.clientX - startX) + Math.abs(t.clientY - startY) > 12) { moved = true; place(cur); }
  }
  function onEnd(ev) {
    if (!on || ours(ev.target)) return;
    ev.preventDefault(); ev.stopImmediatePropagation();
    if (moved || !hoverEl) { place(cur); return; }
    clearSearch();
    cur = hoverEl; downStack = [];
    place(cur); report();
  }
  function swallow(ev) { if (on && !ours(ev.target)) { ev.preventDefault(); ev.stopImmediatePropagation(); } }
  function onScroll() { if (on) { place(cur); positionMarks(); } }

  var EVTS = ['click', 'mousedown', 'mouseup', 'pointerup', 'contextmenu', 'auxclick', 'dblclick'];
  function start() {
    if (on) return;
    on = true;
    if (!layer || !layer.isConnected) build();
    layer.style.display = 'block';
    addEventListener('touchstart', onStart, { capture: true, passive: true });
    addEventListener('touchmove', onMove, { capture: true, passive: true });
    addEventListener('touchend', onEnd, { capture: true, passive: false });
    EVTS.forEach(function (n) { addEventListener(n, swallow, true); });
    addEventListener('scroll', onScroll, true);
  }
  function stop() {
    on = false; cur = null; downStack = [];
    clearSearch();
    if (layer) layer.style.display = 'none';
    place(null);
    removeEventListener('touchstart', onStart, true);
    removeEventListener('touchmove', onMove, true);
    removeEventListener('touchend', onEnd, true);
    EVTS.forEach(function (n) { removeEventListener(n, swallow, true); });
    removeEventListener('scroll', onScroll, true);
  }

  // ---- search ------------------------------------------------------------------------------
  function clearSearch() { marks.forEach(function (m) { m.box.remove(); }); marks = []; search = null; }
  function positionMarks() {
    marks.forEach(function (m) {
      var r = m.el.getBoundingClientRect();
      m.box.style.left = r.left + 'px'; m.box.style.top = r.top + 'px';
      m.box.style.width = r.width + 'px'; m.box.style.height = r.height + 'px';
    });
  }
  function climb(e, n) { for (var i = 0; i < n && e.parentElement && e.parentElement !== document.body; i++) e = e.parentElement; return e; }
  function runSearch(q, up) {
    clearSearch(); cur = null; place(null);
    q = (q || '').trim();
    if (!q) return { count: 0, mode: 'none' };
    var els = [], mode = 'text';
    if (/^[#.\[]|^[a-z]+[#.\[:>]|^[a-z][a-z0-9-]*$/i.test(q) && !/\s{2}/.test(q)) {
      try { els = Array.prototype.slice.call(document.querySelectorAll(q)); mode = 'css'; } catch (_) { els = []; }
      if (mode === 'css' && !els.length && /^[a-z][a-z0-9-]*$/i.test(q)) mode = 'text';
    }
    if (mode === 'text') {
      var needle = q.toLowerCase(), seen = new Set();
      var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null), n;
      while ((n = w.nextNode())) {
        if (!n.nodeValue || n.nodeValue.toLowerCase().indexOf(needle) < 0) continue;
        var p = n.parentElement;
        if (!p || ours(p) || /^(SCRIPT|STYLE|NOSCRIPT)$/.test(p.tagName)) continue;
        p = climb(p, up | 0);
        if (!seen.has(p)) { seen.add(p); els.push(p); }
      }
    }
    els = els.filter(function (e) { return !ours(e); }).slice(0, 400);
    els.forEach(function (e) {
      var b = mk('div', 'position:fixed;pointer-events:none;border:2px solid #E5564B;background:rgba(229,86,75,.14);border-radius:3px;');
      layer.appendChild(b);
      marks.push({ el: e, box: b });
    });
    positionMarks();
    if (els[0]) els[0].scrollIntoView({ block: 'center', behavior: 'smooth' });
    search = { q: q, up: up | 0, mode: mode };
    return { count: els.length, mode: mode };
  }

  // ---- commands from native ------------------------------------------------------------
  function hideNow(e) { e.setAttribute('data-brwsr-zapped', ''); }
  window.__brwsrZappr = {
    start: start,
    stop: function () { stop(); return 'ok'; },
    parent: function () {
      if (!cur) return 'none';
      var p = cur.parentElement;
      if (p && p !== document.body && p !== document.documentElement) { downStack.push(cur); cur = p; }
      place(cur); report(); return 'ok';
    },
    child: function () {
      if (!cur) return 'none';
      var c = downStack.pop() || cur.firstElementChild;
      if (c) cur = c;
      place(cur); report(); return 'ok';
    },
    // Hides the selection now and hands its selector to native, which makes it permanent.
    zap: function (useSimilar) {
      if (!cur) return null;
      var sel = useSimilar ? similar(cur) : unique(cur);
      try { document.querySelectorAll(sel).forEach(hideNow); } catch (_) { hideNow(cur); }
      cur = null; downStack = []; place(null);
      return sel;
    },
    selector: function (useSimilar) { return cur ? (useSimilar ? similar(cur) : unique(cur)) : null; },
    search: function (q, up) { return JSON.stringify(runSearch(q, up)); },
    zapSearch: function () {
      if (!search || !marks.length) return null;
      marks.forEach(function (m) { hideNow(m.el); });
      var out = search.mode === 'css' ? { type: 'css', selector: search.q } : { type: 'text', text: search.q, up: search.up };
      clearSearch();
      return JSON.stringify(out);
    },
    // Live preview for the CSS editor: outline what a selector matches.
    preview: function (sel) { return JSON.stringify(runSearch(sel, 0)); },
  };
  Object.defineProperty(window, '__brwsrZappr', { enumerable: false });
  start();
})();
