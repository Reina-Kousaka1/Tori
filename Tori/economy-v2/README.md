# Tori Economy V2 — erster Implementierungsstand

Die Balance-Abfrage kann optional über Java → Elixir → PostgreSQL laufen. Der
Standard bleibt `TORI_ECONOMY_BALANCE_SOURCE=LEGACY`. Bei `ELIXIR` liest nur
`/balance` über den internen API-Client; mutierende Economy-Kommandos verbleiben
bei Java. Elixir lehnt Writes standardmäßig ab.

Enthalten sind API-v1-Validierung, globale Wallet-Abfrage, Daily und Transfer,
ein transaktionales Request-Replay mit Ledger-Einträgen und ein Java-Client.
Shop, Inventory-Mutationen, Activities und vollständige Feature-Parität sind
noch nicht implementiert.

## Entwicklung

- Java: im Verzeichnis `Tori` `./gradlew clean test build --no-daemon`
- Elixir: im Verzeichnis `economy-v2` `mix deps.get && mix test`
- Der Dienst startet standardmäßig ohne Repo/API-Child. Erst
  `TORI_ECONOMY_API_ENABLED=true` aktiviert ihn.
- Erforderlich bei aktivem Dienst:
  `TORI_ECONOMY_DATABASE_URL` und `TORI_ECONOMY_API_SECRET` (mindestens 32
  Bytes). Standard-Bind-Adresse ist `127.0.0.1:4001`.
- Java-Routing: `TORI_ECONOMY_BALANCE_SOURCE=ELIXIR`,
  `TORI_ECONOMY_URL=http://127.0.0.1:4001` und dasselbe
  `TORI_ECONOMY_API_SECRET`. URL muss lokales HTTP sein; Verbindungstimeout
  2s, Anfrage-Timeout 3s. Der Elixir-Dienst muss dieselbe persistente Tori-
  PostgreSQL-Datenbank nutzen. Keine getrennte Runtime-Datenbank, keine Kopie.
- `TORI_ECONOMY_WRITE_ENABLED` ist standardmäßig `false`. Aktivierung erfordert
  eine nachgewiesene Writer-Übergabe für genau den betroffenen State-Bereich.
- `sql/draft/V5__economy_v2_core.sql` ist bewusst **nicht** im Flyway-Pfad.
  Es wurde keine Migration ausgeführt. Bestehende V1–V4-Dateien sind unverändert.
- `TORI_MARKET_MODE=LEGACY` (Standard) erhaelt den Java-Market. Mit
  `TORI_MARKET_MODE=READ_ONLY` bleiben Produkt-/Preis-/History-Ansichten
  verfuegbar, aber Quote-Writes, Kaeufe, Admin-Aenderungen und Scheduler-Writes
  sind gesperrt. `V2` wird beim Start abgelehnt, solange kein V2-Market existiert.
- Paritaet, Backfill- und Restore-Anleitung: `../docs/ECONOMY-V2-READINESS.md`.

## Vor produktiven Writes

Stabilen Git-Stand markieren, PostgreSQL-Backup und Restore nachweislich testen,
die additive Migration gegen eine isolierte Testdatenbank prüfen, Bestände und
Counts vor/nach Backfill vergleichen und Operationen einzeln umschalten. Sobald
Elixir Wallet/Inventory schreibt, müssen alle schreibenden Java-Pfade für
dieselben Datenbereiche übergeben und gesperrt sein. Bis dahin bleibt der
Elixir-Write-Schalter deaktiviert.

Der HTTP-Stack ist Bandit 1.12.5. Cowboy und Cowlib wurden aus dem Dependency-
Graph entfernt. `mix hex.audit` meldet keine Advisory-Pakete.
