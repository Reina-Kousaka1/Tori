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
runtime/server metrics, not economy data, so it remains Java-owned. The newer `profile.snapshot`, career, catalog, rotation, wardrobe,
progression, activity and marketplace domain operations are implemented in
Elixir. Nostrum owns only the main-bot `/career` and `/profile` interactions;
Java/JDA continues to own the existing command catalog, music and presence.

The Elixir Persona foundation contains supervised transient mood state,
deterministic seasonal overlays and semantic response rendering. It is
presentation-only: it cannot alter economy outcomes or permissions. The
private authenticated `GET /internal/persona/v1/snapshot` and
`POST /internal/persona/v1/events` endpoints expose state and accept only
predefined event names. `POST /internal/persona/v1/render` returns a phrase
from a semantic key, validated context and structured variables. User Career
state remains independent of the global Persona and Presence context; Java
remains the only Presence writer. See
`../docs/ELIXIR-REWRITE-STATUS.md` for ownership and remaining work.

The optional Nostrum integration uses the existing main-bot `DISCORD_TOKEN`
and configured `DISCORD_GUILD_ID` when `TORI_NOSTRUM_ENABLED=true`. Missing
token or guild configuration fails startup with a safe message; when the
feature is disabled, neither value is required by Economy API. Nostrum
registers and handles only `/career` and `/profile` in that guild. It does
not register or handle Java commands. At startup, JDA checks its own command
names and adds missing ones only. The explicit Java command registrar updates
existing Java commands individually and does not delete Elixir-owned guild
commands.
Interactions are acknowledged before domain work and answered ephemerally.
Career writes remain subject to the existing WriteGate and production
allowlist.

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

## Career Gameplay V1 and Profile V1

Career Gameplay V1 uses the existing career selection, per-career XP, XP source,
request-idempotency, wallet, ledger, inventory, equipment and cooldown tables.
It configures four activities for each supported career: Ballet class, barre,
rehearsal and performance; Volleyball practice, drills, scrimmage and match;
Cheer practice, tumbling/stunts, routine and competition. Every activity uses
the same progression engine. Career XP also advances the existing account XP
counter. Base rewards are bounded by the configured action: 10–22 Credits and
16–30 XP, with a 30-minute, one-hour or four-hour cooldown. A single correctly
equipped V11 item from that career's category grants a flat +2 XP and +5 Credits
per action; the bonus does not stack. Rehearsal, scrimmage and routine require
career level 3 plus the matching V11 outfit item; performance, match and
competition require level 4 plus matching V11 gear.

V12 only adds action reward and requirement columns and seeds those action
definitions. It does not rewrite wallet, inventory, selections, progress or
historical XP. No new gameplay table or separate skill counter is introduced.
The profile's account Level comes from account XP; Career Level comes from the
selected career's XP; Skill Level is a derived summary of XP across all active
careers using the existing shared thresholds. Thus Skill Level adds no second
persisted progression state. `/profile [user]` is a read-only aggregate of
Credits, account XP/Level, derived Skill Level, selected Career XP/Level,
equipped outfit and selected style. The read-only profile also reads an active
Relationship from the V12 Marriage domain; it stores no relationship copy.
Individual career actions do not record global Persona mood
events or change Tori's global Presence context.

The `/profile`, `/career`, `/wardrobe`, `/marketplace`, `/marry`,
`/divorce` and `/marriage` commands are Elixir-owned and registered in the
configured main-bot guild when Nostrum is enabled. Optional `TORI_NOSTRUM_SHOP_ENABLED=true` transfers only `/shop`
slash-command ownership: Java excludes it from registration and interaction
handling when both Nostrum flags are true, and Elixir registers the V12 Catalog
and featured-drop UI. It defaults to false. Java's remaining commands,
Music/Lavalink and technical Presence writer are unchanged. Production Shop,
Career and Marriage mutations remain blocked by the current WriteGate allowlist.

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
authorize new shop, inventory, activity, progression, career, marriage or
marketplace mutations. These gates do not prove that a backup was restored or stop other
Java processes. Other Java commands still write the same global wallet rows,
so production wallet writes remain blocked until all writers for that data
area have been transferred or disabled under a reviewed ownership plan.

