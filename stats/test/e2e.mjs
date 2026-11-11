// Prüft den Statistik-Worker von außen: Bericht, Überschreiben statt Addieren, alle Admin-Ansichten
// und Vergessen. Benutzt eine Test-Installation, die am Ende (auch bei Fehlern) wieder gelöscht wird.
//
//   node stats/test/e2e.mjs                                   gegen den echten Worker
//   WORKER=http://localhost:8787 ADMIN_TOKEN=… node stats/test/e2e.mjs   gegen wrangler dev
//
// Der Admin-Schlüssel kommt aus ADMIN_TOKEN oder aus local.properties (stats.adminToken) im
// Repo-Ordner. Er wird nie ausgegeben.
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const W = process.env.WORKER || 'https://streamplayer-stats.marne-gamingx.workers.dev';
function adminToken() {
  if (process.env.ADMIN_TOKEN) return process.env.ADMIN_TOKEN;
  // Im Worktree eines Agenten liegt local.properties nur im Hauptordner.
  let dir = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
  for (const d of [dir, join(dir, '..', '..', '..')]) {
    const f = join(d, 'local.properties');
    if (!existsSync(f)) continue;
    const m = readFileSync(f, 'utf8').match(/^stats\.adminToken=(.+)$/m);
    if (m) return m[1].trim();
  }
  throw new Error('Kein Admin-Schlüssel (ADMIN_TOKEN oder local.properties: stats.adminToken)');
}
const ADMIN = adminToken();
const ID = '00000000-0000-4000-8000-00000000e2e1';

const post = (path, body, headers = {}) => fetch(W + path, {
  method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body: typeof body === 'string' ? body : JSON.stringify(body),
}).then(async (r) => ({ status: r.status, ...(await r.json().catch(() => ({}))) }));
const admin = (op, extra = {}, secret = ADMIN) => post('/admin', { op, ...extra }, { authorization: `Admin ${secret}` });

let failed = 0, n = 0;
const check = (name, ok, detail = '') => { n++; if (!ok) failed++; console.log(ok ? 'ok  ' : 'FAIL', name, ok ? '' : String(detail).slice(0, 400)); };

const day = (back) => { const d = new Date(); d.setUTCDate(d.getUTCDate() - back); return d.toISOString().slice(0, 10); };
const today = day(0), yesterday = day(1);
const report = (extra = {}) => ({
  install: ID, version: '0.0.0-e2e', versionCode: 1, sdk: 36, lang: 'de', account: { uid: 'e2e-uid', name: 'E2E Test' },
  counts: { titles: 12, libraries: 2, profiles: 1, downloadsStored: 3, bytesStored: 4567 },
  days: [
    { day: yesterday, sessions: 2, watchMs: 600000, plays: 3, dlStarted: 2, dlDone: 1, dlFailed: 1, findFailed: 2, crashes: 1 },
    { day: today, sessions: 1, watchMs: 120000, plays: 1, dlStarted: 0, dlDone: 0, dlFailed: 0, findFailed: 0, crashes: 0 },
  ],
  findFailedDomains: [{ day: yesterday, domain: 'e2e-a.example', count: 1 }, { day: yesterday, domain: 'e2e-b.example', count: 1 }],
  crashes: [{ day: yesterday, sig: 'E2eException @ Test.run', count: 1 }],
  ...extra,
});

