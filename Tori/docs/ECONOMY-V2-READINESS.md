# Economy V2 readiness — 2026-09-29

## Raspberry Pi test report and current deployment work

The user reported a successful `mix test` on the Raspberry Pi against only the
isolated `tori_economy_test` database on port 5433. The report says migrations
V1–V4 plus the then-draft V5 were applied, with 21 tests, 0 failures, and no
database-related skips. Production PostgreSQL was not contacted. This verifies
the implementation that was on the Pi at that time; it does not verify the
subsequent Docker release, regular Flyway promotion, or production write-gate
changes recorded in the current working tree.

The current uncommitted deployment work adds a Docker Mix release, an internal
Compose service, a health check, discrete environment-based DB credentials,
and an additive V5 migration under Java's normal Flyway directory. It also
adds a production gate that checks the expected connected database, Flyway V5,
an explicit operator acknowledgement, and a per-operation allowlist. These
changes have not been executed through Mix or Docker in this workstation and
must be treated as unverified until those checks run. No production migration
or production write was run.

`/daily` and the existing `/transfer` command have opt-in Java routing via
`TORI_ECONOMY_ROUTING=daily=ELIXIR,transfer=ELIXIR`; omitted areas remain
`LEGACY`. The test write gate verifies the connected database ends in `_test`.
The default production configuration remains `LEGACY` plus writes disabled.

The mutation path reuses `economy_accounts` for global wallets,
`economy_v2_requests` for interaction-keyed results, and
`economy_v2_ledger_entries` for `DAILY_CLAIM`, `TRANSFER_OUT`, and
`TRANSFER_IN`. Balance updates, ledger legs, and replay results share a DB
transaction. Transfers lock both account rows in stable sorted order. V5 is
now the additive regular Flyway migration
`src/main/resources/db/migration/V5__economy_v2_core.sql`; it has not been
applied to production.

Verification status for the reported Pi baseline and this workstation's newer
deployment changes:

| Gate | Result | Evidence / limit |
| --- | --- | --- |
| Java clean test/build | NOT RUN (current revision) | Earlier Phase 2/3 baseline passed 250 tests, 0 failures/errors, 6 optional live tests skipped. This workstation's rerun was blocked by `AccessDeniedException` on a Gradle dependency JAR, so the deployment changes are not covered. |
| Elixir build and suite (Pi baseline) | PASS (reported) | `mix test`, 21 tests, 0 failures, no DB-related skips on the isolated database. New deployment/gate revision: NOT RUN. |
| Elixir/PostgreSQL integration (Pi baseline) | PASS (reported) | Only `tori_economy_test` on port 5433 with V1–V4 and draft V5. New revision: NOT RUN. |
| Docker build / Compose validation | NOT RUN | Docker CLI unavailable on this workstation; Raspberry Pi build still required. |
| Current Flyway V5 validation | NOT RUN | V5 is now in the normal Flyway path; the newer migration file has not yet been run against a database in this turn. |
| Daily replay after Repo restart | PASS | Included in the reported non-skipped PostgreSQL integration suite. |
| Full Elixir/BEAM service restart replay | NOT RUN | The checked-in test restarts the Repo process; it does not restart the full service runtime. |
| Parallel/race tests | PASS | Included in the reported non-skipped PostgreSQL integration suite. |
| Mid-transaction rollback | PASS | Included in the reported non-skipped PostgreSQL integration suite. |
| Java → Elixir → PostgreSQL live mutation test | NOT RUN | Separate opt-in Gradle live test; it was among the six skipped in the local Java run and was not included in the Pi `mix test` report. |
| Production database contacted | NO | The Pi report confirms the isolated test database was used exclusively. |
| Production Elixir writes enabled | NO | Java production routes remain `LEGACY`; Elixir writes remain gated to an explicitly enabled `*_test` DB. |

The Pi baseline results were reported by the user; they were not rerun from
this workstation. They clear the earlier Elixir/PostgreSQL test blocker for
that baseline only. The distinct Java live mutation test remains unverified.
All production writes remain blocked until the database backup/restore checks
pass and Java writer ownership is exclusive for the migrated data area.

This is a code and schema audit, not a production-data count. Java retains
`PostgresCurrencyStore` and `PostgresMarketStore` for legacy-owned areas;
balance, inventory, shop, leaderboard, daily, and transfer can each be routed
through `EconomyV2Client`. Every area defaults to LEGACY. Elixir's mutation API
is read-only by default. Test writes require the isolated test gate; production
writes additionally require explicit cutover acknowledgement and runtime
schema/database checks. Those checks do not replace verified backups or prove
there is no other Java writer. Runtime must use the same Tori PostgreSQL database and existing
`economy_accounts` tables; disposable databases in this document are test-only.
The wallet key is **Discord user ID only**, never guild ID. IDs are PostgreSQL
`TEXT` and are carried through the V2 schema without numeric conversion.

## Writer parity

