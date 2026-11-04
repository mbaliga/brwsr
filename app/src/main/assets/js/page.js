// brwsr page script — runs at document start in every frame (and is re-run live when the
// user changes a rule). Idempotent: a second run just re-applies the new config.
// The CFG and TOKEN placeholders below are filled in natively (rules JSON; bridge secret).
(function () {
  'use strict';
  var CFG = __CFG__;
  var TOKEN = '__TOKEN__';
  if (window.__brwsr && window.__brwsr.apply) { window.__brwsr.apply(CFG); return; }

  var host = (location.hostname || '').toLowerCase().replace(/^www\./, '');
  var top = window.top === window;
  var cfg = CFG;
  var styleEl = null, extraEl = null, nakdObserver = null, textObserver = null, surveyObserver = null;
  var extraCss = ''; // generic + site cosmetic CSS pushed by native for this page
  var sent = Object.create(null);
  var pendingKeys = [];
  var surveyTimer = 0;

  function siteRule() {
    var s = cfg.sites || {};
    var h = host;
    while (h) {
      if (s[h]) return s[h];
      var i = h.indexOf('.');
      if (i < 0) break;
      h = h.slice(i + 1);
    }
    return {};
  }

  function hide(sel) { return sel + '{display:none!important}\n'; }

  function buildCss(rule) {
    var out = '';
    if (cfg.cosmetic && !rule.adblockOff && cfg.lowly) out += cfg.lowly;
    (rule.zaps || []).forEach(function (z) { out += hide(z); });
    out += '[data-brwsr-zapped]{display:none!important}\n';
    if (rule.dark) {
      out += 'html{filter:invert(1) hue-rotate(180deg)!important;background:#fff!important}\n' +
        'img,video,picture,canvas,iframe,embed,object,svg image,[style*="background-image"]{filter:invert(1) hue-rotate(180deg)!important}\n';
    }
    if (rule.hideImages) out += 'img,picture,video,[style*="background-image"]{visibility:hidden!important}\n';
    if (rule.font) out += '*:not(code):not(pre):not(kbd):not(samp):not([class*="icon"]):not(i){font-family:' + rule.font + '!important}\n';
    if (rule.unstick) out += 'html,body{overflow:auto!important;position:static!important}\n';
    if (cfg.globalCss) out += cfg.globalCss + '\n';
    if (rule.css) out += rule.css + '\n';
    return out;
  }

  function root() { return document.documentElement || document; }

  function ensureStyle() {
    if (!styleEl) {
      styleEl = document.createElement('style');
      styleEl.setAttribute('data-brwsr', '1');
      extraEl = document.createElement('style');
      extraEl.setAttribute('data-brwsr', '2');
    }
    var r = document.documentElement;
    if (!r) return;
    // Keep our styles last so they win ties, and re-attach if a framework wipes <head>.
    var parent = document.head || r;
    if (styleEl.parentNode !== parent) parent.appendChild(styleEl);
    if (extraEl.parentNode !== parent) parent.appendChild(extraEl);
  }

  // ---- text zaps: "hide anything that says X" -------------------------------------------
  function applyTextZaps(rule, scope) {
    var tz = rule.textZaps || [];
    if (!tz.length || !document.body) return;
    var needles = tz.map(function (t) { return { t: t.t.toLowerCase(), u: t.u | 0 }; });
    var w = document.createTreeWalker(scope || document.body, NodeFilter.SHOW_TEXT, null);
    var n;
    while ((n = w.nextNode())) {
      var v = n.nodeValue;
      if (!v || v.length < 2) continue;
      var lv = v.toLowerCase();
      for (var i = 0; i < needles.length; i++) {
        if (lv.indexOf(needles[i].t) < 0) continue;
        var el = n.parentElement;
        for (var k = 0; k < needles[i].u && el && el.parentElement && el.parentElement !== document.body; k++) el = el.parentElement;
        if (el && el !== document.body && el !== document.documentElement) el.setAttribute('data-brwsr-zapped', '');
      }
    }
  }

  // ---- unstick: pinned headers, nag bars, scroll locks ------------------------------------
  function unstick(scope) {
    if (!document.body) return;
    var els = (scope || document.body).querySelectorAll('*');
    for (var i = 0; i < els.length && i < 6000; i++) {
      var e = els[i];
      var p = getComputedStyle(e).position;
      if (p === 'fixed' || p === 'sticky') e.style.setProperty('position', p === 'fixed' ? 'absolute' : 'static', 'important');
    }
  }

  // ---- Nakd: strip every author stylesheet --------------------------------------------------
  var NAKD_BASE = 'img,video,iframe,svg,canvas,table{max-width:100%!important;height:auto}\n' +
    'pre{white-space:pre-wrap}\n';
  function strip(scope) {
    var list = (scope && scope.querySelectorAll ? scope : document).querySelectorAll('link[rel~="stylesheet" i],style:not([data-brwsr]),[style]');
    for (var i = 0; i < list.length; i++) {
      var e = list[i];
      if (e.tagName === 'LINK' || e.tagName === 'STYLE') e.remove();
      else if (!e.hasAttribute('data-brwsr-zapped') && !e.hasAttribute('data-brwsr-ui')) e.removeAttribute('style');
    }
    try { document.adoptedStyleSheets = []; } catch (_) {}
  }
  function nakd(on) {
    if (!on) { if (nakdObserver) { nakdObserver.disconnect(); nakdObserver = null; } return; }
    strip();
    if (nakdObserver) return;
    nakdObserver = new MutationObserver(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        var m = muts[i];
        if (m.type === 'attributes') { if (m.target.removeAttribute && !m.target.hasAttribute('data-brwsr-zapped') && !m.target.hasAttribute('data-brwsr-ui')) m.target.removeAttribute('style'); continue; }
        for (var j = 0; j < m.addedNodes.length; j++) {
          var a = m.addedNodes[j];
          if (a.nodeType !== 1 || a.hasAttribute('data-brwsr-ui')) continue;
          if ((a.tagName === 'LINK' && /stylesheet/i.test(a.rel)) || (a.tagName === 'STYLE' && !a.hasAttribute('data-brwsr'))) a.remove();
          else strip(a);
        }
      }
    });
    nakdObserver.observe(root(), { childList: true, subtree: true, attributes: true, attributeFilter: ['style'] });
  }

  // ---- generic cosmetic survey: report the page's classes/ids, receive matching rules ----
  function collect(scope) {
    if (!top || !cfg.cosmetic || siteRule().adblockOff || !window.BrwsrBridge) return;
    var nodes = (scope || document).querySelectorAll('[id],[class]');
    for (var i = 0; i < nodes.length && i < 4000; i++) {
      var e = nodes[i];
      if (e.id) { var k = '#' + e.id; if (!sent[k]) { sent[k] = 1; pendingKeys.push(k); } }
      var cl = e.classList;
      if (cl) for (var j = 0; j < cl.length; j++) { var c = '.' + cl[j]; if (!sent[c]) { sent[c] = 1; pendingKeys.push(c); } }
    }
    if (pendingKeys.length && !surveyTimer) surveyTimer = setTimeout(flushSurvey, 60);
  }
  function flushSurvey() {
    surveyTimer = 0;
    if (!pendingKeys.length) return;
    var keys = pendingKeys; pendingKeys = [];
    try { window.BrwsrBridge.survey(TOKEN, JSON.stringify(keys)); } catch (_) {}
  }

  var mutTimer = 0, mutScopes = [];
  function onMutations(muts) {
    for (var i = 0; i < muts.length; i++) {
      var added = muts[i].addedNodes;
      for (var j = 0; j < added.length; j++) if (added[j].nodeType === 1) mutScopes.push(added[j]);
    }
    if (mutScopes.length && !mutTimer) mutTimer = setTimeout(function () {
      mutTimer = 0;
      var scopes = mutScopes.splice(0, mutScopes.length);
      var rule = siteRule();
      ensureStyle();
      for (var k = 0; k < scopes.length && k < 300; k++) {
        var s = scopes[k];
        if (!s.isConnected) continue;
        collect(s);
        if (s.id || s.className) collect(s.parentNode || document);
        applyTextZaps(rule, s);
        if (rule.unstick) unstick(s);
      }
    }, 120);
  }

  function apply(next) {
    cfg = next || cfg;
    var rule = siteRule();
    ensureStyle();
    if (styleEl) styleEl.textContent = buildCss(rule);
    nakd(!!rule.nakd);
    if (rule.nakd) { var b = document.querySelector('style[data-brwsr="3"]'); if (!b) { b = document.createElement('style'); b.setAttribute('data-brwsr', '3'); (document.head || root()).appendChild(b); } b.textContent = NAKD_BASE; }
    if (document.body) {
      applyTextZaps(rule);
      if (rule.unstick) unstick();
    }
    if (cfg.gpc) {
      try { Object.defineProperty(Navigator.prototype, 'globalPrivacyControl', { get: function () { return true; }, configurable: true }); } catch (_) {}
    }
  }

  function boot() {
    apply();
    collect();
    if (!surveyObserver) {
      surveyObserver = new MutationObserver(onMutations);
      surveyObserver.observe(root(), { childList: true, subtree: true });
    }
  }

  window.__brwsr = {
    apply: apply,
    // Native pushes cosmetic CSS (site-specific + surveyed generics) for this page.
    addCss: function (css) { extraCss += css; ensureStyle(); if (extraEl) extraEl.textContent = extraCss; },
    host: function () { return host; },
  };
  Object.defineProperty(window, '__brwsr', { enumerable: false });

  apply();
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot, { once: true });
  else boot();
})();
