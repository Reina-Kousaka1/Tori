# Tori Java/Elixir rewrite: implementation and ownership

This is an implementation status, **not** a production cutover authorization. All new V2 writes are disabled in the shipped Compose configuration and Java routing remains `LEGACY`.

```mermaid
flowchart LR
  Discord --> JDA[Java / JDA commands and music]
  JDA --> Legacy[Legacy economy and market writers]
  JDA -. optional private API .-> API[Elixir / Bandit]
  API --> Gate[WriteGate]
  Gate --> Domain[Shop, Inventory, Progression, Marketplace]
  API --> Persona[OTP Mood and Persona]
  Legacy --> PG[(One Tori PostgreSQL)]
  Domain --> PG
  JDA --> Presence[Java status rotation]
```

## Implemented behind the gate

- V6 extends the existing V5 catalog and adds persisted rotations, fashion loadout, XP and career structures, and escrowed marketplace listings. V7 seeds an original, generic catalog spanning fashion, accessories, beauty, ballet, volleyball, cheer, consumables, collectibles, and seasonal items. V8 seeds the exact legacy tool/drop IDs and durability needed for activity parity. Inserts preserve existing catalog edits and inventory.
- `shop.rotation` selects a weighted, deterministic, season-aware drop once per period and persists its item, price and stock snapshots. Reads of an existing period are stable. First creation is a guarded write and therefore unavailable when writes are disabled.
- `shop.purchase` locks the global wallet and rotation stock, validates ownership and configured level requirement, then debits credits, grants to the **existing** `economy_inventory`, writes wallet/inventory events and stores the idempotent result in one PostgreSQL transaction.
- `inventory.equip`/`inventory.unequip` use the same inventory ownership and V6 fashion slots. Existing rod/pickaxe/axe/wrench equipment remains in legacy tables.
- `progression.snapshot` reads persisted XP and career state. `progression.grant` accepts **only explicitly configured active** XP sources and uses persisted cooldowns, idempotency and career XP events. V10 seeds the level curve and starter career/activity XP sources; these are not production-active while routing is LEGACY and the WriteGate is disabled.
- `activity.perform` has test-gated fish/mine/chop parity using existing equipped tools, wear, wallet, cooldown columns, drop thresholds and reward ranges. Wallet, inventory, wear, cooldown, event, optional configured XP and idempotent result share one transaction. Java activity routing is unchanged.
- V9 separates consumable definitions from persistent active effects. `inventory.consume` removes one owned item and activates an explicitly configured effect atomically; `inventory.effects` reads unexpired effects. V10 configures two starter consumables with bounded 30-minute activity bonuses. These effects apply only through the isolated/test-gated Elixir activity path; Java production rewards are unchanged.
- V10 additively seeds 61 further original catalog definitions (stable-ID conflicts are left untouched), cosmetic slots/selections, level/career unlock requirements, a centralized level 1–50 XP threshold curve, career descriptions/switch policy, career practice sources and configured activity XP sources. The schema test requires at least 125 active catalog rows after V1–V10. Career XP and account XP remain separate from Credits.
- The Nostrum consumer on Tori's existing Discord application owns only `/career` and `/profile`. Those handlers use the existing career engine and read-only profile aggregator through the validated Elixir domain boundary. The API does not create another wallet, inventory or career state.
- Nostrum is loaded only when `TORI_NOSTRUM_ENABLED=true`. It uses the existing `DISCORD_TOKEN`, validates `DISCORD_GUILD_ID`, discovers the application ID from Discord and upserts only `/career` and `/profile` into that guild. It does not delete or replace other commands. JDA keeps all existing command definitions and handlers; JDA startup adds missing Java commands only, while the explicit Java command registrar updates its commands individually, so neither path erases Nostrum commands. Missing Discord configuration is ignored while Nostrum is disabled and fails safely when enabled. Live Discord registration has not been verified in this environment.
- Profile snapshots include persisted wallet, XP/level, active and retained career progression, inventory, loadout, selected cosmetics and legacy activity timestamps. Cosmetic selection is separate from consumable effects. Mood event influences have per-event cooldowns and remain presentation-only.
- Main-bot `/career` and `/profile` interactions are acknowledged before domain work and answered ephemerally. Nostrum does not process legacy preview helpers, Java-owned commands, or components. Career activities never publish global Persona or Presence events. The existing JDA presence writer, Music/Lavalink and Java command handlers remain unchanged; only command registration changed to safe per-command upserts.
- `marketplace.browse` reads active user listings. Listing escrows an existing owned item; buying settles global wallet balances, delivers the escrowed item and writes both ledger legs atomically. Seller cancellation returns escrow, including after expiry. Java's existing dynamic market is separate and stays Java-owned.
- `profile.snapshot` now includes existing fish/mine/chop timestamps and the fashion loadout. It does not pretend that legacy activity rewards are Elixir-owned.
- Persona has a supervised transient mood process, deterministic seasonal overlay and semantic, neutral-fallback rendering API. Domain outcomes carry presentation keys but no Discord markup. Java commands/presence remain unaffected.
- `GET /internal/persona/v1/presence` offers a deterministic mood/season-aware suggestion with repetition avoidance in the pure selector. It does not call Discord; Java's scheduler and actual presence remain unchanged.

## Gates and explicit limitations

`WriteGate` permits new mutations only in explicit `test` mode against a connected `*_test` database. Its production allowlist remains restricted to the previously reviewed daily/transfer operations; V2 shop, XP, inventory, rotation and marketplace cannot be enabled in production through environment variables. `disabled` rejects every mutation even with `WRITE_ENABLED=true`. No Java command has been routed to the new operations.

