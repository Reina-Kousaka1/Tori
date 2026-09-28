# Tori Economy V2 — Entwurf zur Prüfung

Stand: 2026-09-28. Grundlage: GitHub `main` bei `8ed2cf7f49c057377f3a103920e5e08f7ab984ca`.
Dieses Dokument ist eine Spezifikation, keine Freigabe für Migration oder Deployment.

## Verbindliche Grenzen

- Die Wallet bleibt **global pro Discord-User**. `guild_id` ist Kontext für Aktionen und mögliche Rankings, nie Teil des Wallet-Schlüssels.
- Java/JDA bleibt Discord-Gateway. Elixir/OTP besitzt nach Cutover die Economy-Regeln und schreibt als einziger Dienst produktive Economy-Mutationen.
- PostgreSQL bleibt persistente Source of Truth. Keine neue Datenbank, kein Web-Consumer der internen API.
- Music, Moderation, Tickets, Orders, Settings und Relationship/Social bleiben außerhalb der Economy-Domain. Order-Settings sind eine separate Arbeit.
- Der dynamische Market ist Teil des langfristigen Ziels, aber **nicht** des ersten produktiven Cutovers. Während des Zwischenstands bleibt er auf reine Leseoperationen beschränkt; es wird keine temporäre Market-Bridge gebaut.
- V1–V4 der vorhandenen Flyway-Migrationen bleiben unverändert. Neue Änderungen sind additiv und werden vor Ausführung gesondert geprüft.

## Verifizierter Bestand und Parität

| Funktion | Heutiger Pfad | V2-Phase | Besondere Parität |
| --- | --- | --- | --- |
| Balance, Transfer, Owner-Grant | `GeneralBot` → `CurrencyStore` → `PostgresCurrencyStore` | 1 | Globales `economy_accounts(user_id)`; Transfer atomar; bestehende Guthaben erhalten |
| Daily | derselbe Pfad | 1 | 150 Credits und 24-Stunden-Cooldown heute; optionales Ziel-User-Argument muss bewusst behandelt werden |
| Beg, Work, Loot, Gamble, Slots | `GeneralBot`, `WorkCatalog`, `GamblingPolicy`, Store | 1 | Cooldowns, Auszahlungen und RNG komplett serverseitig in Elixir |
| Shop, Buy/Sell, Inventory, Equipment | `ShopCatalog`, beide Stores, `GeneralBot` | 1 | Ein Katalog und eine Inventory-Bilanz; dynamischer Market-Buy ist ein gesonderter Konflikt |
| Fish, Mine, Chop, Craft, Repair, Crates | `MiningFishingPolicy`, `ShopCatalog`, `PostgresCurrencyStore` | 1 | Tool-Durability, Zutaten, Drops, Credits und Cooldown in einer Transaktion |
| Account-XP, Levels, Streaks, Achievements | nicht vorhanden | 2 | Neue Mechaniken; Werte erst nach Progressionsdesign festlegen |
| Leaderboard | globales Top-10-Guthaben | 2 | Altes Ranking bleibt abfragbar; XP-/Guild-Rankings brauchen eigene Indizes und Scope-Regeln |
| Quotes, Market-Preise, Sales, Scheduler | `PostgresMarketStore` und `PostgresMarketScheduler` | 3 | Bestehende Quotes, Bestand und History erhalten; nur ein Scheduler-/Schreib-Owner |

Die aktuelle Wallet und `economy_inventory` sind user-global. `economy_market_quotes`, `economy_market_transactions` und `economy_market_inventory` speichern Guild-Kontext, ohne daraus Guild-Wallets zu machen. Die heute vorhandenen Market-Transaktionen sind kein vollständiges Ledger aller Guthabenänderungen.

## Elixir/OTP-Modulstruktur (eine Anwendung)

