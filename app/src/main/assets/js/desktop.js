// Desktop mode: make sites that declare `width=device-width` lay out at a desktop width.
(function () {
  if (window.__brwsrDesktop) return;
  window.__brwsrDesktop = 1;
  var W = 'width=1280';
  function fix() {
    var metas = document.querySelectorAll('meta[name="viewport" i]');
    if (!metas.length && document.head) {
      var m = document.createElement('meta'); m.name = 'viewport'; m.content = W; document.head.appendChild(m);
      return;
    }
    for (var i = 0; i < metas.length; i++) if (metas[i].content !== W) metas[i].content = W;
  }
  var mo = new MutationObserver(fix);
  mo.observe(document.documentElement || document, { childList: true, subtree: true });
  document.addEventListener('DOMContentLoaded', function () { fix(); setTimeout(function () { mo.disconnect(); }, 3000); });
})();