The Pi previously resolved Nostrum with `mix deps.get`; this host has no Mix, Docker or Gradle executable and no repository checkout, so Elixir formatting/compile/tests, Java tests/build, Compose validation and live Discord registration cannot be run here. Do not enable the main-bot feature until those local gates pass and the exact Pi dependency tree is re-audited.

The proposed Java-to-Elixir transfer for purchases, inventory, XP and marketplace is **not ready**. `PostgresCurrencyStore`, `PostgresMarketStore`, activity and shop commands still mutate wallet/inventory in Java. All new Elixir shop, inventory, progression, career, activity and marketplace writes remain blocked in production; Java routing remains LEGACY. The main-bot integration shares the existing bot token with JDA; Java remains the sole Discord Presence writer. Keep `TORI_NOSTRUM_ENABLED=false` until the test gates and the dependency-audit caveats below are resolved or reviewed. The integration must not be used to enable V2 production writes or alter Java routing. Fishing/mining/chopping production rewards, tools, RNG and cooldown mutations remain Java-owned. Moderation remains Java-owned.

### Nostrum dependency security review

The Raspberry Pi validation report for `mix hex.audit` identified `gun 2.6.0` and `cowlib 2.20.0` in the opt-in Nostrum dependency tree and reported CVE-2026-43966 (GHSA-w4f7-4cxr-rv3c, Medium) plus CVE-2026-43969 (GHSA-g2wm-735q-3f56, Low) for cowlib. The report also says `mix deps.update cowlib` did not resolve to a newer compatible dependency set. Do not add a forced dependency override or pin to hide these findings; keep Nostrum disabled until the exact Pi lock/dependency tree is re-audited and the findings are resolved or explicitly reviewed.

The upstream advisory metadata should be reconciled against the Pi audit output: the published CVE-2026-43969 range is cowlib >= 2.9.0 through 2.16.1, which does not include `2.20.0`, while CVE-2026-43966 lists cowlib >= 2.9.0 and remains relevant to `2.20.0`. The GitHub advisory lists gun versions < 2.4.0 as affected, so the reported `gun 2.6.0` is outside that range. Treat this as a dependency-audit discrepancy to confirm on the Pi using the exact resolved lockfile, not as evidence that the entire tree is clear. See the [CVE-2026-43966 advisory](https://github.com/advisories/GHSA-w4f7-4cxr-rv3c), [CVE-2026-43969 advisory](https://github.com/advisories/GHSA-g2wm-735q-3f56), and [Hex cowlib advisories](https://hex.pm/packages/cowlib/advisories).

Migrations V6–V10 are additive but Flyway applies pending migrations automatically when a new Java image starts. Before deploying that image: back up the real DB, restore to a separate database, verify counts/sums and Flyway state, run migration and integration tests on that restore, and verify catalog IDs against legacy inventory. Do not use the isolated test DB as a second persistent runtime database. Never enable an Elixir wallet or inventory writer while any Java command or market path can write the same rows. This pass did not run a production migration, change routing or gates, or enable Nostrum.

## Pi verification commands

From the repository's `Tori` directory:

```sh
git diff --check
read -rsp 'Disposable test DB password: ' TORI_TEST_DB_PASSWORD; echo
export TORI_TEST_DB_PASSWORD
docker run -d --name tori-economy-test-pg -p 127.0.0.1:5433:5432 \
  -e POSTGRES_USER=tori_test -e POSTGRES_PASSWORD -e POSTGRES_DB=tori_economy_test postgres:17
until docker exec tori-economy-test-pg pg_isready -U tori_test -d tori_economy_test; do sleep 1; done
export TORI_ECONOMY_TEST_DATABASE_URL="postgresql://tori_test:${TORI_TEST_DB_PASSWORD}@127.0.0.1:5433/tori_economy_test"
cd economy-v2
mix deps.get
mix format --check-formatted
mix compile --warnings-as-errors
mix test
cd ..
./gradlew clean test build --no-daemon
docker compose config --quiet
```

The disposable container has no volume. Its DB is isolated from Compose's `tori_main`. The test harness applies the real Flyway V1–V10 SQL to a fresh empty `*_test` database and refuses any other target. Use a disposable password without URL-special characters for this command, or URL-encode it. After verification, remove only the explicitly named test container with `docker rm -f tori-economy-test-pg`; do not touch the Tori Compose database. These commands are **not yet verified in this Windows environment** because Mix, Docker and a test PostgreSQL instance are unavailable here; Gradle dependency resolution may also require network access. Do not push or deploy this stack until these checks pass and test failures are fixed.

When Pi tests are green, inspect `git status`, rerun `git diff --check` and `git log --oneline origin/main..main`, then push normally with `git push origin main`. Never force-push. If formatting changes files, commit those changes and rerun tests first.

If the Pi must test this still-unpushed stack, transfer it as a Git bundle instead of pushing untested commits. On the development machine run `git bundle create tori-rewrite.bundle origin/main..main` and copy that file to the Pi. On a **clean** Pi `main` at the same `origin/main` base, run `git fetch /path/to/tori-rewrite.bundle main` then `git merge --ff-only FETCH_HEAD`; this does not create a feature branch or change production services. Verify `git status --short` first and preserve any local Pi changes before merging. Then run the checks above. A bundle is a transfer artifact, not a second repository or database.
