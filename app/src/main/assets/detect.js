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
    tracks(el);
  }

  // Untertitel als <track>: Sprache und Name stehen dabei, das ist mehr, als das Netz verrät.
  var sentSub = {};
  function tracks(el) {
    var ts = el.querySelectorAll ? el.querySelectorAll('track[src]') : [];
    for (var i = 0; i < ts.length; i++) {
      var kind = (ts[i].getAttribute('kind') || 'subtitles').toLowerCase();
      if (kind !== 'subtitles' && kind !== 'captions') continue;
      var u = abs(ts[i].getAttribute('src'));
      if (!u || !/^https?:/i.test(u) || sentSub[u]) continue;
      sentSub[u] = 1;
      post({ t: 'sub', url: u, lang: ts[i].getAttribute('srclang') || '', label: ts[i].getAttribute('label') || '', frame: location.href });
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

  // ---------- 4: Player fragen, bevor jemand auf Play drückt ----------
  // Viele Player laden das Video erst beim Start, kennen die Adresse aber schon vorher.
  // Wir fragen sie direkt: JW Player (z. B. VOE), Video.js, dazu Player-Konfigurationen im Seitencode.

  function fromJwItem(item) {
    if (!item) return;
    var extra = { title: item.title, poster: item.image ? abs(item.image) : null };
    if (item.file) report(item.file, 'player', extra);
    var sources = item.sources || item.allSources || [];
    for (var i = 0; i < sources.length; i++) {
      var s = sources[i];
      if (s && s.file) report(s.file, 'player', { title: extra.title, poster: extra.poster, mime: jwMime(s.type) });
    }
  }

  function jwMime(type) {
    if (!type) return null;
    type = String(type).toLowerCase();
    if (type === 'hls' || type === 'm3u8') return 'application/vnd.apple.mpegurl';
    if (type === 'dash' || type === 'mpd') return 'application/dash+xml';
    if (type === 'mp4' || type === 'webm') return 'video/' + type;
    return null;
  }

  var hookedJw = [];
  function probeJwPlayer() {
    var jw = window.jwplayer;
    if (typeof jw !== 'function') return;
    for (var i = 0; i < 8; i++) {
      var p;
      try { p = jw(i); } catch (e) { break; }
      if (!p || typeof p.getPlaylist !== 'function') break;
      try {
        var list = p.getPlaylist() || [];
        for (var j = 0; j < list.length; j++) fromJwItem(list[j]);
        if (typeof p.getPlaylistItem === 'function') fromJwItem(p.getPlaylistItem());
      } catch (e) { }
      if (hookedJw.indexOf(p) < 0 && typeof p.on === 'function') {
        hookedJw.push(p);
        try { p.on('ready playlist playlistItem', function () { setTimeout(probeJwPlayer, 50); }); } catch (e) { }
      }
    }
  }

  function probeVideoJs() {
    var vjs = window.videojs;
    if (!vjs || typeof vjs.getPlayers !== 'function') return;
    var players = vjs.getPlayers() || {};
    for (var id in players) {
      var p = players[id];
      if (!p) continue;
      try {
        var poster = p.poster && p.poster();
        var list = (p.currentSources && p.currentSources()) || [];
        for (var i = 0; i < list.length; i++) {
          if (list[i] && list[i].src) report(list[i].src, 'player', { mime: list[i].type, poster: poster ? abs(poster) : null });
        }
        var cur = p.currentSrc && p.currentSrc();
        if (cur && !/^blob:/i.test(cur)) report(cur, 'player', { poster: poster ? abs(poster) : null });
      } catch (e) { }
    }
  }

  // Absolute Medien-Adressen im Seitencode, auch JSON-escaped (https:\/\/…).
  var URL_IN_CODE = /https?:(?:\\?\/){2}[^\s"'<>`]+?\.(m3u8|mpd|mp4|webm)(?:\?[^\s"'<>`]*)?(?=["'\s<>`,;)\]}]|$)/gi;
  var scannedScripts = typeof WeakSet === 'function' ? new WeakSet() : null;

  function collectUrls(text, manifests, files) {
    if (!text || text.length > 2000000) return;
    URL_IN_CODE.lastIndex = 0;
    var m;
    while ((m = URL_IN_CODE.exec(text)) !== null) {
      var u = m[0].replace(/\\\//g, '/').replace(/\\u0026/gi, '&').replace(/&amp;/g, '&');
      var ext = m[1].toLowerCase();
      (ext === 'm3u8' || ext === 'mpd' ? manifests : files)[u] = 1;
    }
  }

  function probeCode() {
    var manifests = {}, files = {};
    var scripts = document.querySelectorAll('script:not([src])');
    for (var i = 0; i < scripts.length; i++) {
      var s = scripts[i];
      if (scannedScripts) { if (scannedScripts.has(s)) continue; scannedScripts.add(s); }
      collectUrls(s.textContent, manifests, files);
    }
    var attrs = document.querySelectorAll('[data-src],[data-video],[data-video-src],[data-hls],[data-file],[data-url],[data-stream]');
    for (var j = 0; j < attrs.length; j++) {
      var a = attrs[j].attributes;
      for (var k = 0; k < a.length; k++) if (a[k].name.indexOf('data-') === 0) collectUrls(a[k].value, manifests, files);
    }
    for (var u in manifests) report(u, 'player');
    // Viele MP4s im Code sind meist Vorschau-Clips einer Übersicht, nicht das Video der Seite.
    var fileList = Object.keys(files);
    if (fileList.length <= 3) for (var f = 0; f < fileList.length; f++) report(fileList[f], 'player');
  }

  function probePlayers() {
    try { probeJwPlayer(); } catch (e) { }
    try { probeVideoJs(); } catch (e) { }
    try { probeCode(); } catch (e) { }
  }

  // Wie ein Wecker: 20 Sekunden lang jede Sekunde nachsehen. Player werden oft erst spät aufgebaut.
  function startProbing() {
    var ticks = 0;
    probePlayers();
    var id = setInterval(function () {
      probePlayers();
      if (++ticks >= 20) clearInterval(id);
    }, 1000);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', startProbing);
  else startProbing();

  // ---------- Werbeblocker: Werbeflächen ausblenden ----------
  // Die App antwortet auf „hello“ mit den Ausblende-Regeln für genau diesen Frame.

  function insertCss(css) {
    var put = function () {
      var s = document.createElement('style');
      s.setAttribute('data-streamplayer', '');
      s.textContent = css;
      (document.head || document.documentElement).appendChild(s);
    };
    if (document.documentElement) { put(); return; }
    new MutationObserver(function (m, o) {
      if (document.documentElement) { o.disconnect(); put(); }
    }).observe(document, { childList: true });
  }

  bridge.onmessage = function (ev) {
    var d;
    try { d = JSON.parse(ev.data); } catch (e) { return; }
    if (!d) return;
    if (d.t === 'css' && d.css) insertCss(d.css);
    else if (d.t === 'nudge') nudge();
    else if (d.t === 'pause') pauseAll();
  };

  // ---------- Modus „Nur ansehen“: anstoßen und anhalten ----------

  function quiet(promise) { if (promise && promise.catch) promise.catch(function () { }); }

  // Stumm abspielen, damit der Player seine Adresse abruft. Kein Klick auf irgendetwas:
  // Klicks auf Seiten mit Werbung öffnen sonst gern Fremdes.
  function nudge() {
    try {
      if (typeof window.jwplayer === 'function') {
        var jw = window.jwplayer();
        if (jw && jw.play) { try { jw.setMute(true); } catch (e) { } jw.play(); }
      }
    } catch (e) { }
    try {
      if (window.videojs && window.videojs.getPlayers) {
        var ps = window.videojs.getPlayers();
        for (var id in ps) if (ps[id]) { ps[id].muted(true); quiet(ps[id].play()); }
      }
    } catch (e) { }
    var vids = document.querySelectorAll('video');
    for (var i = 0; i < vids.length; i++) {
      try { vids[i].muted = true; quiet(vids[i].play()); } catch (e) { }
    }
    setTimeout(probePlayers, 800);
  }

  function pauseAll() {
    try { if (typeof window.jwplayer === 'function' && window.jwplayer().pause) window.jwplayer().pause(true); } catch (e) { }
    try {
      if (window.videojs && window.videojs.getPlayers) {
        var ps = window.videojs.getPlayers();
        for (var id in ps) if (ps[id]) ps[id].pause();
      }
    } catch (e) { }
    var vids = document.querySelectorAll('video, audio');
    for (var i = 0; i < vids.length; i++) { try { vids[i].pause(); } catch (e) { } }
  }
  // ---------- Prüfungen: „Bist du ein Mensch?“ ----------
  // Manche Seiten (oder ihr Video-Host im iframe) zeigen erst eine Prüfung: die Zwischenseite von
  // Cloudflare oder ein Kästchen von Turnstile, hCaptcha, reCAPTCHA. Lösen kann sie nur ein Mensch –
  // die App merkt nur, dass eine da ist, und zeigt dann die Seite. Ein Widget wohnt in seinem
  // eigenen Frame und meldet sich hier selbst, auch wenn es im geschlossenen Shadow-DOM steckt.
  // Nur sichtbare zählen: unsichtbare Varianten (reCAPTCHA v3, Turnstile „invisible“) laufen auf
  // vielen normalen Seiten mit und brauchen niemanden.

  var chId = Math.random().toString(36).slice(2);
  var chOn = false;

  function challengeHere() {
    var h = location.hostname, u = location.href;
    if (/size=invisible/.test(u)) return false;
    var widget = h === 'challenges.cloudflare.com' ||
      (/(^|\.)hcaptcha\.com$/.test(h) && /frame=(checkbox|challenge)/.test(location.hash)) ||
      (/(^|\.)(google\.com|recaptcha\.net)$/.test(h) && /\/recaptcha\/(api2|enterprise)\/(anchor|bframe)/.test(location.pathname));
    if (widget) return !isTop && window.innerWidth >= 100 && window.innerHeight >= 40;
    // Cloudflare-Zwischenseite („Einen Moment …“): ihr Skript legt _cf_chl_opt an.
    return isTop && (!!window._cf_chl_opt || !!document.getElementById('challenge-form'));
  }

  function checkChallenge() {
    var on = false;
    try { on = challengeHere(); } catch (e) { }
    if (on !== chOn) {
      chOn = on;
      post({ t: 'challenge', id: chId, on: on });
    }
  }

  var chTicks = 0;
  var chTimer = setInterval(function () {
    checkChallenge();
    // Fünf Minuten reichen; länger löst niemand ein Kästchen.
    if (++chTicks >= 300) clearInterval(chTimer);
  }, 1000);
  // Frame geht weg (gelöst, neu geladen): Prüfung vorbei.
  window.addEventListener('pagehide', function () {
    if (chOn) { chOn = false; post({ t: 'challenge', id: chId, on: false }); }
  });

  post({ t: 'hello', url: location.href });
})();
