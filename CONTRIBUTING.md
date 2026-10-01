# Contributing to Tori

Thanks for considering a contribution. Keep changes focused, follow the current ownership boundaries, and include the checks relevant to the affected code. Read the [Code of Conduct](CODE_OF_CONDUCT.md) before participating; [SUPPORT.md](SUPPORT.md) explains the available help and reporting paths.

## Getting started

Clone the repository and enter the application directory:

```sh
git clone https://github.com/Reina-Kousaka1/Tori.git
cd Tori/Tori
```

The versions below come from the Gradle, Mix and Docker configuration in this repository:

- **Java:** JDK 21. Gradle uses the checked-in wrapper; `Tori/gradle/wrapper/gradle-wrapper.properties` currently pins Gradle 9.7.1.
- **Elixir:** Elixir 1.18.x, as constrained by `Tori/economy-v2/mix.exs`. The Economy API Docker image pins Elixir 1.18.5 with Erlang/OTP 27.3.4.18.
- **PostgreSQL:** PostgreSQL 17 is the Compose version. Database integration tests need a separate, disposable database.
- **Docker:** Docker Engine/Desktop with Docker Compose is used for the repository's container setup. It is optional for Java-only or Elixir-only unit tests.
- **Mix:** Install with the Elixir toolchain. Fetch dependencies from `Tori/economy-v2/` with `mix deps.get`.

No global Gradle installation is needed. On Windows, use `gradlew.bat` in place of `./gradlew`.

## Environment and secrets

`Tori/.env.example` documents the Compose/runtime environment. For local work, copy it to `Tori/.env` and set values for a dedicated test bot and local services as needed. Docker Compose reads `.env`; it is not a shell script and should not be loaded with `source`.

Never commit `.env`, bot tokens, webhook credentials, database passwords, API secrets, production connection strings, or private Discord data. Do not paste secret values into issues, pull requests, logs, screenshots, or test output. If a credential is exposed, revoke or rotate it immediately.

## Development workflow

1. Create a focused branch or fork from the current `main` and make one logical change at a time.
2. Read the relevant domain documentation before changing behavior.
3. Respect Java/Elixir ownership and PostgreSQL as the one persistent source of truth. Do not duplicate wallet, inventory, career, profile, or progression state.
4. Add or update regression tests for behavior changes without weakening existing checks.
5. Run the relevant formatting, compile, test and build checks below before opening a pull request.
6. Fill in [the pull request template](.github/pull_request_template.md), including database and security details where applicable.

Do not perform production deployments, database writes, migration runs, or routing/writer cutovers as part of local validation.

## Architecture rules

- **Java/JDA** owns the established Java Discord runtime, existing Java commands, music/Lavalink, technical Discord presence writing, and the legacy economy paths that remain active.
- **Elixir/OTP/Nostrum** owns its explicitly assigned internal domain services and main-bot interactions. It must not register or handle Java-owned commands.
- **PostgreSQL** is the authoritative persistent database shared by the applications. Tests may use isolated databases; production and test data must not be mixed.
- **Presence** has one actual Discord writer: Java/JDA. Elixir may provide semantic context but must not set Discord presence.
- Preserve stable existing components. Do not rewrite working Java systems solely to increase Elixir usage or vice versa.
- Respect the Economy API routing defaults and WriteGate. Implemented code does not imply production write authorization.

See [the root README](README.md), [Economy V2 documentation](Tori/economy-v2/README.md), [rewrite status](Tori/docs/ELIXIR-REWRITE-STATUS.md), and [ticket/order documentation](Tori/docs/TICKETS-AND-ORDERS.md) for current boundaries.

## Database migrations

- Treat every already-applied versioned migration as immutable.
- Make schema changes with a new, additive Flyway migration; do not edit an applied migration to change production state.
- Do not delete or reset production data to simplify development or tests.
- Verify both a fresh isolated database and the supported upgrade path for schema changes.
- Never point tests at production. The Elixir integration test harness accepts only an explicit PostgreSQL database whose name ends in `_test`.

## Testing

### Java

From `Tori/`:

```sh
./gradlew test
./gradlew clean test build --no-daemon
```

The live PostgreSQL tests are opt-in. They require `TORI_POSTGRES_LIVE_TEST=YES` and a dedicated database URL named exactly `tori_test` in `TORI_TEST_DATABASE_URL`, plus the corresponding local test database credentials. Java-to-Elixir live tests also require a loopback `TORI_ECONOMY_TEST_API_URL` and local `TORI_ECONOMY_TEST_API_SECRET`; write-path cases require `TORI_ECONOMY_TEST_WRITE_ENABLED=YES` and a test-only WriteGate. Never reuse production credentials or URLs.

### Elixir

From `Tori/economy-v2/`:

```sh
mix deps.get
mix format --check-formatted
mix compile --warnings-as-errors
mix test
```

Without `TORI_ECONOMY_TEST_DATABASE_URL`, database integration cases are skipped. To run them, set that variable to a reachable, disposable PostgreSQL database ending in `_test`. The test helper prepares/verifies the isolated test schema from the repository's Flyway migrations; do not use production or a database containing valuable data.

### Docker and Compose

From `Tori/`, with a local `Tori/.env` available:

```sh
docker compose config --quiet
```

This validates the Compose configuration without starting containers. Starting the full stack also starts the bot; use only a dedicated test bot, local services and non-production database configuration.

There are currently no GitHub Actions workflow files in this repository, so these checks are not run by a repository CI workflow yet. Run the checks relevant to each change locally and report their actual results in the pull request.

## Pull requests

External contributors should use a normal topic branch or fork and open a pull request against `main`; do not push directly to `main`. Keep the pull request focused, describe user-visible changes, list the checks actually run, and call out any migration, command ownership, security, or compatibility impact. Do not claim skipped checks passed.
