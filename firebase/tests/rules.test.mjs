// Regeltests gegen den Firestore-Emulator: `npm test` im Ordner firebase/.
// Beide Richtungen: erlaubt, was die App braucht – verboten, was sie nicht braucht.
import { test, before, after, beforeEach } from 'node:test';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import {
  doc, getDoc, setDoc, deleteDoc, collection, getDocs, query, where,
  serverTimestamp, Timestamp, Bytes,
} from 'firebase/firestore';

let env;
before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-streamplayer',
    firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8') },
  });
});
after(() => env.cleanup());
beforeEach(() => env.clearFirestore());

const me = () => env.authenticatedContext('alice').firestore();
const other = () => env.authenticatedContext('mallory').firestore();
const anon = () => env.unauthenticatedContext().firestore();
const sync = (json = '{"id":"t1"}', deleted = false) => ({ json, updatedAt: serverTimestamp(), deleted });

test('eigene Titel, Profile, Fortschritt: schreiben, lesen, abfragen, löschen', async () => {
  const db = me();
  for (const path of ['users/alice/titles/t1', 'users/alice/libraries/anime', 'users/alice/profiles/main', 'users/alice/profiles/main/progress/t1']) {
    await assertSucceeds(setDoc(doc(db, path), sync()));
    await assertSucceeds(getDoc(doc(db, path)));
    await assertSucceeds(setDoc(doc(db, path), sync('', true)));   // weich löschen
    await assertSucceeds(deleteDoc(doc(db, path)));
  }
  // Die Abfrage, mit der die App nur Neues holt.
  await assertSucceeds(getDocs(query(collection(db, 'users/alice/titles'), where('updatedAt', '>', Timestamp.fromMillis(0)))));
});

test('fremde Konten: weder lesen noch schreiben', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), 'users/alice/titles/t1'), { json: '{}', updatedAt: Timestamp.now(), deleted: false });
  });
  const db = other();
  await assertFails(getDoc(doc(db, 'users/alice/titles/t1')));
  await assertFails(getDocs(collection(db, 'users/alice/titles')));
  await assertFails(setDoc(doc(db, 'users/alice/titles/t2'), sync()));
  await assertFails(deleteDoc(doc(db, 'users/alice/titles/t1')));
  await assertFails(getDoc(doc(db, 'users/alice/covers/x.jpg')));
});

test('ohne Anmeldung: nichts', async () => {
  const db = anon();
  await assertFails(getDoc(doc(db, 'users/alice/titles/t1')));
  await assertFails(setDoc(doc(db, 'users/alice/titles/t1'), sync()));
});

test('Abgleich-Dokument: nur die drei Felder, Serverzeit, Typen', async () => {
  const db = me();
  const p = doc(db, 'users/alice/titles/t1');
  // Erfundene Zeit: würde andere Geräte Änderungen verpassen lassen.
  await assertFails(setDoc(p, { json: '{}', updatedAt: Timestamp.fromMillis(Date.now() + 86400e3), deleted: false }));
  await assertFails(setDoc(p, { json: '{}', updatedAt: serverTimestamp(), deleted: false, extra: 1 }));
  await assertFails(setDoc(p, { json: '{}', updatedAt: serverTimestamp() }));
  await assertFails(setDoc(p, { json: 42, updatedAt: serverTimestamp(), deleted: false }));
  await assertFails(setDoc(p, { json: '{}', updatedAt: serverTimestamp(), deleted: 'nein' }));
  await assertFails(setDoc(p, { json: 'x'.repeat(900001), updatedAt: serverTimestamp(), deleted: false }));
});

test('Bilder: einzeln holen ja, auflisten nein, Größe begrenzt', async () => {
  const db = me();
  const p = doc(db, 'users/alice/covers/t1-custom-1.jpg');
  await assertSucceeds(setDoc(p, { data: Bytes.fromUint8Array(new Uint8Array(1000)), updatedAt: serverTimestamp() }));
  await assertSucceeds(getDoc(p));
  await assertFails(getDocs(collection(db, 'users/alice/covers')));
  await assertFails(setDoc(p, { data: 'kein Bild', updatedAt: serverTimestamp() }));
  await assertFails(setDoc(p, { data: Bytes.fromUint8Array(new Uint8Array(1000001)), updatedAt: serverTimestamp() }));
});

