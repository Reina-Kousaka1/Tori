# Elixir Economy API on the Tori Raspberry Pi

The Compose stack runs one Java bot, the internal Elixir API, Lavalink, and
the existing PostgreSQL service. Elixir connects to the same `tori_main`
database as Java. It adds no database container, data volume, or host-published
API port. Compose restarts the API unless it was explicitly stopped; its health
check verifies PostgreSQL through the API's internal health route.

The API is initially read-only and Java routing remains `LEGACY`, including
when an older `.env` contains the retired balance-only Elixir switch. The
commands below are operator instructions; do not run them against production
until the backup and restore comparisons pass.

## Prepare secrets and build

Run these commands from the Tori repository directory on the Pi:

```sh
if [ ! -f .env ]; then cp .env.example .env; chmod 600 .env; fi
openssl rand -hex 32
nano .env
```

This preserves an existing `.env`; do not replace it with the example file.
If you already have one, add any missing Economy API variables shown below.
Put real Discord and Lavalink credentials in `.env`. Set
`TORI_POSTGRES_PASSWORD` and `LAVALINK_PASSWORD` to strong random values and
set `TORI_ECONOMY_API_SECRET` to a separate random value from `openssl rand`.
Do not paste secrets into commands, commit `.env`, or reuse the Discord token
as an API secret. Keep these initial values in `.env`:

```dotenv
TORI_ECONOMY_ROUTING=balance=LEGACY,inventory=LEGACY,shop=LEGACY,leaderboard=LEGACY,daily=LEGACY,transfer=LEGACY
TORI_ECONOMY_WRITE_ENABLED=false
TORI_ECONOMY_WRITE_MODE=disabled
TORI_ECONOMY_PRODUCTION_DATABASE_NAME=
TORI_ECONOMY_PRODUCTION_CUTOVER_ACK=
TORI_ECONOMY_PRODUCTION_WRITE_OPERATIONS=
```

Validate Compose expansion, then build the Java and Elixir images. The selected
Elixir image tag is multi-architecture and includes Linux ARM64 support.

```sh
docker compose config --quiet
docker compose build economy-api bot
docker compose ps
```

Do not start a newly built bot against production until its pre-migration
backup and restore check below is complete. The existing bot may remain up
while the images are built.

## Back up and verify a restore before V5

This procedure restores into a separately named temporary database on the
existing PostgreSQL server. `createdb` fails if that database name already
exists; never substitute `tori_main` for the restore database.

```sh
set -euo pipefail
backup_dir="$HOME/tori-backups"
mkdir -p "$backup_dir"
chmod 700 "$backup_dir"
backup="$backup_dir/tori-main-pre-v5-$(date -u +%Y%m%dT%H%M%SZ).dump"
docker compose exec -T postgres pg_dump -U tori -d tori_main -Fc > "$backup"
test -s "$backup"
sha256sum "$backup" > "$backup.sha256"

restore_db="tori_restore_check_$(date -u +%Y%m%d%H%M%S)"
docker compose exec -T postgres createdb -U tori "$restore_db"
docker compose exec -T postgres pg_restore --exit-on-error --no-owner -U tori -d "$restore_db" < "$backup"
docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -U tori -d tori_main -At < economy-v2/sql/draft/restore_integrity.sql > "$backup_dir/source-v4.json"
docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -U tori -d "$restore_db" -At < economy-v2/sql/draft/restore_integrity.sql > "$backup_dir/restore-v4.json"
cmp "$backup_dir/source-v4.json" "$backup_dir/restore-v4.json"
```

Keep the dump and checksum outside the repository. If `cmp` fails, restore
fails, or the source/restore schema and Flyway state do not match, stop here.
Do not remove the failed restore database until its failure has been examined.
After a verified restore, remove only the temporary database created above:

```sh
docker compose exec -T postgres dropdb --if-exists --force -U tori "$restore_db"
```

## Apply additive V5 through Flyway

The reviewed, additive migration is now
`src/main/resources/db/migration/V5__economy_v2_core.sql`. It creates the V2
request, ledger, catalog, inventory-event, equipment, tool-wear, and activity
tables and does not delete, reset, or rewrite V1–V4 data. The old draft copy
was promoted; do not apply an extra SQL copy manually. Java's existing Flyway
startup applies V5 when the rebuilt bot starts.

Before that start, verify that `.env` still has all writes disabled and that
`TORI_ECONOMY_ROUTING` contains only `LEGACY` entries. Then run:

```sh
docker compose up -d --build economy-api bot
docker compose ps economy-api bot postgres
docker compose logs --tail=150 bot economy-api
docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -U tori -d tori_main -c "SELECT version, script, success FROM flyway_schema_history WHERE version = '5';"
docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -U tori -d tori_main -c "SELECT to_regclass('public.economy_v2_requests'), to_regclass('public.economy_v2_ledger_entries'), to_regclass('public.economy_v2_inventory_events'), to_regclass('public.economy_v2_activity_events');"
```

Require a successful Flyway V5 row and all expected relations before continuing.
The API must show `healthy`. Its port is not mapped to the Pi host; Java reaches
it at `http://economy-api:4001` on the Compose network. `docker compose port
economy-api 4001` should show no published mapping.

## Verify a post-V5 backup/restore before any writes

