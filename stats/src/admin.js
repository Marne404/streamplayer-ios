// Die Admin-App (admin/): Überblick, Nutzer, Probleme. Nur lesen – verändert wird hier nichts.
//
// Zugang nur mit dem Schlüssel ADMIN_TOKEN (wrangler secret put ADMIN_TOKEN), im Kopf
// "authorization: Admin <Schlüssel>". Wie beim Casino-Worker: kein Konto, damit die Admin-App auch
// dann geht, wenn die Anmeldung klemmt, und kein Nutzerkonto je Admin-Rechte bekommen kann.
//
// „Heute“ ist der Tag der Admin-App (Feld today), denn die Tage in der Datenbank sind Ortszeit der
// Player-App. Fehlt er oder liegt er mehr als einen Tag neben UTC, gilt der UTC-Tag.
//
// Gratis-Tarif: höchstens 50 D1-Abfragen je Aufruf – jede Ansicht ist darum ein einziger batch.

import { isInstall, todayUtc, addDays, validDay, WINDOW_DAYS } from './report.js';

const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const enc = new TextEncoder();

/** Vergleicht den Schlüssel in konstanter Zeit (über die Hashes, damit auch die Länge nichts verrät). */
async function sameSecret(given, expected) {
  const [a, b] = await Promise.all([crypto.subtle.digest('SHA-256', enc.encode(given)), crypto.subtle.digest('SHA-256', enc.encode(expected))]);
  const x = new Uint8Array(a), y = new Uint8Array(b);
  let diff = 0;
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
  return diff === 0;
}

/** Die letzten 30 Tage bis [today], ältester zuerst. */
const window30 = (today) => Array.from({ length: WINDOW_DAYS }, (_, i) => addDays(today, i - (WINDOW_DAYS - 1)));

/** Reihe je Tag aus Zeilen mit Spalte day; fehlende Tage mit [empty]. */
function series(today, rows, map, empty) {
  const byDay = new Map(rows.map((r) => [r.day, r]));
  return window30(today).map((day) => (byDay.has(day) ? { day, ...map(byDay.get(day)) } : { day, ...empty }));
}

const versionList = (s) => [...new Set(String(s || '').split(',').filter(Boolean))].sort((a, b) => b.localeCompare(a, 'en', { numeric: true }));

function installOut(r) {
  return {
    install: r.install, account: r.account_uid ? { uid: r.account_uid, name: r.account_name || '' } : null,
    version: r.version, versionCode: r.version_code, sdk: r.sdk, lang: r.lang, firstSeen: r.first_seen, lastSeen: r.last_seen,
    counts: { titles: r.titles, libraries: r.libraries, profiles: r.profiles, downloadsStored: r.downloads_stored, bytesStored: r.bytes_stored },
  };
}

const DAY_EMPTY = { sessions: 0, watchMs: 0, plays: 0, dlStarted: 0, dlDone: 0, dlFailed: 0, findFailed: 0, crashes: 0 };
const dayOut = (r) => ({
  sessions: r.sessions || 0, watchMs: r.watch_ms || 0, plays: r.plays || 0, dlStarted: r.dl_started || 0,
  dlDone: r.dl_done || 0, dlFailed: r.dl_failed || 0, findFailed: r.find_failed || 0, crashes: r.crashes || 0,
});

// ------------------------------------------------------------------------------------- Ansichten

