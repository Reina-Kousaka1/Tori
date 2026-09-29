# Tori Economy API — incremental migration

Tori keeps one persistent PostgreSQL database. Java and Elixir use the same
existing Tori tables at runtime; Elixir does not own a second permanent copy.
The API service is opt-in and binds to `127.0.0.1:4001` by default.

## Read paths

`/balance`, `/inventory`, `/shop`, and `/leaderboard` can each be routed to the
private Elixir API. Elixir returns neutral structured data from the existing
`economy_accounts`, `economy_inventory`, `economy_market_products`, and
`economy_market_sales` tables. Java continues to render Discord output and
resolve item display names. `/stats` currently reports runtime/server metrics,
not economy data, so it remains Java-owned.

Routing defaults to LEGACY. Configure selected areas in one setting, for
example:

```dotenv
TORI_ECONOMY_ROUTING=balance=ELIXIR,inventory=ELIXIR,shop=ELIXIR,leaderboard=ELIXIR
TORI_ECONOMY_URL=http://127.0.0.1:4001
TORI_ECONOMY_API_SECRET=<at-least-32-random-characters>
```

Unspecified areas remain LEGACY. The earlier
`TORI_ECONOMY_BALANCE_SOURCE=LEGACY|ELIXIR` setting remains supported when the
central routing setting is absent. Use a private local API secret and do not
commit it. The Java client only accepts a loopback HTTP origin.

The shop API is a read-only view over the same market tables. During the
transition, Java remains the market writer: it creates the existing user and
guild-bound purchase quotes and handles purchases. `TORI_MARKET_MODE=READ_ONLY`
blocks those writes, while preserving the market views. Shop routing therefore
does not transfer market write ownership.

## Writes and tests

Elixir mutations (`daily.claim`, `wallet.transfer`) remain HTTP 403 `READ_ONLY`
unless `TORI_ECONOMY_WRITE_ENABLED=true`; leave this unset/false. They are not
routed from Discord yet. The Java commands remain the active writers. Do not
enable Elixir writes or cut over a data area until the existing Tori database
backup/restore, parity, idempotency, and single-writer gates have been verified.

No new migration is part of these read paths. No schema was created, copied,
reset, or changed. Production PostgreSQL was not contacted. The existing
`sql/draft/V5__economy_v2_core.sql` remains outside Flyway's automatic path.

- Java: from `Tori`, run `./gradlew clean test build --no-daemon`.
- Elixir: from `Tori/economy-v2`, run `mix test`.
- PostgreSQL integration tests are opt-in and require a dedicated database
  ending in `_test` via `TORI_ECONOMY_TEST_DATABASE_URL` (or Java's
  `TORI_TEST_DATABASE_URL`). They seed uniquely identified fixtures and clean
  them up. Never set these variables to the production database.

The service requires `TORI_ECONOMY_API_ENABLED=true`,
`TORI_ECONOMY_DATABASE_URL` pointing to the existing persistent Tori PostgreSQL
database, and `TORI_ECONOMY_API_SECRET`. It creates no parallel database and
does not auto-run the draft V5 migration.

See `../docs/ECONOMY-V2-READINESS.md` for the parity, backup/restore, and
writer-ownership gates.
