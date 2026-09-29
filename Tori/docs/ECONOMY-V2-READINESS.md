# Economy V2 readiness — 2026-09-28

This is a code and schema audit, not a production-data count. The Java bot still
routes all economy commands to `PostgresCurrencyStore` and `PostgresMarketStore`.
`EconomyV2Client` is not wired into `Main`; the Elixir API is disabled by default.
The wallet key is **Discord user ID only**, never guild ID. IDs are PostgreSQL
`TEXT` and are carried through the draft V2 schema without numeric conversion.

## Writer parity

`A` = `economy_accounts`, `I` = `economy_inventory`, `W` = `economy_tool_wear`,
`E` = `economy_equipment`, `M` = `economy_market_*`. All are V2 legacy sources.
"Target" denotes the intended owner after a separately approved cutover.
The current V2 implementation only supports balance, daily, and transfer.

| Legacy path / indirect writer | Current state | Intended target | V2 support | Backfill | Parallel writer risk |
| --- | --- | --- | --- | --- | --- |
| `/balance`, `/leaderboard` | A reads; `balance()` creates a missing zero account | Global V2 wallet | Balance read only | A opening ledger | Yes if V2 daily/transfer active |
| `/daily` | A.balance + A.last_daily_at | V2 daily + cooldown | Implemented, Java not routed | Balance + daily timestamp | Yes |
| `/transfer` | Both A balances in one transaction | V2 transfer + two ledger legs | Implemented, Java not routed | Balances | Yes |
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

The additive draft V5 stays in `economy-v2/sql/draft`, outside Java Flyway.
`backfill_v1_to_v2.sql` copies opening balances, inventory quantities,
daily/beg/work/fish/mine/chop timestamps, equipment, and tool wear. Unique
opening indexes and `ON CONFLICT DO NOTHING` prevent duplication. Existing
target values are never overwritten. Source rows are locked and source/target
counts, sums, and exact values are checked in one transaction; any mismatch
rolls it back. Preserve the legacy tables until every unsupported operation
has a verified V2 replacement and a separately approved cutover plan.

On an isolated `tori_test` database, first apply V1–V4 and draft V5, then:

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

## Cowlib

`plug_cowboy 2.9.0` → `cowboy 2.19.0` → `cowlib 2.20.0` (mix.lock).
`mix deps.get` reports CVE-2026-43966 (medium, structured response header
splitting) and CVE-2026-43969 (low, client-side cookie header injection).
As of this audit Hex lists 2.20.0 as the latest release and still flags both.
There is no compatible **released, advisory-free** Cowlib version to select.
The API emits JSON and does not construct untrusted structured headers or
client Cookie headers; this lowers direct exposure but is not a dependency
fix. Cowboy 2.19.0 also validates outgoing CR/LF headers by default.
Keep `TORI_ECONOMY_API_ENABLED=false` outside isolated testing and keep the
bind address local. Recheck upstream advisories before any network exposure.

Sources: https://hex.pm/packages/cowlib/advisories ,
https://cna.erlef.org/cves/CVE-2026-43966.html ,
https://cna.erlef.org/cves/CVE-2026-43969.html .

## Verification record

These results use disposable local PostgreSQL 17 containers, not production.
The Java live suite ran against a fresh `tori_test` database with Flyway V1–V4.
The backfill fixture used a separate `tori_test` database with draft V5 applied
manually. The restore target was a third, empty `tori_restore_test` database.

| Gate | Result | Evidence / limit |
| --- | --- | --- |
| Java build | PASS | `gradlew clean test build`; 240 tests, 0 failures, 0 skipped with live opt-in. |
| Elixir build | PASS | `mix compile` in Elixir 1.18 container. |
| API validation | PASS | Contract and Plug HTTP tests for malformed input, numeric Snowflakes and authorization. |
| Idempotency | PASS | Replayed daily request and eight parallel identical requests produced one ledger leg. |
| Concurrency | PASS | Parallel insufficient-funds and opposing-transfer tests; balances and ledger checked. |
| Parity audit | PASS | Java command/store/helper and V1–V4 schema mapping above; V2 feature parity itself remains incomplete. |
| Backfill | PASS (fixture) | 2 wallets, 4,921 credits, 3 inventory rows, 7 items, 1 tool-wear and 1 equipment row; exact cooldown/ID checks. |
| Backfill replay | PASS (fixture) | Second run inserted zero rows; corrupt opening balance caused expected verification failure. |
| Backup | PASS (fixture) | `pg_dump -Fc` completed on the isolated Java test DB. Production backup: BLOCKED (no approved production DB access used). |
| Restore verification | PASS (fixture) | `pg_restore --exit-on-error` into a separate DB; schema/Flyway hash, wallets, inventory, tickets, orders and moderation-case aggregates matched. Production restore: BLOCKED. |
| Market READ_ONLY gate | PASS (isolated live test) | Browsing returned current price while direct mutators rejected; scheduler is not started in READ_ONLY. |
| Cowlib security | BLOCKED | Latest published Cowlib 2.20.0 still reports CVE-2026-43966/43969; no safe release verified. |
| DB integrity | PASS (fixture) | Backfill exact comparison and restore aggregate comparison. Production DB integrity: NOT RUN. |
| Replay after BEAM restart | NOT RUN | Same-process replay and Java restart persistence were tested; explicit BEAM restart replay remains to test. |
| Backup script end-to-end | NOT RUN | Script parsed successfully; host `psql`/`pg_dump`/`pg_restore` were unavailable. Equivalent commands ran inside PostgreSQL container. |

No production write cutover is authorized. `TORI_ECONOMY_API_ENABLED` must remain
false outside the isolated suite. Cowlib advisories and missing V2 parity remain
release blockers even though the build/test gates above pass.