```text
ToriEconomy.Application
├── ToriEconomy.Repo                 # PostgreSQL-Pool, keine flüchtige Wallet-Source-of-Truth
├── ToriEconomy.Api                  # interne HTTP-Boundary, Auth, Version, Validierung
├── ToriEconomy.Telemetry            # Metriken/Tracing ohne Secrets
└── ToriEconomy.Jobs.Supervisor      # zunächst begrenzte Jobs; Market-Worker erst Phase 3

ToriEconomy.Commands                  # Dispatch nach erlaubter Operation
ToriEconomy.Idempotency               # Replay, Payload-Fingerprint, atomare Resultate
ToriEconomy.Accounts                  # globale Wallet, Ledger, Transfers
ToriEconomy.Daily                     # Claim und später Streak
ToriEconomy.Catalog                   # ein Produktkatalog, Unlock-Metadaten
ToriEconomy.Inventory                 # Bestand, Equipment, Consumables, Effects
ToriEconomy.Activities                # Work/Gather/Craft/Games, RNG-Policies
ToriEconomy.Progression               # XP, Level, Achievements (Phase 2)
ToriEconomy.Leaderboards              # definierte Scopes und stabile Sortierung (Phase 2)
ToriEconomy.Market                    # Quotes, Sales, Stock, Preise (Phase 3)
ToriEconomy.Market.Scheduler          # nur ein aktiver Writer (Phase 3)
```

Domain-Module sind überwiegend zustandslose Funktionen/Services; nicht ein GenServer pro User. Datenbanktransaktionen und Row-Locks serialisieren konkurrierende Wallet-/Inventory-Updates. Supervisoren isolieren API, DB-Verbindung und Jobs. Ein Cache hält ausschließlich neu berechenbare Leseergebnisse, niemals Wallet-/Inventory-Wahrheit. Für Jobs sind stabile Job-IDs, Transaktionen und ein einzelner aktiver Scheduler erforderlich.

## API v1: interner Vertrag

Transportvorschlag: `POST /internal/economy/v1/execute` über ein nur im Compose-Netz erreichbares HTTP-Interface. Authentifizierung per eigenem Service-Secret; kein Discord-Token im Request. Normalfall ist **ein** Request/Response-Zyklus. Java kann Discord vor dem Aufruf deferen. Keine „accepted“-Zwischenantwort für normale Commands.

Request-Beispiel (Mutation):

```json
{
  "request_id": "927dfac0-0fb1-40de-96d0-5bad7b88ce7c",
  "idempotency_key": "discord-interaction:123456789012345678",
  "operation": "daily.claim",
  "context": {
    "actor_user_id": "123456789012345678",
    "target_user_id": "123456789012345678",
    "guild_id": "234567890123456789",
    "channel_id": "345678901234567890"
  },
  "args": {}
}
```

Erfolg:

```json
{
  "request_id": "927dfac0-0fb1-40de-96d0-5bad7b88ce7c",
  "status": "ok",
  "result": {
    "type": "daily_claimed",
    "recipient_user_id": "123456789012345678",
    "credits_awarded": "150",
    "balance": "920"
  }
}
```

Domänenfehler:

```json
{
  "request_id": "927dfac0-0fb1-40de-96d0-5bad7b88ce7c",
  "status": "error",
  "error": {
    "code": "COOLDOWN_ACTIVE",
    "retryable": false,
    "details": { "retry_after_ms": 42000 }
  }
}
```

Weitere stabile Codes: `INVALID_INPUT`, `FORBIDDEN`, `INSUFFICIENT_FUNDS`, `ITEM_UNAVAILABLE`, `OUT_OF_STOCK`, `IDEMPOTENCY_CONFLICT`, `TEMPORARILY_UNAVAILABLE`, `INTERNAL`. Keine SQL-/Stacktrace-Details an Discord. `request_id` korreliert Logs; `idempotency_key` identifiziert **eine** Mutation und wird vom Discord-Interaction-ID abgeleitet. Snowflakes und Credits werden als Strings übertragen, damit kein JSON-Number-Präzisionsverlust entsteht. Zeitpunkte sind UTC/ISO-8601, Dauern explizit in Millisekunden. Read-only Calls benötigen einen Request-ID, aber keinen Mutation-Key.

