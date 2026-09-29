# Tori Economy API — incremental migration

Tori keeps one persistent PostgreSQL database. Java and Elixir use the same
existing Tori tables at runtime; Elixir does not own a second permanent copy.
The API is disabled unless configured. In Docker Compose it listens on the
private Compose network and is not published on the host.

## Routed areas

`/balance`, `/inventory`, `/shop`, `/leaderboard`, `/daily`, and `/transfer`
can each be routed to the private Elixir API. All areas default to `LEGACY`.
Elixir returns neutral structured data from existing tables. Java continues to
render Discord output and resolve item display names. `/stats` currently
reports runtime/server metrics, not economy data, so it remains Java-owned.

Routing can be set centrally, for example:

```dotenv
TORI_ECONOMY_ROUTING=balance=ELIXIR,inventory=ELIXIR,shop=ELIXIR,leaderboard=ELIXIR,daily=LEGACY,transfer=LEGACY
TORI_ECONOMY_URL=http://127.0.0.1:4001
TORI_ECONOMY_API_SECRET=<at-least-32-random-characters>
```

Unspecified areas remain `LEGACY`. The earlier
`TORI_ECONOMY_BALANCE_SOURCE=LEGACY|ELIXIR` setting remains supported when the
central routing setting is absent. The Java client only accepts loopback HTTP
origins or the exact internal Compose service name `economy-api`.

The shop API is a read-only view over the existing market tables. Java remains
the market writer and owns quote and purchase behavior. `TORI_MARKET_MODE=READ_ONLY`
blocks market writes while preserving its views; shop read routing does not
transfer market write ownership.

## Writes and ownership

Elixir mutations (`daily.claim`, `wallet.transfer`) return HTTP 403 `READ_ONLY`
by default. Production routing remains `LEGACY`. Test writes require explicit
test mode, a configured `_test` database name, and a runtime check that the
connected PostgreSQL database is also `_test`.

Production writes require a separate explicit mode, exact database-name match,
successful Flyway V5 state, an operator acknowledgement, and an operation
allowlist. These gates do not prove that a backup was restored or stop other
Java processes. Other Java commands still write the same global wallet rows,
so production wallet writes remain blocked until all writers for that data
area have been transferred or disabled under a reviewed ownership plan.

Mutation requests use the Discord interaction ID as a persistent idempotency
key. Daily and transfer balance updates, ledger entries, and stored results
share one PostgreSQL transaction. Additive V5 is in Java's regular Flyway path
at `../src/main/resources/db/migration/V5__economy_v2_core.sql`; it reuses
`economy_accounts` and does not reset or copy existing data. Do not start a
production bot build that applies V5 until the pre-migration backup and restore
gate in the Pi guide has been completed.

## Build, test, and deployment

- Java: from `Tori`, run `./gradlew clean test build --no-daemon`.
- Elixir: from `Tori/economy-v2`, run `mix test`.
- Isolated PostgreSQL tests require `TORI_ECONOMY_TEST_DATABASE_URL` to point
  to a dedicated database ending in `_test`. Under `MIX_ENV=test`, the
  application starts its Repo from this URL and ignores production database
  settings. `mix test` applies Toris V1–V5 Flyway SQL files to a fresh, empty
  test schema before running the integration tests; an incomplete nonempty
  schema fails verification instead of being silently modified. The target
  URL, supervised Repo configuration and connected database name are checked
  before any schema write. Without the test URL, database integration tests
  are skipped. Never use production credentials or data for these tests.
- Java-to-Elixir integration tests also require the local test API secret and
  `TORI_ECONOMY_TEST_WRITE_ENABLED=YES`.

Docker builds a Mix release from `Dockerfile`. Compose passes the same
PostgreSQL credentials used by Java, does not publish the API port, and creates
no parallel database. The container health check uses the internal health
route. See the [Raspberry Pi deployment and cutover guide](../docs/ECONOMY-API-PI-DEPLOYMENT.md).

The production gate and Docker release configuration have not yet been
verified on this workstation. No production migration or write was run. Keep
routes `LEGACY` and write mode `disabled` until the operator completes the
backup/restore, V5 and writer-ownership checks in the Pi guide.

See `../docs/ECONOMY-V2-READINESS.md` for the parity, backup/restore, and
writer-ownership audit.
