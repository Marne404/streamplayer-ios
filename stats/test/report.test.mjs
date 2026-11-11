// Prüft die Bereinigung der Berichte (src/report.js). Anlass: /v1/report ist offen – jeder kann
// dorthin schicken, was er will. Was hier durchrutscht, steht in der Datenbank.
//
//   node --test stats/test/
import test from 'node:test';
import assert from 'node:assert/strict';
import { parseReport, domain, validDay, num, text } from '../src/report.js';

const NOW = Date.UTC(2026, 10, 11, 12, 0, 0); // 11.11.2026, 12 Uhr UTC
const ID = '3f2b8c1e-9a4d-4e6f-8b2a-1c3d5e7f9a0b';
const base = (extra = {}) => ({ install: ID, version: '1.12.0', versionCode: 24, sdk: 36, lang: 'de', account: null, ...extra });

test('ein gültiger Bericht kommt vollständig durch', () => {
  const r = parseReport(base({
    account: { uid: 'abc', name: 'Marne' },
    counts: { titles: 120, libraries: 3, profiles: 2, downloadsStored: 7, bytesStored: 123456789 },
    days: [{ day: '2026-11-11', sessions: 3, watchMs: 3600000, plays: 5, dlStarted: 2, dlDone: 1, dlFailed: 1, findFailed: 1, crashes: 0 }],
    findFailedDomains: [{ day: '2026-11-11', domain: 'example.org', count: 1 }],
    crashes: [{ day: '2026-11-11', sig: 'IllegalStateException @ PlayerActivity.onCreate', count: 1 }],
  }), NOW);
  assert.equal(r.error, undefined);
  assert.equal(r.install, ID);
  assert.deepEqual(r.account, { uid: 'abc', name: 'Marne' });
  assert.equal(r.counts.bytesStored, 123456789);
  assert.deepEqual(r.days, [{ day: '2026-11-11', sessions: 3, watchMs: 3600000, plays: 5, dlStarted: 2, dlDone: 1, dlFailed: 1, findFailed: 1, crashes: 0 }]);
  assert.deepEqual(r.domains, [{ day: '2026-11-11', domain: 'example.org', count: 1 }]);
  assert.deepEqual(r.crashes, [{ day: '2026-11-11', sig: 'IllegalStateException @ PlayerActivity.onCreate', count: 1 }]);
  assert.deepEqual(r.covered, ['2026-11-11']);
});

test('ohne gültige Installation wird abgelehnt', () => {
  assert.ok(parseReport(base({ install: 'nicht-uuid' }), NOW).error);
  assert.ok(parseReport(base({ install: 42 }), NOW).error);
  assert.ok(parseReport(null, NOW).error);
  assert.ok(parseReport([], NOW).error);
  // Großbuchstaben sind dieselbe UUID.
  assert.equal(parseReport(base({ install: ID.toUpperCase() }), NOW).install, ID);
});

test('Tage: nur ISO, nicht in der Zukunft (ein Tag Zeitzone erlaubt), nicht älter als 30 Tage', () => {
  const today = '2026-11-11';
  assert.equal(validDay('2026-11-12', today), '2026-11-12');
  assert.equal(validDay('2026-11-13', today), null);
  assert.equal(validDay('2026-10-13', today), '2026-10-13'); // 30. Tag
  assert.equal(validDay('2026-10-12', today), null);
  assert.equal(validDay('2026-02-31', '2026-03-10'), null);
  assert.equal(validDay('11.11.2026', today), null);
  assert.equal(validDay(20261111, today), null);
});