| Operation v1 (Vorschlag) | Art | Kernargumente / Resultat |
| --- | --- | --- |
| `wallet.balance`, `wallet.transfer` | Read / Mutation | Ziel-User; bei Transfer Empfänger und Betrag; Resultat enthält globale Bilanz |
| `daily.claim` | Mutation | Ziel-User; Resultat enthält Claim, Betrag, Cooldown und globale Bilanz |
| `shop.page`, `shop.buy`, `shop.sell` | Read / Mutation | Kategorie/Cursor bzw. Item-ID und Menge; Preis und Version werden serverseitig geprüft |
| `inventory.list`, `equipment.equip`, `equipment.unequip` | Read / Mutation | Ziel-User bzw. Item/Slot; strukturierte Bestands-/Equipment-Daten |
| `activity.perform` | Mutation | erlaubte Aktivität (`beg`, `work`, `loot`, `gamble`, `slots`, `fish`, `mine`, `chop`, `craft`, `repair`, `opencrate`) und typisierte Argumente; kompletter Reward-/Cooldown-Status |
| `admin.grant_credits`, `admin.grant_item` | Mutation | Actor und Ziel-User, Betrag bzw. Item/Menge; Owner-Berechtigung zusätzlich im Dienst prüfen |
| `leaderboard.page`, `progression.view` | Read | erst Phase 2; expliziter Metric- und Scope-Typ, optional Guild-ID |
| `market.*` | Read/Mutation | erst Phase 3; eigener Contract-Anhang für Quotes, Purchases und Scheduler |

`operation` und jedes `args`-Schema sind allowlisted; unbekannte Felder/Versionen werden eindeutig abgelehnt. Der Elixir-Dienst verwendet seine eigene Uhr für Cooldown- und Quote-Entscheidungen, nicht ein vom Client geliefertes `now`. HTTP 200 enthält abgeschlossene Domain-Resultate einschließlich fachlicher Ablehnung; 400 signalisiert ungültigen Contract, 401/403 Auth-/Zugriffsfehler, 409 einen Key/Payload-Konflikt und 503 temporäre Unverfügbarkeit. Für jede dieser Klassen gibt es Java↔Elixir-Fixtures und eine dokumentierte Discord-Darstellung. Der Shop-Cursor ist opaque und befristet; Java errechnet keine nächste Produktmenge oder Preise selbst.

Pro Mutation gilt: gleicher Key und gleicher kanonischer Payload-Fingerprint → gespeichertes Resultat; gleicher Key und anderer Fingerprint → `IDEMPOTENCY_CONFLICT`. Ergebnis und alle Economy-Effekte werden in **derselben PostgreSQL-Transaktion** persistiert. Ein nicht commiteter Versuch hinterlässt keinen „erfolgreichen“ Replay-Eintrag. Nach Timeout wiederholt Java denselben Key und Payload; ein neuer Key wäre ein neuer Vorgang. HTTP-Timeouts und Discord-Antwort-Limits gehören in Java-Adapter-Contract-Tests. Admin-Operationen bleiben zusätzlich durch die bestehende Java-Berechtigungsprüfung geschützt und werden im API-Dienst auf eine erlaubte Operation/Actor-Kombination begrenzt.

Java rendert lokalisierte Discord-Ausgaben aus strukturierten Resultaten und hält keine Preise, Chancen, Cooldowns oder XP-Regeln. Für paginierten Shop tragen Buttons nur opaque Cursor/Seitenangaben und eine begrenzte Ablaufzeit; jeder Klick erzeugt einen neuen autorisierten Read-Request. Für Purchases wird die vom Dienst gelieferte Produkt-/Preisversion verwendet, nicht ein Preis aus dem Button.

## Additiver PostgreSQL-V2-Schemaplan

