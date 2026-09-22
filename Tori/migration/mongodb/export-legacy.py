"""Export Main Tori's stopped SQLite database to private MongoDB import files.

Run from the repository root. Output lives under gitignored data/mongo-import.
The SQLite source is never modified, and no Discord webhook is sent.
"""
import json
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "data" / "moderation.db"
LANGUAGES = ROOT / "data" / "languages.properties"
OUTPUT = ROOT / "data" / "mongo-import"
TABLES = (
    "bot_stats_context",
    "bot_events",
    "guild_prefixes",
    "moderation_cases",
)

if not SOURCE.is_file():
    raise SystemExit("Legacy moderation.db is missing; no import was performed.")
OUTPUT.mkdir(parents=True, exist_ok=True)
SNAPSHOT = OUTPUT / "legacy-snapshot.db"
with sqlite3.connect(f"file:{SOURCE.as_posix()}?mode=ro", uri=True) as source:
    with sqlite3.connect(SNAPSHOT) as snapshot:
        source.backup(snapshot)

counts = {}
with sqlite3.connect(f"file:{SNAPSHOT.as_posix()}?mode=ro", uri=True) as database:
    database.row_factory = sqlite3.Row
    for table in TABLES:
        rows = database.execute(f"SELECT * FROM {table}")
        count = 0
        with (OUTPUT / f"{table}.jsonl").open("w", encoding="utf-8", newline="\n") as out:
            for row in rows:
                document = dict(row)
                document["schema_version"] = 1
                out.write(json.dumps(document, ensure_ascii=False, separators=(",", ":")) + "\n")
                count += 1
        counts[table] = count

language_count = 0
with (OUTPUT / "guild_languages.jsonl").open("w", encoding="utf-8", newline="\n") as out:
    if LANGUAGES.exists():
        for line in LANGUAGES.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith(("#", "!")):
                continue
            guild_id, separator, language = line.partition("=")
            if not separator or not guild_id.isdigit() or not 17 <= len(guild_id) <= 20 or language not in ("en", "de", "nl"):
                raise SystemExit("Unsupported languages.properties entry; export stopped.")
            out.write(json.dumps({"guild_id": guild_id, "language": language, "schema_version": 1}) + "\n")
            language_count += 1
counts["guild_languages"] = language_count
(OUTPUT / "manifest.json").write_text(json.dumps(counts, indent=2) + "\n", encoding="utf-8")
print("Private import snapshot prepared:", counts)
