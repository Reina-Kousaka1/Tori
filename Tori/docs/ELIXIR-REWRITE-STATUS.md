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

- V6–V12 and the V12 repeatable scripts extend the existing catalog and add persisted rotations, fashion loadout, XP/career structures, escrowed marketplace listings, career actions, style slots and marriage state. The catalog spans fashion, accessories, beauty, ballet, volleyball, cheer, consumables, collectibles and seasonal items. Inserts preserve existing catalog edits and inventory; this pass adds no migration or schema version.
- `shop.rotation` selects a weighted, deterministic, season-aware drop once per period and persists its item, price and stock snapshots. Reads of an existing period are stable. First creation is a guarded write and therefore unavailable when writes are disabled.
- `shop.purchase` locks the global wallet and rotation stock, validates current availability, quantity/stack rules, ownership and the specifically required career level, then debits credits, grants to the **existing** `economy_inventory`, writes wallet/inventory events and stores the idempotent result in one PostgreSQL transaction. The Discord adapter maps an omitted period to the catalog price; a selected featured-drop purchase carries its period key so the server can reject expired periods and enforce remaining stock.
- `inventory.equip`/`inventory.unequip` use the same inventory ownership and V6 fashion slots. Existing rod/pickaxe/axe/wrench equipment remains in legacy tables.
- `progression.snapshot` reads persisted XP and career state. `progression.grant` accepts **only explicitly configured active** XP sources and uses persisted cooldowns, idempotency and career XP events. V10 seeds the level curve and starter career/activity XP sources; these are not production-active while routing is LEGACY and the WriteGate is disabled.
- `activity.perform` has test-gated fish/mine/chop parity using existing equipped tools, wear, wallet, cooldown columns, drop thresholds and reward ranges. Wallet, inventory, wear, cooldown, event, optional configured XP and idempotent result share one transaction. Java activity routing is unchanged.
- V9 separates consumable definitions from persistent active effects. `inventory.consume` removes one owned item and activates an explicitly configured effect atomically; `inventory.effects` reads unexpired effects. V10 configures two starter consumables with bounded 30-minute activity bonuses. These effects apply only through the isolated/test-gated Elixir activity path; Java production rewards are unchanged.
- V10 additively seeds 61 further original catalog definitions (stable-ID conflicts are left untouched), cosmetic slots/selections, level/career unlock requirements, a centralized level 1–50 XP threshold curve, career descriptions/switch policy, career practice sources and configured activity XP sources. The schema test requires at least 125 active catalog rows after V1–V10. Career XP and account XP remain separate from Credits.
- The Nostrum consumer uses the existing main-bot `DISCORD_TOKEN` and configured `DISCORD_GUILD_ID`; there is no `TORI_NOSTRUM_TOKEN` or separate preview bot. When enabled it owns `/career`, `/profile`, `/marry`, `/divorce`, `/marriage`, `/wardrobe` and `/marketplace`. Optional `/shop` ownership requires both `TORI_NOSTRUM_ENABLED=true` and `TORI_NOSTRUM_SHOP_ENABLED=true`; otherwise JDA remains the `/shop` owner. When enabled, JDA excludes `/shop` from registration and interaction handling while Nostrum registers it. JDA remains owner of `/market`, `/buy`, `/sell`, `/inventory`, `/equip`, `/unequip`, balance/currency/activity commands, moderation, tickets/orders and all music commands. The remaining Nostrum command names do not overlap Java command names. Both registrars perform per-command upserts and never bulk-overwrite the guild command collection. The feature and live registration have not been verified against Discord here.
- The main-bot shop path includes `/shop catalog`, `/shop browse`, `/shop item`, `/shop buy`, category/page components, and item-state/requirement rendering. The V12 regression suite now exercises omitted catalog periods, persisted active-drop selection and purchase, expired-drop rejection, insufficient funds, unique-item quantity/duplicate rules, purchase-to-inventory-to-equip-to-profile, and concurrent escrow buyers. These are test definitions, not a claim that this host executed the Elixir suite.
- Profile snapshots include persisted wallet, XP/level, active and retained career progression, inventory, loadout, selected cosmetics and legacy activity timestamps. Cosmetic selection is separate from consumable effects. Mood event influences have per-event cooldowns and remain presentation-only.
- Main-bot Elixir interactions are acknowledged before domain work and answered ephemerally. The optional `/shop` supports catalog/drop browsing with category and page components, item detail and purchase. Main-bot `/wardrobe` reaches the shared `economy_inventory` and `economy_v2_loadout`; `/profile` reads that same equipment state and does not persist a profile copy. The marketplace uses its escrow domain and the same wallet/inventory tables. Nostrum ignores JDA-owned commands and unregistered legacy preview names. The existing JDA presence writer, Music/Lavalink and Java command handlers remain unchanged; command registration stays per-command.
- `marketplace.browse` reads active user listings. Listing escrows an existing owned item; buying settles global wallet balances, delivers the escrowed item and writes both ledger legs atomically. Seller cancellation returns escrow, including after expiry. Java's existing dynamic market is separate and stays Java-owned.
- `profile.snapshot` now includes existing fish/mine/chop timestamps and the fashion loadout. It does not pretend that legacy activity rewards are Elixir-owned.
- Persona has a supervised transient mood process, deterministic seasonal overlay and semantic, neutral-fallback rendering API. Domain outcomes carry presentation keys but no Discord markup. Java commands/presence remain unaffected.
- `GET /internal/persona/v1/presence` offers a deterministic mood/season-aware suggestion with repetition avoidance in the pure selector. It does not call Discord; Java's scheduler and actual presence remain unchanged.

