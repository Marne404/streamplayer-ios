-- Nutzungsstatistik des Players (D1 „streamplayer-stats“). Einspielen:
--   npx wrangler d1 execute streamplayer-stats --remote --file=schema.sql
-- Nur Zahlen je Installation und Tag – keine Titel, keine Seitenadressen, keine Suchbegriffe.
-- Fehlgeschlagene Video-Suchen nur mit dem Hostnamen der Seite.

-- Eine Zeile je Installation: was der letzte Bericht über sie sagt.
CREATE TABLE IF NOT EXISTS installs (
  install TEXT PRIMARY KEY,           -- zufällige UUID der App, kein Gerätekennzeichen
  first_seen INTEGER NOT NULL,        -- ms seit 1970, erster Bericht
  last_seen INTEGER NOT NULL,         -- ms seit 1970, letzter Bericht
  version TEXT NOT NULL DEFAULT '',
  version_code INTEGER NOT NULL DEFAULT 0,
  sdk INTEGER NOT NULL DEFAULT 0,
  lang TEXT NOT NULL DEFAULT '',
  account_uid TEXT,                   -- nur wenn angemeldet
  account_name TEXT,
  titles INTEGER NOT NULL DEFAULT 0,
  libraries INTEGER NOT NULL DEFAULT 0,
  profiles INTEGER NOT NULL DEFAULT 0,
  downloads_stored INTEGER NOT NULL DEFAULT 0,
  bytes_stored INTEGER NOT NULL DEFAULT 0
);

-- Summen je Installation und Tag (Ortszeit der App, 'YYYY-MM-DD'). Ein neuer Bericht überschreibt
-- den Tag, statt zu addieren: die App schickt die letzten 14 Tage jedes Mal neu.
CREATE TABLE IF NOT EXISTS days (
  install TEXT NOT NULL,
  day TEXT NOT NULL,
  sessions INTEGER NOT NULL DEFAULT 0,
  watch_ms INTEGER NOT NULL DEFAULT 0,
  plays INTEGER NOT NULL DEFAULT 0,
  dl_started INTEGER NOT NULL DEFAULT 0,
  dl_done INTEGER NOT NULL DEFAULT 0,
  dl_failed INTEGER NOT NULL DEFAULT 0,
  find_failed INTEGER NOT NULL DEFAULT 0,
  crashes INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (install, day)
);
CREATE INDEX IF NOT EXISTS days_by_day ON days (day);

-- Seiten, auf denen die Video-Suche nichts fand: nur der Hostname.
CREATE TABLE IF NOT EXISTS find_failed (
  install TEXT NOT NULL,
  day TEXT NOT NULL,
  domain TEXT NOT NULL,
  count INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (install, day, domain)
);
CREATE INDEX IF NOT EXISTS find_failed_by_day ON find_failed (day);

-- Abstürze als Signatur („Ausnahme @ Klasse.methode“). version = die App-Version, mit der der Absturz
-- zuerst gemeldet wurde; spätere Berichte (nach einem Update) ändern sie nicht mehr.
CREATE TABLE IF NOT EXISTS crashes (
  install TEXT NOT NULL,
  day TEXT NOT NULL,
  sig TEXT NOT NULL,
  count INTEGER NOT NULL DEFAULT 0,
  version TEXT NOT NULL DEFAULT '',
  PRIMARY KEY (install, day, sig)
);
CREATE INDEX IF NOT EXISTS crashes_by_day ON crashes (day);