Take a second backup after V5 is installed, restore it to another new temporary
database, and compare V5-aware schema, Flyway state, wallet/balance, inventory,
market, ticket/order, moderation, and V2-table counts:

```sh
set -euo pipefail
backup_v5="$backup_dir/tori-main-v5-$(date -u +%Y%m%dT%H%M%SZ).dump"
docker compose exec -T postgres pg_dump -U tori -d tori_main -Fc > "$backup_v5"
test -s "$backup_v5"
sha256sum "$backup_v5" > "$backup_v5.sha256"

restore_v5_db="tori_restore_v5_$(date -u +%Y%m%d%H%M%S)"
docker compose exec -T postgres createdb -U tori "$restore_v5_db"
docker compose exec -T postgres pg_restore --exit-on-error --no-owner -U tori -d "$restore_v5_db" < "$backup_v5"
docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -U tori -d tori_main -At < economy-v2/sql/restore_integrity_v5.sql > "$backup_dir/source-v5.json"
docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -U tori -d "$restore_v5_db" -At < economy-v2/sql/restore_integrity_v5.sql > "$backup_dir/restore-v5.json"
cmp "$backup_dir/source-v5.json" "$backup_dir/restore-v5.json"
```

Stop if restore or comparison fails. Keep the verified dump and checksum in a
protected location. Once inspected, remove only the temporary restore database:

```sh
docker compose exec -T postgres dropdb --if-exists --force -U tori "$restore_v5_db"
```

The equality check verifies restore consistency, not an off-device disaster
recovery copy. Copy the verified dump to protected storage on another device
before declaring the backup gate complete.

## Controlled command cutover

Keep the API healthy with production writes disabled until the V5 restore check
passes and the operator has verified that only one production Java bot instance
is running. The secret acknowledgment is a deliberate operator assertion; it
does not perform the backup or prove external processes are stopped. These
steps transfer ownership of one command at a time. For each command, the Java
route sends that operation only to Elixir and has no legacy fallback; the API
allowlist rejects any other production mutation.

The current parity audit still finds Java commands such as `/beg`, `/work`,
`/loot`, `/grantcredits`, `/gamble`, `/slots`, and shop purchases writing the
same global wallet table. The running Java bot does not disable those writers
when `/daily` or `/transfer` is routed. Under the project's strict one-writer
per wallet-data-area rule, the sample cutover below is **not ready to execute**
until those Java wallet writers are migrated or disabled and that ownership
change is verified. The API gate cannot discover other Java writers by itself.

Before cutover, ensure `.env` has:

```dotenv
TORI_ECONOMY_WRITE_ENABLED=false
TORI_ECONOMY_WRITE_MODE=disabled
TORI_ECONOMY_ROUTING=balance=LEGACY,inventory=LEGACY,shop=LEGACY,leaderboard=LEGACY,daily=LEGACY,transfer=LEGACY
```

After backup/restore, V5, database contents, and single-instance ownership are
all verified, edit `.env` to enable **daily only**:

```dotenv
TORI_ECONOMY_WRITE_ENABLED=true
TORI_ECONOMY_WRITE_MODE=production
TORI_ECONOMY_PRODUCTION_DATABASE_NAME=tori_main
TORI_ECONOMY_PRODUCTION_CUTOVER_ACK=I_VERIFIED_BACKUP_RESTORE_SCHEMA_AND_EXCLUSIVE_WRITER_OWNERSHIP
TORI_ECONOMY_PRODUCTION_WRITE_OPERATIONS=daily.claim
TORI_ECONOMY_ROUTING=daily=ELIXIR
```

Recreate the two services so both the API gate and Java routing use the same
cutover configuration:

```sh
docker compose up -d --force-recreate economy-api bot
docker compose ps economy-api bot
docker compose logs --tail=100 economy-api bot
```

Verify `/daily` on a test guild, then monitor logs and balances before proceeding.
Only after daily is stable, add transfer to both allowlists in `.env`:

```dotenv
TORI_ECONOMY_PRODUCTION_WRITE_OPERATIONS=daily.claim,wallet.transfer
TORI_ECONOMY_ROUTING=daily=ELIXIR,transfer=ELIXIR
```

Recreate and verify again:

```sh
docker compose up -d --force-recreate economy-api bot
docker compose ps economy-api bot
docker compose logs --tail=100 economy-api bot
```

The API checks the live PostgreSQL database name and successful Flyway V5
record before accepting production writes. It rejects a request unless its
operation is in the explicit allowlist. Do not set the production acknowledgment
until the checks above are complete. The Compose defaults keep every route
`LEGACY` and writes disabled.

To stop Elixir writes and return both commands to Java, first set:

```dotenv
TORI_ECONOMY_ROUTING=balance=LEGACY,inventory=LEGACY,shop=LEGACY,leaderboard=LEGACY,daily=LEGACY,transfer=LEGACY
TORI_ECONOMY_WRITE_ENABLED=false
TORI_ECONOMY_WRITE_MODE=disabled
TORI_ECONOMY_PRODUCTION_DATABASE_NAME=
TORI_ECONOMY_PRODUCTION_CUTOVER_ACK=
TORI_ECONOMY_PRODUCTION_WRITE_OPERATIONS=
```

Then recreate both services with the same command. Keep Flyway V5 and its
additive tables in place; do not roll back or delete them as part of routing
rollback. No production migration or write is run by this guide's authoring
workflow.
