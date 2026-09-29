# Tori Economy API — incremental migration

Tori keeps one persistent PostgreSQL database. Java and Elixir use the same
existing Tori tables at runtime; Elixir does not own a second permanent copy.
The API is disabled unless configured. In Docker Compose it listens on the
private Compose network and is not published on the host.

## Routed areas

`/balance`, `/inventory`, `/shop`, `/leaderboard`, `/daily`, and `/transfer`
can each be routed to the private Elixir API. All areas default to `LEGACY`.
For the existing routed reads, Elixir returns neutral structured data and Java
continues to render the production Discord output. `/stats` currently reports
runtime/server metrics, not economy data, so it remains Java-owned. The newer
`profile.snapshot`, catalog, rotation, wardrobe, progression, activity and
marketplace domain operations are exposed through the isolated Nostrum preview;
the Java `/profile` and existing production commands have not been routed to
them.

The Elixir Persona foundation contains supervised transient mood state,
deterministic seasonal overlays and semantic response rendering. It is
presentation-only: it cannot alter economy outcomes or permissions. The
private authenticated `GET /internal/persona/v1/snapshot` and
`POST /internal/persona/v1/events` endpoints expose state and accept only
predefined event names. `POST /internal/persona/v1/render` returns a phrase
from a semantic key, validated context and structured variables. The opt-in
Nostrum preview uses this layer for preview responses; Java production
commands and presence remain unchanged. See
`../docs/ELIXIR-REWRITE-STATUS.md` for ownership and remaining work.

An optional Nostrum preview is available for a **separate test bot only**.
It is disabled unless `TORI_NOSTRUM_ENABLED=true`,
`TORI_NOSTRUM_PREVIEW_ONLY=true`, a dedicated test application/guild/token,
`TORI_ECONOMY_WRITE_MODE=test`, and an explicit isolated `*_test` database are
configured. Test writes also require `TORI_ECONOMY_WRITE_ENABLED=true`. It registers only
uniquely named `tori-*-preview` commands and supports shop category/page
navigation, item details and test-gated purchases, profile, wardrobe,
marketplace, careers, consumables, gathering activities and Persona previews.
Interactions are acknowledged before domain work and edited ephemerally. It
does not register or take ownership of Java/JDA commands. Never enable it with
Tori's production bot token.

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

The Elixir Shop domain uses the existing wallet and inventory plus additive
catalog/rotation tables. Its purchase, inventory, career, activity and
marketplace writes are implemented behind the test-only WriteGate; production
routing remains `LEGACY`, so Java remains the live writer. `TORI_MARKET_MODE=READ_ONLY`
blocks legacy Java market writes while preserving its views; shop read routing
does not transfer market write ownership.
`market.product` and `market.history` now expose additional read-only V2-table
views through the same private API. Neither operation creates a quote, evolves
a price, changes stock, or authorizes an Elixir Market writer. Java Market
commands are not routed to these operations yet.

## Writes and ownership

Elixir mutations return HTTP 403 `READ_ONLY` by default because the Compose
default is `TORI_ECONOMY_WRITE_ENABLED=false` and
`TORI_ECONOMY_WRITE_MODE=disabled`. Production routing remains `LEGACY`. Test
writes require explicit enablement, test mode, a configured `_test` database
name, and a runtime check that the connected PostgreSQL database is also
`_test`.

Production writes require a separate explicit mode, exact database-name match,
successful Flyway V5 state, an operator acknowledgement, and an operation
allowlist currently limited to daily and transfer. The production gate does not
authorize new shop, inventory, activity, progression, career or marketplace
mutations. These gates do not prove that a backup was restored or stop other
Java processes. Other Java commands still write the same global wallet rows,
so production wallet writes remain blocked until all writers for that data
area have been transferred or disabled under a reviewed ownership plan.

Mutation requests use the Discord interaction ID as a persistent idempotency
key. Daily and transfer balance updates, ledger entries, and stored results
share one PostgreSQL transaction. The current test harness applies the
repository's additive V1–V10 Flyway SQL to an empty isolated test database; it
does not apply migrations to production. V5 reuses `economy_accounts`; V6–V10
add catalog, rotation, loadout, progression, marketplace, activity and
consumable structures without resetting existing data. Do not deploy a bot
image that auto-applies pending migrations until the backup/restore and schema
review gates in the Pi guide have been completed.

## Build, test, and deployment

- Java: from `Tori`, run `./gradlew clean test build --no-daemon`.
- Elixir: from `Tori/economy-v2`, run `mix test`.
- Isolated PostgreSQL tests require `TORI_ECONOMY_TEST_DATABASE_URL` to point
  to a dedicated database ending in `_test`. Under `MIX_ENV=test`, the
  application starts its Repo from this URL and ignores production database
  settings. `mix test` applies Tori's V1–V10 Flyway SQL files to a fresh, empty
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

The rewrite's full integration suite, Discord preview and Docker release
configuration have not been verified on this workstation. No production
migration or write was run. Keep routes `LEGACY` and write mode `disabled`
until the operator completes backup/restore, V1–V10 schema review and
writer-ownership checks in the Pi guide.

See `../docs/ECONOMY-V2-READINESS.md` for the parity, backup/restore, and
writer-ownership audit.
