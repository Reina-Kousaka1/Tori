# Shared DBX operations for Tori and Noélia

This directory defines one optional DBX Web operations service for inspecting
the separate Tori and Noélia PostgreSQL instances. It does not replace either
bot, PostgreSQL, Flyway, application backups, or migration ownership. No bot
runtime code or database migration is changed by DBX.

## Topology and versions

- DBX Web is pinned to `t8y2/dbx:v0.6.54`, with `linux/arm64` selected for the
  Raspberry Pi. The published image is multi-architecture.
- DBX Packages `0.4.131` is a separate CLI/MCP release. The optional
  `install-packages.sh` downloads only the Linux glibc ARM64 CLI and MCP assets
  and verifies their published SHA-256 digests before installing under the
  current user's `~/.local/opt/dbx/` directory.
- Tori PostgreSQL joins the dedicated Docker network `dbx-ops` as
  `tori-postgres`. Noélia contributes its own PostgreSQL service to that same
  network with the alias `noelia-postgres` using
  [`Noélia's DBX overlay`](https://github.com/Reina-Kousaka1/Noelia/blob/main/ops/dbx/compose.dbx.override.yaml).
- The DBX Web port is published only on `127.0.0.1`; use an SSH tunnel for
  remote browser access. The DBX service has no direct public bind.
- Persistent DBX state and its externally managed encryption key live
  separately from both bot database volumes. `/app/backups` is a separate
  persistent DBX backup volume.

The DBX Web login and encryption key are supplied through the local Compose
environment and Docker secret file. PostgreSQL role passwords are never placed
in this repository, command arguments, or shell history: DBX's supported
connection setup stores them encrypted in its persistent application data,
protected by the stable encryption key. No supported environment-based
connection-profile provisioning was found, so do not script against DBX's
internal database/API. If policy requires PostgreSQL credentials to remain
outside DBX's encrypted store as well, leave those connections unconfigured.

```text
DBX Web (127.0.0.1:4224; dbx-ops)
  ├── tori-postgres:5432   → Tori's existing PostgreSQL volume
  └── noelia-postgres:5432 → Noélia's existing PostgreSQL volume
```

## Repository files

- `compose.yaml`: one pinned, ARM64 DBX Web service; loopback-only port; a
  required externally managed encryption-key file; separate persistent DBX
  state and backup volumes. MCP is not forced on by the Compose file.
- `.env.example`: non-secret template for the DBX Web login and external key
  path. The real `.env` must remain local and mode `0600` on the Pi.
- `tori-readonly-role.sql`: optional manual role bootstrap for the existing
  `tori_main` database. It is not a Flyway migration and is not run by the bot.
- `install-packages.sh`: optional checksum-pinned CLI/MCP installation for
  64-bit Raspberry Pi OS with glibc. It does not install system-wide or create
  services.

## Before the first Pi change

This repository does not assume that DBX is absent or that particular Docker
volumes/networks are unused. On the Pi, first inspect the current workspaces,
branches, dirty Git state, Compose project names, running containers, networks,
PostgreSQL port bindings and actual named volumes. If DBX already exists, stop
and identify its data volume and key before adopting or starting this Compose
project. Never run `docker compose down -v`, remove volumes, reset databases,
or overwrite an existing DBX encryption key.

The two PostgreSQL services will be recreated when their Compose network
configuration is applied. Schedule that as a maintenance action: the existing
named data volumes must resolve to their current names before running Compose.
Do not use `down`/`down -v`; `up -d postgres` is the only service targeted by
the bot-repository steps below.

## Deployment steps for a later, separately authorized Pi rollout

These commands are documentation only; they have not been run here.

1. Confirm both Git workspaces are clean and on the intended production branch.
   Pull Tori `main`. Pull Noélia `main` only after the DBX-only PR for its
   network overlay has been reviewed and merged. Do not pull the current local
   Noélia README branch as a substitute.
2. Inspect `docker compose ls`, `docker ps`, `docker network ls`,
   `docker volume ls`, and `docker inspect` for both PostgreSQL containers.
   Confirm the existing volume names and current Compose project names before
   any `compose up` that could recreate a database container.
3. In Tori's `Tori/` directory, validate the resolved config, then apply only
   the PostgreSQL service so Compose creates `dbx-ops` and attaches
   `tori-postgres`:

   ```sh
   docker compose -f compose.yaml config --quiet
   docker compose -f compose.yaml up -d postgres
   ```

   Expect a brief PostgreSQL restart when the extra network is first applied.
   Check logs and readiness before continuing. This must reuse the already
   verified Tori volume; it must not initialize a new one.
4. In Noélia's repository root, validate and apply its overlay to only the
   existing `postgres` service:

   ```sh
   docker compose -f compose.yaml -f ops/dbx/compose.dbx.override.yaml config --quiet
   docker compose -f compose.yaml -f ops/dbx/compose.dbx.override.yaml up -d postgres
   ```

   This also can recreate PostgreSQL and briefly interrupt Noélia. Verify the
   existing Noélia named volume is unchanged before running it. The overlay
   does not publish a PostgreSQL host port or attach the Noélia bot to DBX's
   network.
5. Review this DBX Compose file and `.env.example`. Create the DBX operations
   directory on the Pi, copy `.env.example` to `.env`, set a unique strong
   `DBX_WEB_PASSWORD`, and set `DBX_SECRET_KEY_HOST_FILE` to a root-protected
   file created once with a cryptographically random key. Restrict `.env` and
   the key file to the service operator. Never print either secret or replace
   the key after DBX has encrypted credentials.
6. Before starting the service, confirm the chosen loopback port is free and
   that the external `dbx-ops` network contains only the intended DBX and
   PostgreSQL endpoints. From `Tori/ops/dbx/`, run `docker compose
   --env-file .env config --quiet`, then `docker compose --env-file .env pull`
   and `docker compose --env-file .env up -d`. Do not start if an existing DBX
   deployment or its volumes have not been reconciled first.
7. Open DBX only through an SSH tunnel, for example
   `ssh -L 4224:127.0.0.1:4224 toriaa@raspberrypi`, then browse
   `http://localhost:4224`. Authenticate with the locally held
   `DBX_WEB_PASSWORD`.
8. Create one DBX connection named exactly `tori-production-readonly`:
   PostgreSQL host `tori-postgres`, port `5432`, database `tori_main`, and
   login `tori_dbx_readonly`. Set the DBX connection's Read Only and Production
   Protection options. Do not use the bot's `tori` login.
9. Create a separate `noelia-production-readonly` connection with host
   `noelia-postgres`, port `5432`, the actual existing Noélia production
   database name (read locally from the Pi configuration; do not copy it into
   this repository), and login `noelia_dbx_readonly`. Set the same DBX
   protections. Do not use the Noélia bot/database bootstrap login.
10. Provision the two database roles only after reviewing the corresponding
    role SQL and confirming the actual schema owner. The password is entered
    through `psql`'s non-echoing `\password` prompt, then entered directly
    into the authenticated DBX connection form; it is never put in shell
    arguments or Git. For Tori, run the checked-in SQL from the `Tori/`
    directory with `docker compose exec -T postgres psql -U tori -d tori_main
    < ops/dbx/tori-readonly-role.sql`, then open an interactive psql session
    and enter `\password tori_dbx_readonly`. Verify each role has no write
    grants, no role memberships, no ownership and no elevated attributes.
11. In DBX Settings → MCP, leave MCP disabled until both connections have been
    verified. If enabling it later, use the UI-managed token over the existing
    loopback-only listener; allow only the two exact connections/databases,
    set global and per-connection execution to **Read only**, and allow only
    metadata and query tools. Do not allow future connections, write tools,
    broad execution, or public Host/Origin values. DBX's MCP permission policy
    is a second guard; the PostgreSQL roles remain the actual authorization
    boundary.
12. Create `/app/backups/tori` and `/app/backups/noelia` inside the DBX
    container as its service user with
    `docker compose --env-file .env exec dbx sh -c 'mkdir -p /app/backups/tori /app/backups/noelia'`,
    then configure separate DBX backup schedules to those paths. Persisted
    DBX state (`dbx-data`) and its external encryption-key file form one
    recovery set; back them up together through a protected off-host process.
    The SQL backup volume does not replace either bot's existing backup
    process. Review retention and arrange an independent off-host copy of SQL
    backups too. Test a restore only into a disposable isolated database,
    never production.

## DBX Packages installer

On the Pi, run this optional host tool installer as the normal service user
from the checked-out Tori repository:

```sh
bash Tori/ops/dbx/install-packages.sh
```

It supports Linux `aarch64`/`arm64` with glibc (not Alpine/musl), downloads the
two native `0.4.131` assets from the official release, checks the exact release
SHA-256 values, and installs under `~/.local/opt/dbx/packages-v0.4.131/`.
It refuses to overwrite an existing version directory. Use the MCP package in
stdio mode with `DBX_WEB_URL=http://localhost:4224` and a password supplied by
the local MCP client's secret/environment facility. The CLI can use the same
DBX Web URL/password. Keep the SSH tunnel open while either tool is running.
The native DBX Web `/mcp` endpoint is an alternative and does not require this
standalone package installation.

## Validation boundary

`docker compose config` and YAML validation can be run locally without starting
services. No production DB connection, schema migration, backup, restore, Pi
command, container start, or DBX installation is part of this repository
change.

## Official references

- [DBX Web v0.6.54 release and ARM64 assets](https://github.com/t8y2/dbx/releases/tag/v0.6.54)
- [DBX Packages v0.4.131 release](https://github.com/t8y2/dbx/releases/tag/packages-v0.4.131)
- [DBX Docker and credential-key handling](https://github.com/t8y2/dbx#self-hosted-docker)
- [DBX Web password and environment settings](https://github.com/t8y2/dbx/blob/main/docs/content/docs/web-api.mdx)
- [MCP permissions, scopes, and deployment secrets](https://github.com/t8y2/dbx/blob/main/docs/content/docs/mcp.mdx)
- [DBX scheduled backups and volume requirements](https://github.com/t8y2/dbx/blob/main/docs/background-database-backups.md)
