/*
 * Streamplayer – Erkennung in der Seite.
 *
 * Läuft in jedem Frame ab Dokumentbeginn. Sucht Videos auf drei Wegen und meldet
 * Adressen über window.streamplayerBridge an die App:
 *   1. play/loadedmetadata-Events in der Capture-Phase (auch aus verschachtelten Playern)
 *   2. DOM-Scan nach <video>/<audio>/<source> und VideoObject-Schema (JSON-LD)
 *   3. Antworten von XHR und fetch, deren Content-Type nach Video aussieht
 * Der Rest (Netzwerk-Anfragen mit Video-Endung) läuft nativ im WebViewClient.
 */
(function () {
  'use strict';
  if (window.__streamplayer) return;
  window.__streamplayer = true;

  var bridge = window.streamplayerBridge;
  if (!bridge) return;

  var sent = Object.create(null);
  var isTop = window.top === window;

  function abs(u) {
    try { return new URL(u, location.href).href; } catch (e) { return null; }
  }

  function post(msg) {
    try { bridge.postMessage(JSON.stringify(msg)); } catch (e) { /* Bridge weg: Seite wird verlassen */ }
  }

  function report(url, src, extra) {
    if (!url) return;
    url = abs(String(url));
    if (!url || !/^https?:/i.test(url)) return; // blob:, data:, mediasource: gehen nicht
    if (sent[url]) return;
    sent[url] = 1;
    var m = { t: 'media', url: url, src: src, frame: location.href };
    if (extra) {
      for (var k in extra) if (extra[k]) m[k] = String(extra[k]);
    }
    post(m);
  }

  // ---------- 1 + 2: Medien-Elemente ----------

  function fromElement(el, src) {
    var poster = el.getAttribute && el.getAttribute('poster');
    var extra = { poster: poster ? abs(poster) : null, title: el.getAttribute && (el.getAttribute('title') || el.getAttribute('aria-label')) };
    var cur = el.currentSrc || el.src;
    if (cur && !/^blob:/i.test(cur)) report(cur, src, extra);
    var sources = el.querySelectorAll ? el.querySelectorAll('source[src]') : [];
    for (var i = 0; i < sources.length; i++) {
      report(sources[i].getAttribute('src'), src, { poster: extra.poster, title: extra.title, mime: sources[i].getAttribute('type') });
    }
  }

  ['play', 'playing', 'loadedmetadata'].forEach(function (type) {
    document.addEventListener(type, function (e) {
      var t = e.target;
      if (t && (t.tagName === 'VIDEO' || t.tagName === 'AUDIO')) fromElement(t, 'play');
    }, true);
  });

  function thumbOf(t) {
    if (!t) return null;
    if (Array.isArray(t)) t = t[0];
    if (t && typeof t === 'object') t = t.url || t.contentUrl;
    return t ? abs(String(t)) : null;
  }

  function walkLd(o, depth) {
    if (!o || typeof o !== 'object' || depth > 6) return;
    if (Array.isArray(o)) { for (var i = 0; i < o.length; i++) walkLd(o[i], depth + 1); return; }
    var type = o['@type'];
    var isVideo = type === 'VideoObject' || (Array.isArray(type) && type.indexOf('VideoObject') >= 0);
    if (isVideo && o.contentUrl) {
      report(o.contentUrl, 'ld', { title: o.name, poster: thumbOf(o.thumbnailUrl) });
    }
    if (o['@graph']) walkLd(o['@graph'], depth + 1);
    if (o.video) walkLd(o.video, depth + 1);
    if (o.mainEntity) walkLd(o.mainEntity, depth + 1);
  }

  function scan() {
    var els = document.querySelectorAll('video, audio');
    for (var i = 0; i < els.length; i++) fromElement(els[i], 'dom');
    var lds = document.querySelectorAll('script[type="application/ld+json"]');
    for (var j = 0; j < lds.length; j++) {
      try { walkLd(JSON.parse(lds[j].textContent), 0); } catch (e) { /* kaputtes JSON-LD ist häufig */ }
    }
    meta();
  }

  var scheduled = null;
  function scheduleScan() {
    if (scheduled) return;
    scheduled = setTimeout(function () { scheduled = null; scan(); }, 700);
  }

  function observe() {
    if (!document.documentElement) return;
    new MutationObserver(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        var added = muts[i].addedNodes;
        for (var j = 0; j < added.length; j++) {
          var n = added[j];
          if (n.nodeType !== 1) continue;
          if (n.tagName === 'VIDEO' || n.tagName === 'AUDIO' || n.tagName === 'SOURCE' || n.tagName === 'TITLE' ||
              (n.querySelector && n.querySelector('video, audio'))) {
            scheduleScan();
            return;
          }
        }
      }
    }).observe(document.documentElement, { childList: true, subtree: true });
  }

  document.addEventListener('readystatechange', scan);
  window.addEventListener('load', scan);
  if (document.documentElement) observe(); else document.addEventListener('DOMContentLoaded', observe);

  // ---------- Titel und Vorschaubild der Seite ----------

  var lastMeta = '';
  function content(sel) {
    var e = document.querySelector(sel);
    return e ? e.getAttribute('content') : null;
  }
  function meta() {
    if (!isTop) return;
    var title = content('meta[property="og:title"]') || content('meta[name="twitter:title"]') || document.title || '';
    var image = content('meta[property="og:image"]') || content('meta[name="twitter:image"]') || '';
    var key = location.href + '|' + title + '|' + image;
    if (key === lastMeta) return;
    lastMeta = key;
    post({ t: 'meta', title: title, image: image ? abs(image) : '', page: location.href });
  }

  // ---------- 3: XHR und fetch ----------

  var MEDIA_TYPE = /mpegurl|dash\+xml|^video\/|^audio\//i;
  var SEGMENT_TYPE = /mp2t|segment/i;

  function checkType(url, type) {
    if (!url || !type) return;
    type = String(type).split(';')[0].trim();
    if (SEGMENT_TYPE.test(type)) return;
    if (MEDIA_TYPE.test(type)) report(url, 'xhr', { mime: type });
  }

  try {
    var open = XMLHttpRequest.prototype.open;
    var send = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (method, url) {
      this.__spUrl = url;
      return open.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function () {
      var x = this;
      x.addEventListener('readystatechange', function () {
        if (x.readyState !== 2) return; // HEADERS_RECEIVED
        try { checkType(x.responseURL || abs(x.__spUrl), x.getResponseHeader('content-type')); } catch (e) { }
      });
      return send.apply(this, arguments);
    };
  } catch (e) { }

  try {
    if (typeof window.fetch === 'function') {
      var origFetch = window.fetch;
      window.fetch = function () {
        var p = origFetch.apply(this, arguments);
        try {
          p.then(function (r) {
            try { checkType(r.url, r.headers.get('content-type')); } catch (e) { }
          }, function () { });
        } catch (e) { }
        return p;
      };
    }
  } catch (e) { }

  // Single-Page-Apps wechseln die Adresse ohne neues Dokument.
  if (isTop) setInterval(meta, 2500);
})();
