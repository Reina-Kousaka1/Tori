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

- V6 extends the existing V5 catalog and adds persisted rotations, fashion loadout, XP and career structures, and escrowed marketplace listings. V7 seeds an original, generic catalog spanning fashion, accessories, beauty, ballet, volleyball, cheer, consumables, collectibles, and seasonal items. Inserts preserve existing catalog edits and inventory.
- `shop.rotation` selects a weighted, deterministic, season-aware drop once per period and persists its item, price and stock snapshots. Reads of an existing period are stable. First creation is a guarded write and therefore unavailable when writes are disabled.
- `shop.purchase` locks the global wallet and rotation stock, validates ownership and configured level requirement, then debits credits, grants to the **existing** `economy_inventory`, writes wallet/inventory events and stores the idempotent result in one PostgreSQL transaction.
- `inventory.equip`/`inventory.unequip` use the same inventory ownership and V6 fashion slots. Existing rod/pickaxe/axe/wrench equipment remains in legacy tables.
- `progression.snapshot` reads persisted XP and career state. `progression.grant` accepts **only explicitly configured active** XP sources and uses persisted cooldowns, idempotency and career XP events. No production XP curve, reward values or activity XP sources are invented or enabled.
- `marketplace.browse` reads active user listings. Listing escrows an existing owned item; buying settles global wallet balances, delivers the escrowed item and writes both ledger legs atomically. Seller cancellation returns escrow, including after expiry. Java's existing dynamic market is separate and stays Java-owned.
- `profile.snapshot` now includes existing fish/mine/chop timestamps and the fashion loadout. It does not pretend that legacy activity rewards are Elixir-owned.
- Persona has a supervised transient mood process, deterministic seasonal overlay and semantic, neutral-fallback rendering API. Domain outcomes carry presentation keys but no Discord markup. Java commands/presence remain unaffected.

## Gates and explicit limitations

`WriteGate` permits new mutations only in explicit `test` mode against a connected `*_test` database. Its production allowlist remains restricted to the previously reviewed daily/transfer operations; V2 shop, XP, inventory, rotation and marketplace cannot be enabled in production through environment variables. `disabled` rejects every mutation even with `WRITE_ENABLED=true`. No Java command has been routed to the new operations.

The proposed Java-to-Elixir transfer for purchases, inventory, XP and marketplace is **not ready**. `PostgresCurrencyStore`, `PostgresMarketStore`, activity and shop commands still mutate wallet/inventory in Java. Nostrum is included as an **opt-in, off-by-default** gateway with a single read-only `tori-profile-preview` adapter. It registers no command and ignores existing JDA commands. Enabling it requires a separate test bot token and an explicit command-ownership review; do not run it beside JDA using Tori's production token. Existing Java presence remains the single status owner. Fishing/mining/chopping progression is read from existing timestamps only; rewards, tools, RNG and cooldown mutations remain Java-owned. Moderation remains Java-owned.

Migrations V6–V7 are additive but Flyway applies them automatically when a new Java image starts. Before deploying that image: back up the real DB, restore to a separate database, verify counts/sums and Flyway state, run migration and integration tests on that restore, and verify the catalog IDs against legacy inventory. Do not use the isolated test DB as a second persistent runtime database. Never enable an Elixir wallet or inventory writer while any Java command or market path can write the same rows.

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

The disposable container has no volume. Its DB is isolated from Compose's `tori_main`. The test harness applies the real Flyway V1–V7 SQL to a fresh empty `*_test` database and refuses any other target. Use a disposable password without URL-special characters for this command, or URL-encode it. After verification, remove only the explicitly named test container with `docker rm -f tori-economy-test-pg`; do not touch the Tori Compose database. These commands are **not yet verified in this Windows environment** because Mix, Gradle dependency download, Docker and a test PostgreSQL instance are unavailable here. Do not push or deploy this stack until these checks pass and test failures are fixed.

When Pi tests are green, inspect `git status`, rerun `git diff --check` and `git log --oneline origin/main..main`, then push normally with `git push origin main`. Never force-push. If formatting changes files, commit those changes and rerun tests first.

If the Pi must test this still-unpushed stack, transfer it as a Git bundle instead of pushing untested commits. On the development machine run `git bundle create tori-rewrite.bundle origin/main..main` and copy that file to the Pi. On a **clean** Pi `main` at the same `origin/main` base, run `git fetch /path/to/tori-rewrite.bundle main` then `git merge --ff-only FETCH_HEAD`; this does not create a feature branch or change production services. Verify `git status --short` first and preserve any local Pi changes before merging. Then run the checks above. A bundle is a transfer artifact, not a second repository or database.
