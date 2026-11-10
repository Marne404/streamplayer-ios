// Firestore-Regeln über die Rules-API einspielen – mit einem Dienstkonto statt `firebase login`.
// Anlass: `firebase deploy` braucht zusätzlich Rechte auf die Service-Usage-API, die das
// Firebase-Admin-Dienstkonto nicht hat; die Rules-API selbst darf es.
//
//   GOOGLE_APPLICATION_CREDENTIALS=~/Downloads/<projekt>-firebase-adminsdk-….json npm run deploy:rules:sa
//
// Der Schlüssel bleibt, wo er liegt: nie ins Repo kopieren, nie ausgeben.
import { readFileSync } from 'node:fs';
import { GoogleAuth } from 'google-auth-library';

const project = JSON.parse(readFileSync(new URL('../.firebaserc', import.meta.url))).projects.default;
const source = readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8');
const auth = new GoogleAuth({ scopes: ['https://www.googleapis.com/auth/firebase'] });
const client = await auth.getClient();
const api = `https://firebaserules.googleapis.com/v1/projects/${project}`;

const ruleset = (await client.request({
  url: `${api}/rulesets`, method: 'POST',
  data: { source: { files: [{ name: 'firestore.rules', content: source }] } },
})).data;
const release = (await client.request({
  url: `${api}/releases/cloud.firestore`, method: 'PATCH',
  data: { release: { name: `projects/${project}/releases/cloud.firestore`, rulesetName: ruleset.name } },
})).data;
console.log(`Regeln aktiv in ${project}: ${release.rulesetName} (${release.updateTime})`);