**Migrationsautorität:** Solange Tori und Economy dieselbe DB nutzen, bleibt Flyway die einzige ausführende Schema-Migration-Pipeline. Elixir/Ecto kann die Tabellen abbilden, führt aber nicht parallel eigene Auto-Migrationen auf diesen Tabellen aus. Jede neue Migration folgt auf V4, ist append-only und erhält einen geprüften Down-/Restore-Plan; bestehende V1–V4-Dateien werden nicht editiert.

| Neue Struktur (Arbeitsname) | Schlüssel / Zweck | Phase |
| --- | --- | --- |
| `economy_v2_requests` | `idempotency_key` PK; Payload-Hash, Operation, Actor/Guild, Resultat JSONB, committed_at. Mutation-Replay. | 1 |
| `economy_v2_ledger_entries` | `entry_id` PK, `user_id` FK zu globalem Account, `request_key`, `leg`, `delta`, `balance_after`, `reason_code`, optional `guild_id`/Gegenpartei, `occurred_at`; UNIQUE `(request_key, leg)`. | 1 |
| `economy_v2_catalog_items` | stabile `item_id` PK, Kategorie, Basis-/Sell-Preis, Anzeige-/Unlock-Metadaten, Aktiv-Flag; importierte IDs bleiben erhalten. | 1 |
| `economy_v2_inventory_events` | `event_id` PK, User, Item, Delta, Bestand danach, Request-Key, Grund und Zeit. Bestand bleibt zunächst in `economy_inventory`. | 1 |
| `economy_v2_activity_state` und `economy_v2_activity_events` | `(user_id, activity)` für Cooldown-Zustand; Ereignisse für nachvollziehbare Rewards/Drops. Alte `last_*_at`-Werte werden vor Aktivierung übernommen. | 1 |
| `economy_v2_daily_streaks` | `user_id` PK, Claim-/Streak-Zustand. Streak-Regel getrennt von der bisherigen 24-h-Daily-Parität versionieren. | 2 |
| `economy_v2_xp_events`, `economy_v2_progress` | XP-Ereignisse eindeutig pro Auslöser; globaler Account-Scope und separate Aktivitäts-/ggf. Guild-Scopes. | 2 |
| `economy_v2_achievement_defs`, `economy_v2_achievement_unlocks` | Versionierte Definitionen und eindeutige Unlocks pro User/Scope/Achievement/Tier. | 2 |
| Ranking-Indizes/Projektionen | Explizite Top-N-/Cursor-Abfragen mit stabiler Tie-Break-Sortierung. Guild-Ranking hat `guild_id`, Wallet nicht. | 2 |
| Market-Job-/Idempotenz-Metadaten | Nur wenn nach Analyse der bestehenden Market-Tabellen nötig; bestehende Quotes/History/Stock bleiben erhalten. | 3 |

Wallet-Balance bleibt für Phase 1 in `economy_accounts.balance`, Inventory-Mengen in `economy_inventory`: keine zweite unkontrollierte Bilanz. Elixir schreibt Bilanzänderung, Ledger-Zeile und gegebenenfalls Inventory-Ereignisse atomar. Ein Transfer erzeugt zwei Ledger-Legs in einer Transaktion; globale Summe der Legs ist null. Ein opening-balance-Eintrag pro vorhandenem User dokumentiert den importierten Kontostand als **Snapshot**, nicht als erfundene Transaktionshistorie. Backfill muss wiederholbar sein; vor/nachher werden User-Anzahl, Gesamtsaldo, Einzelkonten, Item-Mengen und Stichproben per Checksummen verglichen. Die Snapshot-Zeit und Herkunft werden protokolliert.

Constraints vor Ausführung konkretisieren: Snowflake-Format wie bisher, `BIGINT`-Überlauf/Nonnegative-Balance, FK/ON DELETE RESTRICT, eindeutige Request-Keys, begrenzte JSONB-Größe, UTC-`TIMESTAMPTZ`, Indizes auf Ledger `(user_id, occurred_at, entry_id)` und Ranking `(metric DESC, user_id)`. Keine destructive Änderung bestehender Tabellen oder Werte.