test('Zahlen: negativ, Text und NaN werden 0, zu große gedeckelt, Brüche abgeschnitten', () => {
  assert.equal(num(-5, 10), 0);
  assert.equal(num('abc', 10), 0);
  assert.equal(num(NaN, 10), 0);
  assert.equal(num(Infinity, 10), 0);
  assert.equal(num(1e12, 10), 10);
  assert.equal(num(3.9, 10), 3);
  assert.equal(num('7', 10), 7);
  const r = parseReport(base({ days: [{ day: '2026-11-11', sessions: -1, watchMs: 1e15, plays: 'x' }] }), NOW);
  assert.equal(r.days[0].sessions, 0);
  assert.equal(r.days[0].watchMs, 24 * 3600 * 1000);
  assert.equal(r.days[0].plays, 0);
  assert.equal(r.days[0].crashes, 0);
});

test('doppelte Tage: der letzte gilt; Domains und Abstürze am selben Tag werden addiert', () => {
  const r = parseReport(base({
    days: [{ day: '2026-11-10', sessions: 1 }, { day: '2026-11-10', sessions: 4 }],
    findFailedDomains: [{ day: '2026-11-10', domain: 'a.org', count: 2 }, { day: '2026-11-10', domain: 'A.org', count: 3 }, { day: '2026-11-09', domain: 'a.org' }],
    crashes: [{ day: '2026-11-10', sig: 'X', count: 0 }],
  }), NOW);
  assert.deepEqual(r.days.map((d) => d.sessions), [4]);
  assert.deepEqual(r.domains, [{ day: '2026-11-10', domain: 'a.org', count: 5 }, { day: '2026-11-09', domain: 'a.org', count: 1 }]);
  assert.deepEqual(r.crashes, []); // count 0 ist kein Absturz
  assert.deepEqual(r.covered.sort(), ['2026-11-09', '2026-11-10']);
});

test('Domains: nur der Hostname, nie Pfad, Anfrage oder Anmeldedaten', () => {
  assert.equal(domain('Example.ORG'), 'example.org');
  assert.equal(domain('www.example.org'), 'example.org');
  assert.equal(domain('https://user:pw@sub.example.org:8443/serie/geheim?q=suchwort#x'), 'sub.example.org');
  assert.equal(domain('example.org/film/titel'), 'example.org');
  assert.equal(domain('localhost'), null);
  assert.equal(domain('ein titel mit leerzeichen'), null);
  assert.equal(domain(''), null);
  assert.equal(domain(null), null);
  assert.equal(domain('x'.repeat(300) + '.org'), null);
});

test('Texte: Steuerzeichen weg, gekürzt; Kontoname nur mit uid', () => {
  assert.equal(text('a\nb\u0000c', 10), 'a b c');
  assert.equal(text('x'.repeat(500), 200).length, 200);
  const r = parseReport(base({ account: { uid: '', name: 'Ohne uid' }, crashes: [{ day: '2026-11-11', sig: 'E'.repeat(999), count: 1 }] }), NOW);
  assert.equal(r.account, null);
  assert.equal(r.crashes[0].sig.length, 200);
  assert.equal(parseReport(base({ version: '1.12.0"; DROP TABLE' }), NOW).version, '1.12.0DROPTABLE');
});

test('Listen sind begrenzt: höchstens 100 Domains und 50 Absturz-Signaturen', () => {
  const r = parseReport(base({
    findFailedDomains: Array.from({ length: 300 }, (_, i) => ({ day: '2026-11-11', domain: `d${i}.org`, count: 1 })),
    crashes: Array.from({ length: 300 }, (_, i) => ({ day: '2026-11-11', sig: `E${i}`, count: 1 })),
  }), NOW);
  assert.equal(r.domains.length, 100);
  assert.equal(r.crashes.length, 50);
});

test('unbekannte Felder (z. B. Titel oder Adressen) fallen weg', () => {
  const r = parseReport(base({ title: 'Geheim', url: 'https://example.org/x', days: [{ day: '2026-11-11', sessions: 1, title: 'Film' }] }), NOW);
  assert.ok(!JSON.stringify(r).includes('Geheim'));
  assert.ok(!JSON.stringify(r).includes('Film'));
  assert.ok(!JSON.stringify(r).includes('example.org/x'));
});