`A` = `economy_accounts`, `I` = `economy_inventory`, `W` = `economy_tool_wear`,
`E` = `economy_equipment`, `M` = `economy_market_*`. All are V2 legacy sources.
"Target" denotes the intended owner after a separately approved cutover.
The current V2 implementation only supports balance, daily, and transfer.

| Legacy path / indirect writer | Current state | Intended target | V2 support | Backfill | Parallel writer risk |
| --- | --- | --- | --- | --- | --- |
| `/balance`, `/leaderboard` | A reads; `balance()` creates a missing zero account | Global V2 wallet | Balance read only | A opening ledger | Yes if V2 daily/transfer active |
| `/daily` | A.balance + A.last_daily_at | V2 daily + cooldown | Implemented, opt-in Java route; default LEGACY | Balance + daily timestamp | Yes |
| `/transfer` | Both A balances in one transaction | V2 transfer + two ledger legs | Implemented, opt-in Java route; default LEGACY | Balances | Yes |
| `/beg`, `/work` | A.balance + A.last_beg_at/last_work_at | V2 activities | No | Balances + timestamps | Yes |
| `/loot`, `/grantcredits` | `changeBalance` → A.balance | V2 awards/admin grants | No | Balances | Yes |
| `/gamble`, `/slots` | `settleWager` → A.balance | V2 wagers | No | Balances | Yes |
| `/shop`, `/iteminfo` | M product price evolution, quote insert/update; sale expiry on list | V2 catalog/quotes | No | Existing M retained, not imported | Market writer remains in LEGACY |
| `/buy` | `PostgresMarketStore.buy` → A.balance, I.quantity, M stock/transaction/market inventory/quote deletion atomically; fallback `PostgresCurrencyStore.buy` → A + I | V2 purchase | No | A/I snapshot; M history retained | **High** |
| `/sell` | `PostgresCurrencyStore.sell` → I then A | V2 sale | No | A/I snapshot | **High** |
| `/inventory` | I read; market ownership history separately in M | V2 inventory | No | I opening events | Yes if V2 inventory enabled |
| `/grantitem` | `grantItem` → I | V2 admin grant | No | I opening events | Yes |
| `/equip`, `/unequip`, `/tools` | E and W; tools read may create zero account | V2 equipment/tool wear | No | E/W snapshot | Yes |
| `/fish`, `/mine`, `/chop` | `gather` → A.balance/cooldown, I reward, W durability, E on break | V2 gathering | No | All four tables | **High** |
| `/craft` | I ingredient removal and output addition | V2 crafting | No | I opening events | Yes |
| `/repair` | A debit and W reset | V2 repair | No | A/W snapshot | Yes |
| `/opencrate` | I crate/key removal and reward, A credit | V2 crate opening | No | A/I snapshot | Yes |
| `/market add`, `/market stock` | M product insert/stock update | V2 market admin | No | M retained | Market gate required |
| Market scheduler | M catalog seed, price/history/trim, sale creation/expiry | Future V2 market | No | M retained | Market gate required |
| Ticket/order flows | `guild_*` tables; payment metadata only, no A/I mutation in V4 | Remain Java | N/A | Restore check only | No wallet conflict |

The audit searched Java SQL references and all callers of currency/market methods.
`PostgresCurrencyStore.removeItem`, `addItem`, `credit`, and `awardOnCooldown`
are indirect mutation helpers shared by several commands. `PostgresMarketStore`
has write-on-read behavior, so READ_ONLY disables price evolution, quote writes,
sale expiry, and its scheduler as well as explicit market mutations. READ_ONLY
does **not** make the rest of the Java economy read-only; it is only the first
cutover gate. Do not enable V2 writes while Java still writes the same A/I rows.

## Draft backfill, isolated test only

V5 is now `src/main/resources/db/migration/V5__economy_v2_core.sql` and is
applied by Java Flyway at startup. `backfill_v1_to_v2.sql` remains an isolated
test-only draft and copies opening balances, inventory quantities,
daily/beg/work/fish/mine/chop timestamps, equipment, and tool wear. Unique
opening indexes and `ON CONFLICT DO NOTHING` prevent duplication. Existing
target values are never overwritten. Source rows are locked and source/target
counts, sums, and exact values are checked in one transaction; any mismatch
rolls it back. Preserve the legacy tables until every unsupported operation
has a verified V2 replacement and a separately approved cutover plan.

On an isolated `tori_test` database, first apply V1–V5 through Flyway, then:

```powershell
psql -X -v ON_ERROR_STOP=1 -d tori_test -f economy-v2/sql/draft/backfill_v1_to_v2.sql
psql -X -v ON_ERROR_STOP=1 -d tori_test -f economy-v2/sql/draft/backfill_v1_to_v2.sql
```

Record source and target sample balances, inventories and cooldowns before
and after. An already partially populated target is allowed only if its values
match exactly. Running this against an active production writer is unsafe.

## Backup and real restore

The safe sequence is source database → custom-format `pg_dump` → separately
created empty `*_restore_test` database → `pg_restore` → source/target
`restore_integrity.sql` comparison. The PowerShell
`economy-v2/sql/draft/backup-restore-verify.ps1` enforces distinct database
names, an empty test target, no backup overwrite, and no passwords in DSNs.
Supply credentials with `PGPASSFILE` or an interactive prompt, never a
committed command line. Example (replace hosts and DB names with approved
values, and keep backup outside the repository):

