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
  for (const path of ['users/alice/titles/t1', 'users/alice/profiles/main', 'users/alice/profiles/main/progress/t1']) {
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