## Market-Cutover-Abhängigkeit (Freigabepunkt)

Heute teilen Market-Buy und Java-Economy `economy_accounts.balance` und `economy_inventory`. Werden Wallet/Inventory nach Elixir umgeschaltet, darf Java-Market nicht daneben weiter in dieselben Tabellen schreiben: Das ergäbe zwei Business-Writer und Ledger-Lücken.

**Festgelegte erste Cutover-Variante:** Dynamischer Market bietet vor Phase 3 höchstens unverändernde Produktansicht und Price-History. Das heutige Quote-Verfahren schreibt Daten und gehört daher **nicht** zu diesem Read-only-Modus. Market-Kauf und Market-Admin-Writes sind während des Zwischenstands klar als vorübergehend nicht verfügbar gekennzeichnet. Der neue gemeinsame V2-Shop verarbeitet nur Produkte, deren Purchase-Policy vollständig in Elixir liegt. Ein Wechsel vom dynamischen Market-Preis zu einem V2-Festpreis wäre eine sichtbare fachliche Änderung und darf nicht stillschweigend passieren. Eine temporäre Market-Bridge wird nicht gebaut. Der Read-only-Modus wird erst beim kontrollierten Cutover aktiviert; diese Implementierungsphase verändert das produktive Verhalten nicht.

## Umsetzungsgates und Tests

1. Vor Implementierung: stabile Git-Referenz, verifizierter PostgreSQL-Backup **mit Test-Restore**, genaue Produktions-Schema-/Dateninventur. Kein Tag oder Backup ist durch dieses Dokument angelegt worden.
2. API-v1-Contract-Tests: Java- und Elixir-Fixtures für jede Operation, Versionierungs-/Validierungsfehler, Snowflake- und BIGINT-Grenzen, Timeout/Retry, Secret-Redaction.
3. Ecto-/DB-Tests: parallele Daily-/Buy-/Transfer-Aktionen, Idempotenz unter Race, Transaktions-Rollback, Ledger↔Balance- und Inventory↔Event-Abgleich, Neustart.
4. Parität: bestehende Commands, Rewards, Cooldowns, Tool-Verhalten und globales Wallet anhand anonymisierter Golden Cases vergleichen; absichtliche Abweichungen separat freigeben.
5. Shadow-Reads gegen isolierte Kopie, dann begrenzter Schreib-Cutover erst nach Market-Freigabepunkt. Nie zwei aktive Writer für dieselbe Economy-Aktion.
6. Progression und Leaderboards nach stabilem Kern; Market mit Quotes/Stock/Sales/Scheduler zuletzt. Alte Java-Economy-Dateien erst nach nachgewiesener Parität entfernen.

**Rollback-Grenze:** Vor dem ersten V2-Write ist Rückkehr zur Java-Economy relativ einfach. Danach genügt ein Code-Rollback nicht: neue Ledger-/Inventory-/XP-Ereignisse müssen konsistent zurückgespielt oder ein explizit getesteter Reverse-Pfad bereitstehen. Daher Cutover und Backout als eigenes Runbook freigeben.

## Noch offene fachliche Freigaben

- Market-Übergang: read-only ohne Bridge ist entschieden. Vor Cutover muss die sichtbare `/buy`-/Quote-Behandlung als Discord-Verhalten getestet werden.
- Beibehaltung des heutigen `/daily`-Parameters, mit dem ein Nutzer für einen anderen claimen kann, oder bewusstes Breaking Change?
- Streak-Kalender/Zeitzone, XP-Quellen und -Kurven, Level-Unlocks, Achievement-Definitionen und Ranking-Scopes erst nach Progressionsdesign festlegen.
- Exaktes Ayana-Repository/Archiv und Lizenz, falls Ayana als konkrete OTP-Inspiration geprüft werden soll; derzeit kein verifizierter öffentlicher Elixir-Source identifiziert.