Mutation requests use the Discord interaction ID as a persistent idempotency
key. Daily and transfer balance updates, ledger entries, and stored results
share one PostgreSQL transaction. The current test harness applies the
repository's V1–V12 Flyway SQL and repeatable V12 scripts to an empty
isolated test database; it
does not apply migrations to production. V5 reuses `economy_accounts`; V6–V12
add catalog, rotation, loadout, progression, marketplace, activity, consumable
and career-action configuration without resetting existing player data. The
repeatable V12 scripts insert 51 new style IDs, add necklace and earring slots,
and create the Marriage relationship table. Existing TEXT-array tags are reused. Do not
deploy a bot image that auto-applies pending migrations until the backup/restore and schema
review gates in the Pi guide have been completed.

## V12 Shop and AutoMod gates

The existing Java `/shop` and `/buy` use legacy Market products. V12 Catalog
items were absent from that Discord purchase path. With optional Elixir `/shop`
ownership, `/shop catalog` paginates active styles, `/shop browse` shows a
persisted featured rotation, and `/shop item` and `/shop buy` accept an item ID.
Catalog purchases reuse the account, ledger, inventory and idempotency
transaction; a numeric featured period still checks rotation expiry and stock.
Seasonal and limited-stock items retain their availability rules. `/wardrobe`
uses existing Inventory/Equipment slots, and `/marketplace` uses existing
listing escrow and purchase transactions. Java `/market`, `/equip` and tool
commands remain Java-owned. This does not enable production wallet writes.

`TORI_AUTOMOD_ENABLED=false` by default. When enabled with Nostrum, per-guild
OTP processes detect join bursts, floods, mention spam and invite links.
Detection cases reuse `moderation_cases` with action `AUTO_DETECT` and result
`OBSERVED`; no ban, timeout or deletion is executed. Thresholds, allowlists
and review/alert escalation are transient, with internal OTP configuration
and overrides. Join and message-content detection requires privileged gateway
intents for the Discord application. Keep this disabled until the release gate.

## Build, test, and deployment

- Java: from `Tori`, run `./gradlew clean test build --no-daemon`.
- Elixir: from `Tori/economy-v2`, run `mix test`.
- Isolated PostgreSQL tests require `TORI_ECONOMY_TEST_DATABASE_URL` to point
  to a dedicated database ending in `_test`. Under `MIX_ENV=test`, the
  application starts its Repo from this URL and ignores production database
  settings. `mix test` applies Tori's V1–V12 Flyway SQL and repeatable V12 files to a fresh, empty
  test schema before running the integration tests; an incomplete nonempty
  schema fails verification instead of being silently modified. An older
  isolated schema with V1–V11 can receive the additive V12 action config and repeatables. The target
  URL, supervised Repo configuration and connected database name are checked
  before any schema write. Without the test URL, database integration tests
  are skipped. Never use production credentials or data for these tests.
- Java-to-Elixir integration tests also require the local test API secret and
  `TORI_ECONOMY_TEST_WRITE_ENABLED=YES`.

Docker builds a Mix release from `Dockerfile`. Compose passes the same
PostgreSQL credentials used by Java, does not publish the API port, and creates
no parallel database. The container health check uses the internal health
route. See the [Raspberry Pi deployment and cutover guide](../docs/ECONOMY-API-PI-DEPLOYMENT.md).

The main-bot Nostrum registration and Compose environment wiring have not been
verified against live Discord in this environment. No production migration or
write was run. Keep routes `LEGACY` and the write mode `disabled`; verify
formatting, compilation, isolated database tests, Java tests and Compose
configuration on the Raspberry Pi before enabling the feature.

See `../docs/ECONOMY-V2-READINESS.md` for the parity, backup/restore, and
writer-ownership audit.
