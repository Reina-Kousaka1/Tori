# Tori Social 1.1

Social actions use JDA only for Discord transport, target resolution, and presentation. JDA sends the Discord interaction ID, actor, target, and action to the existing private Economy API. Elixir validates the action, applies the persistent cooldown, stores the response through the existing request-idempotency table, and returns a structured result. JDA renders that result and may attach a GIF.

The initial actions are `hug`, `kiss`, `pat`, `cuddle`, `poke`, `slap`, `bite`, `tickle`, `highfive`, `boop`, and `wave`. Each requires another member. Cooldowns are scoped to the actor/action/target tuple and last exactly 24 hours according to PostgreSQL time. Reusing an interaction ID replays its original result; concurrent requests for the same tuple serialize in PostgreSQL. Social actions currently have no wallet, inventory, or marriage effect.

## Ownership and persistence

- PostgreSQL remains the only authoritative store. `economy_v2_social_action_cooldowns` stores the latest successful action time for each actor/action/target tuple. `economy_v2_requests` stores idempotent action results.
- Marriage remains in the existing Elixir `ToriEconomy.Marriage` domain and `economy_v2_marriages` table. Profile relationship snapshots continue reading that table through the existing query path; Social 1.1 adds no second relationship state.
- The `database-ferretdb` Gradle module provides a generic document adapter, but the Tori application does not wire it into its runtime or social state. Cooldowns and idempotent command results need relational transactions and concurrency constraints, so they remain in PostgreSQL.
- Java/JDA owns the new `/hug`, `/kiss`, `/pat`, `/cuddle`, `/poke`, `/slap`, `/bite`, `/tickle`, `/highfive`, `/boop`, and `/wave` commands. The existing Elixir/Nostrum marriage commands are unchanged.

## Rollout and GIF presentation

The central `TORI_ECONOMY_ROUTING` configuration accepts `social=LEGACY|ELIXIR`; omitted areas, including `social`, default to `LEGACY`. Since there is no legacy social-action writer, actions return an unavailable response until the route is explicitly set to `ELIXIR`. Routing alone does not enable writes: the existing Elixir `WriteGate` must authorize `social.action`. Production remains read-only because the default write mode is disabled and `social.action` is not in the production operation allowlist.

`TORI_SOCIAL_GIF_URLS` is optional JSON mapping action IDs to arrays of direct HTTPS URLs, for example `{"hug":["https://cdn.example.invalid/hug.gif"]}`. URLs with credentials, query strings, fragments, unsupported actions, or non-HTTPS schemes are rejected without echoing the value. An empty or missing pool renders the action as text. The provider interface can be replaced without changing domain rules.

Social integration tests require an explicitly configured isolated PostgreSQL database ending in `_test` via `TORI_ECONOMY_TEST_DATABASE_URL`. They must never use the production database.
