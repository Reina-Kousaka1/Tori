# One-time MongoDB to PostgreSQL migration

Main Tori's normal runtime now uses PostgreSQL. The MongoDB Java driver exists only in the isolated `mongoMigration` Gradle source set, and is loaded only by the explicit `migrateMongoToPostgres` task. The migration reads the supported Tori collections and never updates or deletes source documents. It refuses a PostgreSQL target containing application rows and writes a JSONB archive of every copied source document in the same transaction as the relational import.

## Important before starting

- The Raspberry Pi's live MongoDB data has not been inspected from this development environment. Local SQLite/JSONL migration snapshots are not evidence about what is in the Pi's Mongo volume. Check the source database and counts on the machine/network where it is actually available.
- Do not downgrade MongoDB, remove its container/volume, run `docker compose down -v`, or point this importer at a production PostgreSQL database that has already received bot writes.
- Keep Main Tori stopped while taking the final source copy so the export is consistent. Preserve the original Mongo volume and a verified backup until PostgreSQL has been checked in production.
- Do not expose MongoDB or PostgreSQL publicly to make this work. Use a private LAN/VPN or SSH tunnel with local-only port bindings. Never put passwords in Git or paste them into logs.
- For deployment on the Pi, verify it is running a 64-bit OS (`uname -m` should report `aarch64`). PostgreSQL's official image publishes ARM64 builds, and this bot image now selects ARM64 or amd64 yt-dlp accordingly; 32-bit Pi OS is not covered by this Docker build.
- The importer expects the existing Mongo database named `tori_main` and these Tori collections: `bot_stats_context`, `bot_events`, `guild_prefixes`, `moderation_cases`, `guild_languages`, `guild_orders`, `guild_order_status_events`, `guild_tickets`, `guild_ticket_events`, `guild_ticket_transcripts`, `guild_ticket_order_config`, and `guild_ticket_order_counters`. A nonempty unknown collection makes it abort for review rather than silently omit it.

## Safe staged workflow

1. On the source host, stop the bot cleanly and make a database backup using the MongoDB tools available for that host/version. Keep the original volume intact. Record collection counts and retain the backup somewhere separate. For a Pi source, use its currently installed MongoDB/container tooling; do not try to install or upgrade MongoDB 8 as part of this migration.
2. Restore a *copy* of that backup into a temporary, private MongoDB instance on a compatible migration machine, or provide a private network route to the unchanged source. Bind any temporary service to localhost only and remove it only after migration verification. Never use the production source as a scratch database.
3. Configure a fresh PostgreSQL database with the credentials in `.env` (`TORI_POSTGRES_PASSWORD`). Start only PostgreSQL, not the bot. The Compose service maps its port to `127.0.0.1:5432`; the bot's normal config is `TORI_DATABASE_URL`, `TORI_DATABASE_USER`, and `TORI_DATABASE_PASSWORD`.
4. In a private terminal session, provide these migration-only settings without committing them or printing them:

   - `TORI_MONGO_SOURCE_URI` — private URI to the unchanged source/copy, including the correct auth settings.
   - `TORI_MONGO_SOURCE_DATABASE=tori_main` (optional; this is the default).
   - `TORI_DATABASE_URL=jdbc:postgresql://localhost:5432/tori_main`, `TORI_DATABASE_USER=tori`, and `TORI_DATABASE_PASSWORD` for the fresh PostgreSQL target.

   Then run `./gradlew migrateMongoToPostgres` (Windows: `./gradlew.bat migrateMongoToPostgres`). The task applies the Flyway schema, checks the target is empty, copies and maps every supported document, and commits the entire import atomically. An error rolls back the SQL transaction; the source is read-only throughout.
5. Compare the per-collection source counts from the backup with the imported relational counts and `mongo_import_archive` counts. Check guild settings, moderation cases, ticket/order totals, status histories, transcript records, and reciprocal ticket/order links. The task prints only the total copied count; use read-only SQL and the preserved archive for detailed reconciliation.
6. Only after manual reconciliation, back up PostgreSQL, deploy the bot configured for that database, and verify commands, tickets, order buttons, moderation logs, and restart persistence in a private test guild. Keep the Mongo backup/volume unchanged through the agreed rollback window.

Example read-only archive count query:

```sql
SELECT collection_name, count(*)
FROM mongo_import_archive
GROUP BY collection_name
ORDER BY collection_name;
```

Example application table counts:

```sql
SELECT 'bot_events' AS table_name, count(*) FROM bot_events
UNION ALL SELECT 'moderation_cases', count(*) FROM moderation_cases
UNION ALL SELECT 'guild_orders', count(*) FROM guild_orders
UNION ALL SELECT 'guild_tickets', count(*) FROM guild_tickets
UNION ALL SELECT 'guild_ticket_transcripts', count(*) FROM guild_ticket_transcripts;
```

This checkout cannot access or verify the Raspberry Pi's Mongo data or execute the live migration. Do not treat a successful local build as a completed data migration. The old Mongo volume is intentionally not declared in the new Compose file; Docker may report it as orphaned, but it remains untouched unless someone explicitly removes it.