async function overview(env, today, now) {
  const from7 = addDays(today, -6), from30 = addDays(today, -(WINDOW_DAYS - 1));
  const q = (sql, ...b) => env.DB.prepare(sql).bind(...b);
  // Aktiv = an dem Tag mindestens eine Sitzung. Ein Bericht ohne Sitzung (nur Zählerstände) zählt nicht.
  const ACTIVE30 = 'SELECT install FROM days WHERE day >= ?1 AND sessions > 0';
  const [act, sums, daily, versions, accounts] = (await env.DB.batch([
    q(`SELECT COUNT(DISTINCT CASE WHEN day = ?1 THEN install END) AS d1, COUNT(DISTINCT CASE WHEN day >= ?2 THEN install END) AS d7,
       COUNT(DISTINCT install) AS d30 FROM days WHERE day >= ?3 AND sessions > 0`, today, from7, from30),
    q(`SELECT SUM(CASE WHEN day >= ?1 THEN watch_ms ELSE 0 END) AS w7, SUM(watch_ms) AS w30,
       SUM(CASE WHEN day >= ?1 THEN plays ELSE 0 END) AS p7, SUM(plays) AS p30 FROM days WHERE day >= ?2`, from7, from30),
    q(`SELECT day, SUM(sessions > 0) AS active, SUM(sessions) AS sessions, SUM(watch_ms) AS watch_ms, SUM(plays) AS plays,
       SUM(dl_started) AS dl_started, SUM(dl_done) AS dl_done, SUM(dl_failed) AS dl_failed, SUM(find_failed) AS find_failed,
       SUM(crashes) AS crashes FROM days WHERE day >= ?1 GROUP BY day`, from30),
    q(`SELECT version, MAX(version_code) AS code, COUNT(*) AS n FROM installs WHERE install IN (${ACTIVE30})
       GROUP BY version ORDER BY code DESC, version DESC`, from30),
    q(`SELECT COUNT(*) AS installs, SUM(first_seen >= ?2) AS new7,
       SUM(install IN (${ACTIVE30}) AND account_uid IS NOT NULL) AS with_account,
       SUM(install IN (${ACTIVE30}) AND account_uid IS NULL) AS without_account,
       COUNT(DISTINCT CASE WHEN install IN (${ACTIVE30}) THEN account_uid END) AS accounts FROM installs`, from30, now - 7 * 86400000),
  ])).map((r) => r.results || []);
  const a = act[0] || {}, s = sums[0] || {}, c = accounts[0] || {};
  return {
    ok: true, today,
    active: { today: a.d1 || 0, d7: a.d7 || 0, d30: a.d30 || 0 },
    watchMs: { d7: s.w7 || 0, d30: s.w30 || 0 },
    plays: { d7: s.p7 || 0, d30: s.p30 || 0 },
    installs: { total: c.installs || 0, new7: c.new7 || 0 },
    // Unter den in 30 Tagen aktiven Installationen: wie viele angemeldet sind, und wie viele
    // verschiedene Konten das sind (ein Konto kann auf mehreren Geräten laufen).
    accounts: { withAccount: c.with_account || 0, without: c.without_account || 0, distinct: c.accounts || 0 },
    days: series(today, daily, (r) => ({ active: r.active || 0, ...dayOut(r) }), { active: 0, ...DAY_EMPTY }),
    versions: versions.map((r) => ({ version: r.version || '?', versionCode: r.code || 0, installs: r.n })),
  };
}

async function users(env, today) {
  const from7 = addDays(today, -6);
  const rows = (await env.DB.prepare(`SELECT i.*, COALESCE(w.watch, 0) AS watch7, COALESCE(w.sessions, 0) AS sessions7,
      COALESCE(w.plays, 0) AS plays7 FROM installs i
      LEFT JOIN (SELECT install, SUM(watch_ms) AS watch, SUM(sessions) AS sessions, SUM(plays) AS plays FROM days
        WHERE day >= ?1 GROUP BY install) w ON w.install = i.install
      ORDER BY i.last_seen DESC LIMIT 2000`).bind(from7).all()).results || [];
  return { ok: true, today, users: rows.map((r) => ({ ...installOut(r), watchMs7: r.watch7, sessions7: r.sessions7, plays7: r.plays7 })) };
}

