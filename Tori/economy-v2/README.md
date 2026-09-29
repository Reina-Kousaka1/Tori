# Tori Economy V2 — erster Implementierungsstand

Dies ist ein **inaktives Fundament**, kein produktiver Cutover. Der Java-Bot nutzt
`EconomyV2Client` noch nicht. Die Java-Economy und der Market bleiben im
laufenden Bot unverändert. Daher existieren aktuell keine zwei aktiven Writer.

Enthalten sind API-v1-Validierung, globale Wallet-Abfrage, Daily und Transfer,
ein transaktionales Request-Replay mit Ledger-Einträgen sowie ein Java-Client
mit Contract-Test. Shop, Inventory-Mutationen, Activities, Backfill, Shadow-Reads
und Command-Routing sind noch nicht implementiert.

## Entwicklung

- Java: im Verzeichnis `Tori` `./gradlew clean test build --no-daemon`
- Elixir: im Verzeichnis `economy-v2` `mix deps.get && mix test`
- Der Dienst startet standardmäßig ohne Repo/API-Child. Erst
  `TORI_ECONOMY_API_ENABLED=true` aktiviert ihn. Das ist **nur** für eine
  isolierte Entwicklungsdatenbank vorgesehen; niemals direkt gegen eine
  produktive Tori-Datenbank setzen.
- Erforderlich bei aktivem Dienst:
  `TORI_ECONOMY_DATABASE_URL` und `TORI_ECONOMY_API_SECRET` (mindestens 32
  Bytes). Standard-Bind-Adresse ist `127.0.0.1:4001`.
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
Elixir Wallet/Inventory schreibt, muss der schreibende Java-Market gesperrt
und auf read-only reduziert sein. Bis dahin ist die V2-API auszuschalten.

Der HTTP-Stack zieht derzeit `cowlib` mit gemeldeten Sicherheitsadvisories.
Vor einer Freigabe auf einem Netzwerk-Interface ist eine aktualisierte
Abhängigkeitsprüfung nötig; `TORI_ECONOMY_BIND` sollte bis dahin lokal bleiben.
