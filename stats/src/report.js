// Prüft einen Bericht der Player-App, bevor irgendetwas in die Datenbank geht.
//
// Der Endpunkt ist offen (die App hat keinen Schlüssel, den man nicht aus der APK holen könnte).
// Deshalb gilt hier: alles ist verdächtig. Zahlen werden gedeckelt, Zeichenketten gekürzt, Tage
// außerhalb des Fensters fallen weg. Einzelne kaputte Einträge werden übersprungen statt den ganzen
// Bericht abzulehnen – wie beim Katalog-Import: lieber die guten Teile behalten.
//
// Datenschutz: Es gibt hier bewusst kein Feld für Titel, Adressen oder Suchbegriffe. Was der
// Bericht darüber hinaus mitschickt, wird nie gelesen und landet nirgends.
//
// Reine Funktionen ohne Cloudflare-Bezug, damit test/report.test.mjs sie mit node:test prüfen kann.

export const MAX_BODY = 64 * 1024;
/** So weit zurück nimmt der Server Tage an. Die App schickt die letzten 14. */
export const WINDOW_DAYS = 30;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;
// Ein Hostname: Teile aus Buchstaben, Ziffern, Bindestrich, durch Punkte getrennt, mindestens zwei.
const HOST = /^(?=.{3,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$/;

/** Obergrenzen je Tag. Großzügig, aber so, dass ein einzelner Bericht keine Summe sprengen kann. */
const DAY_CAPS = {
  sessions: 1000, watchMs: 24 * 3600 * 1000, plays: 10000,
  dlStarted: 10000, dlDone: 10000, dlFailed: 10000, findFailed: 10000, crashes: 1000,
};
const COUNT_CAPS = { titles: 1e6, libraries: 1e4, profiles: 1e3, downloadsStored: 1e6, bytesStored: 1e15 };
const MAX_DOMAINS = 100;
const MAX_CRASHES = 50;

export const isInstall = (v) => typeof v === 'string' && UUID.test(v.toLowerCase());

/** Ganze Zahl in [0, cap]; alles andere (Text, NaN, negativ) wird 0. */
export function num(v, cap) {
  const n = Math.floor(Number(v));
  return Number.isFinite(n) && n > 0 ? Math.min(n, cap) : 0;
}

/** Freitext ohne Steuerzeichen, auf [max] Zeichen gekürzt. */
export function text(v, max) {
  if (typeof v !== 'string') return '';
  return v.replace(/[\u0000-\u001f\u007f-\u009f\u2028\u2029]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, max);
}

/** Tag als 'YYYY-MM-DD' [n] Tage nach [day]. */
export function addDays(day, n) {
  const d = new Date(`${day}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}
export const todayUtc = (now = Date.now()) => new Date(now).toISOString().slice(0, 10);

/**
 * Ein gültiger Tag im Fenster, sonst null. Die App rechnet in ihrer Ortszeit: in Deutschland ist
 * kurz nach Mitternacht schon „morgen“, während es in UTC noch „heute“ ist. Ein Tag Vorlauf ist
 * darum erlaubt, mehr nicht.
 */
export function validDay(v, today) {
  if (typeof v !== 'string' || !ISO_DAY.test(v)) return null;
  const d = new Date(`${v}T00:00:00Z`);
  if (Number.isNaN(d.getTime()) || d.toISOString().slice(0, 10) !== v) return null; // 2026-02-31
  if (v > addDays(today, 1) || v < addDays(today, -(WINDOW_DAYS - 1))) return null;
  return v;
}

/** Nur der Hostname einer Seite – sollte die App doch einmal mehr schicken, bleibt der Rest weg. */
export function domain(v) {
  if (typeof v !== 'string') return null;
  let s = v.trim().toLowerCase();
  if (s.includes('/') || s.includes('@') || s.includes(':')) {
    try { s = new URL(s.includes('://') ? s : `http://${s}`).hostname; } catch { return null; }
  }
  s = s.replace(/^www\./, '').replace(/\.$/, '');
  return HOST.test(s) ? s : null;
}

/**
 * Bericht → bereinigtes Objekt, oder { error } wenn er gar nicht zu gebrauchen ist (keine gültige
 * Installation). [now] nur für Tests.
 */
export function parseReport(body, now = Date.now()) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) return { error: 'Kein Bericht' };
  if (!isInstall(body.install)) return { error: 'Ungültige Installation' };
  const today = todayUtc(now);

  const acc = body.account && typeof body.account === 'object' ? body.account : null;
  const uid = acc ? text(acc.uid, 128) : '';
  const counts = body.counts && typeof body.counts === 'object' ? body.counts : {};

  // Tage: je Tag nur ein Eintrag; kommt einer doppelt, gilt der letzte (wie beim Überschreiben).
  const days = new Map();
  for (const d of Array.isArray(body.days) ? body.days.slice(0, 60) : []) {
    const day = d && validDay(d.day, today);
    if (!day) continue;
    days.set(day, Object.fromEntries(Object.entries(DAY_CAPS).map(([k, cap]) => [k, num(d[k], cap)])));
  }

  // Fehl-Domains und Abstürze: gleiche Schlüssel am selben Tag werden zusammengezählt.
  const merge = (list, max, key, cap) => {
    const out = new Map();
    for (const e of Array.isArray(list) ? list.slice(0, max * 4) : []) {
      const day = e && validDay(e.day, today);
      const k = day && key(e);
      // Ohne Anzahl zählt der Eintrag einmal; eine ausdrückliche 0 ist kein Ereignis.
      const n = e && e.count === undefined ? 1 : num(e && e.count, cap);
      if (!k || !n) continue;
      const id = `${day}\t${k}`;
      const prev = out.get(id);
      if (!prev && out.size >= max) continue;
      out.set(id, { day, key: k, count: Math.min(cap, (prev ? prev.count : 0) + n) });
    }
    return [...out.values()];
  };
  const domains = merge(body.findFailedDomains, MAX_DOMAINS, (e) => domain(e.domain), DAY_CAPS.findFailed)
    .map(({ day, key, count }) => ({ day, domain: key, count }));
  const crashes = merge(body.crashes, MAX_CRASHES, (e) => text(e.sig, 200), DAY_CAPS.crashes)
    .map(({ day, key, count }) => ({ day, sig: key, count }));

  // Diese Tage gelten als neu gemeldet: was der Server dort noch an Domains/Abstürzen hat, aber
  // nicht mehr im Bericht steht, fällt weg. So überschreibt ein Bericht seine Tage vollständig.
  const covered = new Set([...days.keys(), ...domains.map((d) => d.day), ...crashes.map((c) => c.day)]);

  return {
    install: body.install.toLowerCase(),
    version: text(body.version, 32).replace(/[^0-9A-Za-z._+-]/g, ''),
    versionCode: num(body.versionCode, 1e9),
    sdk: num(body.sdk, 1000),
    lang: text(body.lang, 16).replace(/[^A-Za-z-]/g, '').toLowerCase(),
    account: uid ? { uid, name: text(acc.name, 80) } : null,
    counts: Object.fromEntries(Object.entries(COUNT_CAPS).map(([k, cap]) => [k, num(counts[k], cap)])),
    days: [...days.entries()].map(([day, v]) => ({ day, ...v })),
    domains,
    crashes,
    covered: [...covered],
  };
}