async function user(env, today, install) {
  const from30 = addDays(today, -(WINDOW_DAYS - 1));
  const q = (sql, ...b) => env.DB.prepare(sql).bind(...b);
  const [info, days, domains, crashes] = (await env.DB.batch([
    q('SELECT * FROM installs WHERE install = ?1', install),
    q('SELECT * FROM days WHERE install = ?1 AND day >= ?2', install, from30),
    q(`SELECT domain, SUM(count) AS count, COUNT(*) AS days, MAX(day) AS last FROM find_failed
       WHERE install = ?1 AND day >= ?2 GROUP BY domain ORDER BY count DESC, last DESC`, install, from30),
    q(`SELECT sig, SUM(count) AS count, MAX(day) AS last, group_concat(DISTINCT version) AS versions FROM crashes
       WHERE install = ?1 AND day >= ?2 GROUP BY sig ORDER BY last DESC, count DESC`, install, from30),
  ])).map((r) => r.results || []);
  if (!info[0]) return { ok: false, reason: 'Unbekannte Installation' };
  return {
    ok: true, today, user: installOut(info[0]),
    days: series(today, days, dayOut, DAY_EMPTY),
    domains: domains.map((r) => ({ domain: r.domain, count: r.count, days: r.days, last: r.last })),
    crashes: crashes.map((r) => ({ sig: r.sig, count: r.count, last: r.last, versions: versionList(r.versions) })),
  };
}

async function problems(env, today) {
  const from30 = addDays(today, -(WINDOW_DAYS - 1));
  const q = (sql, ...b) => env.DB.prepare(sql).bind(...b);
  const [domains, crashes, downloads] = (await env.DB.batch([
    q(`SELECT domain, COUNT(DISTINCT install) AS installs, SUM(count) AS count, MAX(day) AS last FROM find_failed
       WHERE day >= ?1 GROUP BY domain ORDER BY count DESC, installs DESC LIMIT 50`, from30),
    q(`SELECT sig, SUM(count) AS count, COUNT(DISTINCT install) AS installs, MAX(day) AS last,
       group_concat(DISTINCT version) AS versions FROM crashes WHERE day >= ?1 GROUP BY sig
       ORDER BY count DESC, last DESC LIMIT 50`, from30),
    q(`SELECT day, SUM(dl_started) AS started, SUM(dl_done) AS done, SUM(dl_failed) AS failed FROM days
       WHERE day >= ?1 GROUP BY day`, from30),
  ])).map((r) => r.results || []);
  // Fehlerquote = fehlgeschlagen / (fertig + fehlgeschlagen). Ohne beendete Downloads: null, nicht 0.
  const rate = (done, failed) => (done + failed > 0 ? failed / (done + failed) : null);
  const days = series(today, downloads, (r) => ({ started: r.started || 0, done: r.done || 0, failed: r.failed || 0, rate: rate(r.done || 0, r.failed || 0) }),
    { started: 0, done: 0, failed: 0, rate: null });
  const done = days.reduce((n, d) => n + d.done, 0), failed = days.reduce((n, d) => n + d.failed, 0);
  return {
    ok: true, today,
    domains: domains.map((r) => ({ domain: r.domain, installs: r.installs, count: r.count, last: r.last })),
    crashes: crashes.map((r) => ({ sig: r.sig, count: r.count, installs: r.installs, last: r.last, versions: versionList(r.versions) })),
    downloads: { done, failed, rate: rate(done, failed), days },
  };
}

// ------------------------------------------------------------------------------------- Eingang

export async function admin(request, env) {
  if (!env.ADMIN_TOKEN) return json({ ok: false, reason: 'Admin-Zugang ist nicht eingerichtet' }, 404);
  const given = (request.headers.get('authorization') || '').replace(/^Admin /, '');
  if (!given || !(await sameSecret(given, env.ADMIN_TOKEN))) return json({ ok: false, reason: 'Falscher Schlüssel' }, 401);
  const body = await request.json().catch(() => ({}));
  const op = String((body && body.op) || '');
  const now = Date.now();
  const today = validDay(body && body.today, todayUtc(now)) && body.today >= addDays(todayUtc(now), -1) ? body.today : todayUtc(now);
  switch (op) {
    case 'ping': return json({ ok: true, now, today });
    case 'overview': return json(await overview(env, today, now));
    case 'users': return json(await users(env, today));
    case 'user': {
      if (!isInstall(body.install)) return json({ ok: false, reason: 'Unbekannte Installation' });
      return json(await user(env, today, body.install.toLowerCase()));
    }
    case 'problems': return json(await problems(env, today));
    default: return json({ ok: false, reason: 'Unbekannte Aktion' });
  }
}