```powershell
$env:TORI_BACKUP_SOURCE_DSN = 'postgresql://localhost:5432/tori_source'
$env:TORI_RESTORE_TEST_DSN = 'postgresql://localhost:5432/tori_restore_test'
powershell.exe -ExecutionPolicy Bypass -File .\economy-v2\sql\draft\backup-restore-verify.ps1 -BackupFile 'C:\backups\tori-check.dump'
```

The script **does not create** the target database. The operator must create
an empty, separate target and check its name first. A successful isolated
fixture restore is not evidence that a production backup has been tested.

## HTTP dependency security

The API now uses Bandit 1.12.5 and removes Plug.Cowboy, Cowboy and Cowlib from
the lockfile. Hex's current advisory page marks Bandit versions below 1.12.5
affected by two August 2026 advisories; 1.12.5 is the patched boundary.
`mix hex.audit` reports no advisory or retired packages for the resolved graph.
The API remains disabled by default. In the Pi Compose deployment it binds
inside the private Compose network and has no host-published port.

Sources: https://hex.pm/packages/bandit/advisories ,
https://bandit.hexdocs.pm/Bandit.html .

## Previous phase verification record

The following results are from the earlier Phase 1/backfill audit. They are
historical and do not replace the Phase 2/3 results at the top of this file.
Those fixture runs used disposable local PostgreSQL 17 containers, not production.
The Java live suite ran against a fresh `tori_test` database with Flyway V1–V4.
The earlier backfill fixture used a separate `tori_test` database with V5
applied manually before it was promoted to Flyway. The restore target was a
third, empty `tori_restore_test` database.

| Gate | Result | Evidence / limit |
| --- | --- | --- |
| Java build | PASS | Earlier run: `gradlew clean test build`; 246 tests, 0 failures, 0 errors, 6 optional live tests skipped. Current Phase 2/3 run: 250 tests, 0 failures/errors, 6 optional live tests skipped. |
| Elixir build | PASS | `mix compile` in Elixir 1.18 container using Bandit 1.12.5. |
| API validation | PASS | 13 ExUnit tests, 0 failures; includes malformed input, auth and read-only gate. |
| Idempotency | PASS | Replayed daily request and eight parallel identical requests produced one ledger leg. |
| Concurrency | PASS | Parallel insufficient-funds and opposing-transfer tests; balances and ledger checked. |
| Parity audit | PASS | Java command/store/helper and V1–V4 schema mapping above; V2 feature parity itself remains incomplete. |
| Backfill | PASS (fixture) | 2 wallets, 4,921 credits, 3 inventory rows, 7 items, 1 tool-wear and 1 equipment row; exact cooldown/ID checks. |
| Backfill replay | PASS (fixture) | Second run inserted zero rows; corrupt opening balance caused expected verification failure. |
| Backup | PASS (fixture) | `pg_dump -Fc` completed on the isolated Java test DB. Production backup: BLOCKED (no approved production DB access used). |
| Restore verification | PASS (fixture) | `pg_restore --exit-on-error` into a separate DB; schema/Flyway hash, wallets, inventory, tickets, orders and moderation-case aggregates matched. Production restore: BLOCKED. |
| Market READ_ONLY gate | PASS (isolated live test) | Browsing returned current price while direct mutators rejected; scheduler is not started in READ_ONLY. |
| HTTP dependency security | PASS | Cowlib/Cowboy removed; `mix hex.audit` reports no advisory or retired packages. Bandit pinned via lock to patched 1.12.5. |
| Java → Elixir balance | PASS (isolated integration) | Real Java HTTP client read the known 4,821 balance through Bandit and existing `economy_accounts` table after service start and restart. Production database was not contacted. |
| Elixir mutation gate | PASS | Plug-level test confirms valid mutation returns HTTP 403 `READ_ONLY` by default. |
| Java client timeout/unavailable/schema | PASS | Unit tests cover timeout, unavailable server and malformed response; unrelated ping remains functional. |
| Shared runtime database | NOT RUN | Actual Tori DB credentials/availability were not provided or used in this turn; same database URL is required by runtime config/docs. |
| DB integrity | PASS (fixture) | Backfill exact comparison and restore aggregate comparison. Production DB integrity: NOT RUN. |
| Replay after BEAM restart | NOT RUN | Same-process replay and Java restart persistence were tested; explicit BEAM restart replay remains to test. |
| Backup script end-to-end | NOT RUN | Script parsed successfully; host `psql`/`pg_dump`/`pg_restore` were unavailable. Equivalent commands ran inside PostgreSQL container. |

No production write cutover is authorized by these test results. Keep
`TORI_ECONOMY_WRITE_ENABLED=false` for production until the documented backup
and restore, schema/data, runtime connection, and writer-ownership checks have
been completed by the operator. The Pi test database result does not verify
the production database backup/restore or production runtime connection.