## Gates and explicit limitations

`WriteGate` permits V2 mutations only in explicit `test` mode against a connected `*_test` database. Its production allowlist remains restricted to the previously reviewed daily/transfer operations; V2 shop rotation/purchase, wardrobe/equipment, XP, activities and marketplace cannot be enabled in production through environment variables. In Compose, `TORI_ECONOMY_WRITE_ENABLED=false` and `TORI_ECONOMY_WRITE_MODE=disabled`; `disabled` rejects every mutation even if the enabled flag is mistakenly true. Java economy routing remains `LEGACY`. No JDA command was routed to these new V2 mutations.

This development host has the repository checkout and Java 21, but has no Mix, Elixir, Erlang or Docker executable. Elixir formatting/compile/tests, isolated PostgreSQL integration tests, Compose validation and live Discord registration therefore cannot be verified here. Do not enable the main-bot feature until the Raspberry Pi gates pass and the exact Pi dependency tree is re-audited.

The proposed Java-to-Elixir transfer for purchases, inventory, XP and marketplace is **not ready**. `PostgresCurrencyStore`, `PostgresMarketStore`, activity and shop commands still mutate wallet/inventory in Java. All new Elixir shop, inventory, progression, career, activity and marketplace writes remain blocked in production; Java routing remains LEGACY. `TORI_ECONOMY_WRITE_ENABLED=true` and `TORI_ECONOMY_WRITE_MODE=production` are not sufficient to allow these operations: the production `WriteGate` allowlist currently excludes shop rotation/purchase, equipment and marketplace mutations. Enabling `/shop` on Nostrum alone would not authorize a write or establish exclusive ownership of related Java `/buy`, `/sell`, `/inventory`, `/equip` and `/market` paths. A reviewed code-level allowlist/routing and single-writer cutover is required after backup/restore, migration, and parity gates; there is no safe environment-only production switch for V2 shop/market writes today. The main-bot integration shares the existing bot token with JDA; Java remains the sole Discord Presence writer. Keep `TORI_NOSTRUM_ENABLED=false` until the test gates and the dependency-audit caveats below are resolved or reviewed. The integration must not be used to enable V2 production writes or alter Java routing. Fishing/mining/chopping production rewards, tools, RNG and cooldown mutations remain Java-owned. Moderation remains Java-owned.

### Nostrum dependency security review

The Raspberry Pi validation report for `mix hex.audit` identified `gun 2.6.0` and `cowlib 2.20.0` in the opt-in Nostrum dependency tree and reported CVE-2026-43966 (GHSA-w4f7-4cxr-rv3c, Medium) plus CVE-2026-43969 (GHSA-g2wm-735q-3f56, Low) for cowlib. The report also says `mix deps.update cowlib` did not resolve to a newer compatible dependency set. Do not add a forced dependency override or pin to hide these findings; keep Nostrum disabled until the exact Pi lock/dependency tree is re-audited and the findings are resolved or explicitly reviewed.

The upstream advisory metadata should be reconciled against the Pi audit output: the published CVE-2026-43969 range is cowlib >= 2.9.0 through 2.16.1, which does not include `2.20.0`, while CVE-2026-43966 lists cowlib >= 2.9.0 and remains relevant to `2.20.0`. The GitHub advisory lists gun versions < 2.4.0 as affected, so the reported `gun 2.6.0` is outside that range. Treat this as a dependency-audit discrepancy to confirm on the Pi using the exact resolved lockfile, not as evidence that the entire tree is clear. See the [CVE-2026-43966 advisory](https://github.com/advisories/GHSA-w4f7-4cxr-rv3c), [CVE-2026-43969 advisory](https://github.com/advisories/GHSA-g2wm-735q-3f56), and [Hex cowlib advisories](https://hex.pm/packages/cowlib/advisories).

Migrations V6–V12 and repeatable V12 scripts are additive but Flyway applies pending migrations automatically when a new Java image starts. Before deploying that image: back up the real DB, restore to a separate database, verify counts/sums and Flyway state, run migration and integration tests on that restore, and verify catalog IDs against legacy inventory. Do not use the isolated test DB as a second persistent runtime database. Never enable an Elixir wallet or inventory writer while any Java command or market path can write the same rows. This pass did not run a production migration, change routing or gates, or enable Nostrum.

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