test('alles andere ist zu: Konto-Dokument, fremde Sammlungen, Wurzel', async () => {
  const db = me();
  await assertFails(setDoc(doc(db, 'users/alice'), { name: 'x' }));
  await assertFails(getDoc(doc(db, 'users/alice')));
  await assertFails(setDoc(doc(db, 'users/alice/admin/x'), sync()));
  await assertFails(setDoc(doc(db, 'settings/global'), { open: true }));
  await assertFails(getDocs(collection(db, 'users')));
});

// ---------- Geteilte Bibliotheken ----------
// Anlass (1.12.0): Bibliotheken mit Freunden teilen, nur zum Ansehen. Alice teilt, Bob tritt mit
// dem Code bei, Mallory hat keinen Code.
const bob = () => env.authenticatedContext('bob').firestore();
const share = (owner = 'alice') => ({ owner, ownerName: 'Alice', name: 'Anime', color: 1, updatedAt: serverTimestamp() });
const CODE = 'abcdefghijkmnopqrstuvw';

async function aliceShares() {
  const a = me();
  await assertSucceeds(setDoc(doc(a, 'shared/lib1'), share()));
  await assertSucceeds(setDoc(doc(a, `invites/${CODE}`), { shareId: 'lib1', owner: 'alice', createdAt: serverTimestamp() }));
  await assertSucceeds(setDoc(doc(a, 'shared/lib1/titles/t1'), sync()));
}

test('teilen: Besitzer legt an, lädt ein, spiegelt Titel', async () => {
  await aliceShares();
  // Fremder Besitzer eingetragen, zu kurzer Code, Einladung für fremde Bibliothek: nein.
  await assertFails(setDoc(doc(other(), 'shared/lib2'), share('alice')));
  await assertFails(setDoc(doc(me(), 'invites/kurz'), { shareId: 'lib1', owner: 'alice', createdAt: serverTimestamp() }));
  await assertFails(setDoc(doc(other(), 'invites/zzzzzzzzzzzzzzzzzzzzzz'), { shareId: 'lib1', owner: 'mallory', createdAt: serverTimestamp() }));
  // Übernehmen einer fremden geteilten Bibliothek: nein.
  await assertFails(setDoc(doc(other(), 'shared/lib1'), share('mallory')));
});

test('beitreten nur mit passendem Code, dann nur lesen', async () => {
  await aliceShares();
  const b = bob();
  await assertFails(getDocs(collection(b, 'shared/lib1/titles')));
  await assertSucceeds(getDoc(doc(b, `invites/${CODE}`)));
  await assertFails(getDocs(collection(b, 'invites')));
  // Falscher Code, fremder Name im Pfad: nein.
  await assertFails(setDoc(doc(b, 'shared/lib1/members/bob'), { code: 'falschfalschfalschfals', name: 'Bob', joinedAt: serverTimestamp() }));
  await assertFails(setDoc(doc(b, 'shared/lib1/members/carol'), { code: CODE, name: 'Bob', joinedAt: serverTimestamp() }));
  await assertSucceeds(setDoc(doc(b, 'shared/lib1/members/bob'), { code: CODE, name: 'Bob', joinedAt: serverTimestamp() }));
  await assertSucceeds(getDoc(doc(b, 'shared/lib1')));
  await assertSucceeds(getDocs(query(collection(b, 'shared/lib1/titles'), where('updatedAt', '>', Timestamp.fromMillis(0)))));
  // Nur ansehen: nichts schreiben, Mitglieder nicht auflisten.
  await assertFails(setDoc(doc(b, 'shared/lib1/titles/t2'), sync()));
  await assertFails(setDoc(doc(b, 'shared/lib1'), share('bob')));
  await assertFails(getDocs(collection(b, 'shared/lib1/members')));
  // Mallory ohne Code: nichts.
  await assertFails(getDoc(doc(other(), 'shared/lib1')));
  await assertFails(getDocs(collection(other(), 'shared/lib1/titles')));
  // Besitzer sieht Mitglieder und kann eins entfernen; Bob kann gehen.
  await assertSucceeds(getDocs(collection(me(), 'shared/lib1/members')));
  await assertSucceeds(deleteDoc(doc(b, 'shared/lib1/members/bob')));
  await assertFails(getDocs(collection(b, 'shared/lib1/titles')));
});