try {
  // --- Eingang der App
  let r = await post('/v1/report', { install: 'kaputt' });
  check('report without valid install is refused', r.status === 400, JSON.stringify(r));
  r = await post('/v1/report', 'kein json');
  check('report that is not JSON is refused', r.status === 400, JSON.stringify(r));
  r = await post('/v1/report', JSON.stringify({ ...report(), pad: 'x'.repeat(70 * 1024) }));
  check('report over 64 KB is refused with 413', r.status === 413, JSON.stringify(r));
  r = await post('/v1/forget', { install: 'kaputt' });
  check('forget without valid install is refused', r.status === 400, JSON.stringify(r));

  r = await post('/v1/report', report());
  check('report is accepted', r.status === 200 && r.ok === true, JSON.stringify(r));
  r = await post('/v1/report', report());
  check('the same report again is accepted', r.ok === true, JSON.stringify(r));

  // --- Schlüssel
  r = await admin('ping', {}, 'falsch');
  check('wrong key is refused', r.status === 401 && r.ok === false, JSON.stringify(r));
  r = await post('/admin', { op: 'ping' });
  check('no key is refused', r.status === 401, JSON.stringify(r));
  r = await admin('ping', { today });
  check('right key works', r.ok === true && r.today === today, JSON.stringify(r));

  // --- Einzelne Installation: zweimal gemeldet, trotzdem einfach gezählt
  r = await admin('user', { install: ID, today });
  const y = r.days && r.days.find((d) => d.day === yesterday);
  check('user detail exists with account', r.ok && r.user.account && r.user.account.name === 'E2E Test' && r.user.counts.titles === 12, JSON.stringify(r).slice(0, 300));
  check('user detail has 30 days', r.ok && r.days.length === 30 && r.days[29].day === today, JSON.stringify(r.days && r.days.slice(-2)));
  check('repeated report overwrites instead of adding', y && y.sessions === 2 && y.watchMs === 600000 && y.crashes === 1, JSON.stringify(y));
  check('user failed domains', r.ok && r.domains.length === 2 && r.domains.every((d) => d.count === 1), JSON.stringify(r.domains));
  check('user crashes with version', r.ok && r.crashes.length === 1 && r.crashes[0].versions[0] === '0.0.0-e2e', JSON.stringify(r.crashes));

  // Neuer Bericht für denselben Tag ohne e2e-b: die Domain fällt weg. Neue Version: der alte
  // Absturz behält seine Version.
  r = await post('/v1/report', report({ version: '0.0.1-e2e', versionCode: 2, account: null, findFailedDomains: [{ day: yesterday, domain: 'e2e-a.example', count: 3 }] }));
  check('second report is accepted', r.ok === true, JSON.stringify(r));
  r = await admin('user', { install: ID, today });
  check('domain no longer reported is gone, count overwritten', r.ok && r.domains.length === 1 && r.domains[0].domain === 'e2e-a.example' && r.domains[0].count === 3, JSON.stringify(r.domains));
  check('crash keeps the version it was first reported with', r.ok && r.crashes[0].versions.join() === '0.0.0-e2e', JSON.stringify(r.crashes));
  check('signing out removes the account name', r.ok && r.user.account === null && r.user.version === '0.0.1-e2e', JSON.stringify(r.user));

  // --- Ansichten über alle
  r = await admin('overview', { today });
  check('overview', r.ok && r.active.today >= 1 && r.active.d7 >= 1 && r.active.d30 >= 1 && r.days.length === 30, JSON.stringify(r).slice(0, 300));
  check('overview knows the test version', r.ok && r.versions.some((v) => v.version === '0.0.1-e2e' && v.installs === 1), JSON.stringify(r.versions));
  check('overview watch time includes the test', r.ok && r.watchMs.d7 >= 720000 && r.watchMs.d30 >= r.watchMs.d7, JSON.stringify(r.watchMs));
  check('overview counts installs without account', r.ok && r.accounts.without >= 1, JSON.stringify(r.accounts));
  r = await admin('users', { today });
  const me = r.users && r.users.find((u) => u.install === ID);
  check('users lists the test install', me && me.watchMs7 === 720000 && me.sessions7 === 3 && me.counts.bytesStored === 4567, JSON.stringify(me));
  r = await admin('problems', { today });
  check('problems lists the failed domain', r.ok && r.domains.some((d) => d.domain === 'e2e-a.example' && d.count >= 3 && d.installs >= 1), JSON.stringify(r.domains).slice(0, 300));
  check('problems lists the crash', r.ok && r.crashes.some((c) => c.sig === 'E2eException @ Test.run' && c.versions.includes('0.0.0-e2e')), JSON.stringify(r.crashes).slice(0, 300));
  check('problems has download failure rate per day', r.ok && r.downloads.days.length === 30 && r.downloads.days.find((d) => d.day === yesterday).failed >= 1, JSON.stringify(r.downloads).slice(0, 300));
  r = await admin('user', { install: 'kaputt' });
  check('user with bad id says why', r.ok === false && r.reason, JSON.stringify(r));
  r = await admin('gibtsnicht');
  check('unknown op says why', r.ok === false && r.reason, JSON.stringify(r));
} finally {
  // --- Vergessen (räumt zugleich auf)
  const r = await post('/v1/forget', { install: ID });
  check('forget', r.ok === true, JSON.stringify(r));
  const after = await admin('user', { install: ID });
  check('after forget nothing is left', after.ok === false, JSON.stringify(after).slice(0, 200));
  const p = await admin('problems');
  check('after forget the domains and crashes are gone too', p.ok && !p.domains.some((d) => d.domain.startsWith('e2e-')) && !p.crashes.some((c) => c.sig.startsWith('E2e')), JSON.stringify(p).slice(0, 300));
}

console.log(`${n - failed}/${n} ok`);
process.exit(failed ? 1 : 0);
