# Main Tori MongoDB

Main Tori uses the dedicated `mongodb` service in `compose.yaml`. The bot connects with
`MONGODB_URI` and database `tori_main`; Compose supplies both. The database files live
in Docker volume `tori-main_mongo-data`. The MongoDB port is bound only to
`127.0.0.1:27018` for local maintenance.

Create a private `.env` with `TORI_MONGO_PASSWORD` and the existing bot/Lavalink
settings. For a local Java run outside Compose, set `MONGODB_URI` to
`mongodb://tori:<password>@127.0.0.1:27018/?authSource=admin` and
`MONGODB_DATABASE=tori_main`. Never commit the password.

The active MongoDB collections are `bot_stats_context`, `bot_events`,
`guild_prefixes`, `moderation_cases`, and `guild_languages`. The bot creates
the necessary unique indexes at startup and fails startup if MongoDB is
unavailable. There is no fallback to SQLite in production.

## Move existing local data once

Stop Main Tori before exporting. Run `python migration/mongodb/export-legacy.py`
from the repository root. It makes a consistent SQLite snapshot and private
JSONL files under gitignored `data/mongo-import`. It never changes the source.
Start only MongoDB with `docker compose up -d mongodb`, then run
`powershell.exe -ExecutionPolicy Bypass -File migration/mongodb/import-legacy.ps1`.
The importer refuses a nonempty target database and checks collection counts.
Do not start the bot against an empty MongoDB before importing, or the bot
will create new lifecycle data and the import guard will stop.

After successful import, run `docker compose up -d --build bot`. Keep the
SQLite snapshot for rollback investigation; returning to SQLite after new
MongoDB writes would require a separate reverse migration.
