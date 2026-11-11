// Nutzungsstatistik des Players: Die App schickt Tagessummen (nur Zahlen), die Admin-App
// (admin/) liest sie. Eigener Worker statt Firebase, weil D1 im Gratis-Tarif 100.000 Schreibvorgänge
// am Tag erlaubt und keine Anmeldung braucht – die Statistik soll auch ohne Konto gehen.
//
//   POST /v1/report   Tagessummen der letzten Tage (report.js prüft alles)
//   POST /v1/forget   Statistik in der App abgeschaltet: alles zu dieser Installation löschen
//   POST /admin       nur mit Admin-Schlüssel (admin.js)
//
// Täglich um 03:17 UTC räumt cleanup() alte Tage weg: gebraucht werden nur 30, behalten werden 90.

import { parseReport, isInstall, MAX_BODY, todayUtc, addDays } from './report.js';
import { admin } from './admin.js';

const KEEP_DAYS = 90;
const KEEP_INSTALL_DAYS = 180;

const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

/**
 * Liest den Körper als JSON, höchstens [MAX_BODY] Bytes. Die Länge im Kopf kann fehlen oder lügen,
 * darum wird zusätzlich gezählt, was wirklich ankommt.
 */
async function readJson(request) {
  if (Number(request.headers.get('content-length') || 0) > MAX_BODY) return { status: 413 };
  const reader = request.body && request.body.getReader();
  if (!reader) return { status: 400 };
  const chunks = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > MAX_BODY) { await reader.cancel().catch(() => {}); return { status: 413 }; }
    chunks.push(value);
  }
  const all = new Uint8Array(size);
  let at = 0;
  for (const c of chunks) { all.set(c, at); at += c.byteLength; }
  try { return { body: JSON.parse(new TextDecoder().decode(all)) }; } catch { return { status: 400 }; }
}

/**
 * Speichert einen Bericht in einem Rutsch (D1-batch = eine Transaktion, sechs Abfragen, egal wie
 * viele Tage – die Listen gehen als JSON hinein und werden in SQL mit json_each ausgepackt).
 * Tage werden überschrieben, nicht addiert: Schickt die App denselben Bericht zweimal, ändert sich nichts.
 */
