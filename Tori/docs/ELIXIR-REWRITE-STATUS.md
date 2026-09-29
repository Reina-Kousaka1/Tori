# Tori Java/Elixir rewrite: current ownership

This document records the inspected code paths and the scope of the first
Elixir domain increment. It is not a production cutover approval.

## Current architecture

- `music.CommandListener` and `music.CommandRegistration` own Java/JDA slash
  commands. `EconomyRouting` defaults every area to `LEGACY`; only selected
  reads, daily and transfer have optional Elixir routes.
- `PostgresCurrencyStore`, `PostgresMarketStore`, `ShopCatalog` and their
  policies still own the legacy shop, wallet, inventory, fishing, mining,
  chopping and market rules. `StatusRotation` and `ToriPersona` own Java
  presence/persona behavior. Moderation stays in its existing Java paths.
- Flyway V1–V5 defines the one PostgreSQL schema. V2 provides the existing
  global wallet, inventory, equipment and market tables; V3 adds activity
  cooldowns; V5 adds Elixir transaction, ledger and activity support.
- The Elixir API reads those existing tables and has explicitly gated daily
  and transfer writes. The Docker Compose service is internal and points to
  the same `tori_main` database as Java. Its default write mode is `disabled`.

## Added Elixir foundation

- `ToriEconomy.Persona.Mood` is a supervised OTP process with one transient
  bot-wide state. Its pure transition and decay functions can be tested without
  Discord or PostgreSQL. Guild-local state can be layered later; no persistent
  mood or cross-instance synchronization is claimed.
- `ToriEconomy.Persona.Season` resolves deterministic calendar/event overlays
  from an injected date. `ToriEconomy.Persona` renders semantic response keys
  from structured variables. Moderation, administration and system contexts
  use neutral wording. Rendering never changes transaction results.
- `profile.snapshot` reads actual balance, inventory and existing tool
  equipment from V2 tables. It omits XP, careers, fashion loadout and
  achievements because those states do not exist yet. It is an internal API
  read, not a migrated `/profile` Discord command.
- `ToriEconomy.Progression.Policy` can derive a level from an explicitly
  supplied, validated threshold curve. The numeric values in its tests are
  fixtures only. No XP source, runtime curve, level reward or writer is active.
- `ToriEconomy.Market` can read existing product details and price history
  from the V2 Java-owned tables. It never creates quotes or performs market
  maintenance. Market purchases, sales, scheduler ownership and Java routing
  remain unchanged.

## Ownership and cutover boundaries

Java remains the sole production writer for shop, inventory, market,
activities and all legacy commands. Elixir production writes remain gated;
`TORI_ECONOMY_ROUTING` remains `LEGACY` by default. No Nostrum gateway is
started alongside JDA. Moving a Discord command requires a deliberate gateway
and interaction-ownership plan, then tests without a live Discord connection.

The larger catalog, persisted rotations, clothing loadouts, cosmetics,
marketplace, progression, careers and activity migration require additive
schema design against real V1–V5 tables and migration tests before deployment.
They are **not implemented** by this increment. A future writer transfer must
first pass backup/restore verification, parity, idempotency, concurrency and
exclusive-ownership gates. No parallel permanent database is planned.