async function storeReport(env, r, now) {
  const q = (sql, ...b) => env.DB.prepare(sql).bind(...b);
  const c = r.counts;
  const covered = JSON.stringify(r.covered);
  await env.DB.batch([
    q(`INSERT INTO installs (install, first_seen, last_seen, version, version_code, sdk, lang, account_uid, account_name,
         titles, libraries, profiles, downloads_stored, bytes_stored)
       VALUES (?1, ?2, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)
       ON CONFLICT(install) DO UPDATE SET last_seen = excluded.last_seen, version = excluded.version,
         version_code = excluded.version_code, sdk = excluded.sdk, lang = excluded.lang,
         account_uid = excluded.account_uid, account_name = excluded.account_name, titles = excluded.titles,
         libraries = excluded.libraries, profiles = excluded.profiles, downloads_stored = excluded.downloads_stored,
         bytes_stored = excluded.bytes_stored`,
      r.install, now, r.version, r.versionCode, r.sdk, r.lang, r.account ? r.account.uid : null, r.account ? r.account.name : null,
      c.titles, c.libraries, c.profiles, c.downloadsStored, c.bytesStored),
    // „WHERE true“: ohne sie hielte SQLite das ON CONFLICT für den Teil eines JOINs.
    q(`INSERT INTO days (install, day, sessions, watch_ms, plays, dl_started, dl_done, dl_failed, find_failed, crashes)
       SELECT ?1, json_extract(value, '$.day'), json_extract(value, '$.sessions'), json_extract(value, '$.watchMs'),
         json_extract(value, '$.plays'), json_extract(value, '$.dlStarted'), json_extract(value, '$.dlDone'),
         json_extract(value, '$.dlFailed'), json_extract(value, '$.findFailed'), json_extract(value, '$.crashes')
       FROM json_each(?2) WHERE true
       ON CONFLICT(install, day) DO UPDATE SET sessions = excluded.sessions, watch_ms = excluded.watch_ms,
         plays = excluded.plays, dl_started = excluded.dl_started, dl_done = excluded.dl_done,
         dl_failed = excluded.dl_failed, find_failed = excluded.find_failed, crashes = excluded.crashes`,
      r.install, JSON.stringify(r.days)),
    // Was an den gemeldeten Tagen nicht mehr im Bericht steht, ist auch nicht mehr wahr.
    q(`DELETE FROM find_failed WHERE install = ?1 AND day IN (SELECT value FROM json_each(?2))
       AND day || char(9) || domain NOT IN (SELECT value FROM json_each(?3))`,
      r.install, covered, JSON.stringify(r.domains.map((d) => `${d.day}\t${d.domain}`))),
    q(`INSERT INTO find_failed (install, day, domain, count)
       SELECT ?1, json_extract(value, '$.day'), json_extract(value, '$.domain'), json_extract(value, '$.count')
       FROM json_each(?2) WHERE true
       ON CONFLICT(install, day, domain) DO UPDATE SET count = excluded.count`,
      r.install, JSON.stringify(r.domains)),
    q(`DELETE FROM crashes WHERE install = ?1 AND day IN (SELECT value FROM json_each(?2))
       AND day || char(9) || sig NOT IN (SELECT value FROM json_each(?3))`,
      r.install, covered, JSON.stringify(r.crashes.map((c) => `${c.day}\t${c.sig}`))),
    // Die Version bleibt die vom ersten Melden: Nach einem Update schickt die App alte Abstürze
    // noch zwei Wochen lang mit, die gehören aber zur alten Version.
    q(`INSERT INTO crashes (install, day, sig, count, version)
       SELECT ?1, json_extract(value, '$.day'), json_extract(value, '$.sig'), json_extract(value, '$.count'), ?3
       FROM json_each(?2) WHERE true
       ON CONFLICT(install, day, sig) DO UPDATE SET count = excluded.count`,
      r.install, JSON.stringify(r.crashes), r.version),
  ]);
}

/** Alles zu einer Installation weg – die App hat die Statistik abgeschaltet. */
export async function forget(env, install) {
  await env.DB.batch(['installs', 'days', 'find_failed', 'crashes']
    .map((t) => env.DB.prepare(`DELETE FROM ${t} WHERE install = ?1`).bind(install)));
}

async function cleanup(env, now = Date.now()) {
  const before = addDays(todayUtc(now), -KEEP_DAYS);
  await env.DB.batch([
    ...['days', 'find_failed', 'crashes'].map((t) => env.DB.prepare(`DELETE FROM ${t} WHERE day < ?1`).bind(before)),
    env.DB.prepare('DELETE FROM installs WHERE last_seen < ?1').bind(now - KEEP_INSTALL_DAYS * 86400000),
  ]);
}

export default {
  async fetch(request, env) {
    const { pathname } = new URL(request.url);
    if (request.method !== 'POST') {
      return pathname === '/' ? new Response('streamplayer-stats\n') : json({ ok: false, reason: 'Nicht gefunden' }, 404);
    }
    try {
      if (pathname === '/admin') return await admin(request, env);
      if (pathname !== '/v1/report' && pathname !== '/v1/forget') return json({ ok: false, reason: 'Nicht gefunden' }, 404);

      const { body, status } = await readJson(request);
      if (status === 413) return json({ ok: false, reason: 'Bericht zu groß' }, 413);
      if (status) return json({ ok: false, reason: 'Kein JSON' }, 400);

      if (pathname === '/v1/forget') {
        const install = body && body.install;
        if (!isInstall(install)) return json({ ok: false, reason: 'Ungültige Installation' }, 400);
        await forget(env, install.toLowerCase());
        return json({ ok: true });
      }
      const now = Date.now();
      const r = parseReport(body, now);
      if (r.error) return json({ ok: false, reason: r.error }, 400);
      await storeReport(env, r, now);
      return json({ ok: true });
    } catch (e) {
      console.error(pathname, e);
      return json({ ok: false, reason: 'Serverfehler' }, 500);
    }
  },

  async scheduled(event, env, ctx) {
    ctx.waitUntil(cleanup(env));
  },
};
