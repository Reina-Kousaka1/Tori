# Vorbereitung: SQLite → MongoDB

Status: Vorbereitung, kein aktives MongoDB-Backend. Der Bot verwendet weiterhin SQLite und seine bestehende Sprachdatei. Diese Dateien werden nicht vom Bot-Start geladen. Es wurde keine Verbindung aufgebaut und kein Datensatz übertragen. PostgreSQL ist nicht Teil dieser Vorbereitung.

## Ziel und Verbindung

`connection.env.example` beschreibt die später benötigten Werte. Eine echte URI ausschließlich in einer privaten Konfiguration speichern; nicht in Git, Logs oder Chat. Die Vorlage wird aktuell von keinem Bot-Code ausgewertet. Der offizielle Java-MongoDB-Treiber und die Repository-Implementierungen werden erst bei der tatsächlichen Migration eingebunden. Ein gesetztes `MONGODB_URI` schaltet den Bot daher noch nicht um.

Die MongoDB-Datenbank gehört genau einer Tori-Installation. Produktions- und Dev-Datenbanken müssen getrennt bleiben.

## Datenzuordnung (Schema-Version 1)

Feldnamen und Inhalte bleiben beim ersten Import unverändert. Jede SQL-Zeile wird ein Dokument mit zusätzlichem `schema_version: 1`. Alle Discord-IDs, Fall-IDs und Session-IDs bleiben **Strings**. Bestehende ISO-Zeitstempel bleiben zunächst Strings und werden exakt übernommen; optionale SQL-NULL-Werte werden BSON-null. Zähler bleiben Zahlen. Keine Message-Inhalte oder Zugangsdaten ergänzen.

| Quelle | Collection | Eindeutige Felder |
| --- | --- | --- |
| SQLite `bot_stats_context` | `bot_stats_context` | `bot_id`, `guild_id`, `channel_id` |
| SQLite `bot_events` | `bot_events` | `session_id`, `event_type` |
| SQLite `guild_prefixes` | `guild_prefixes` | `guild_id` |
| SQLite `moderation_cases` | `moderation_cases` | `guild_id`, `case_id` |
| `BOT_DATA_DIR/languages.properties` | `guild_languages` | `guild_id` |

Für Spracheinstellungen: Properties-Schlüssel → `guild_id`, Wert → `language` (`en`, `de`, `nl`). Nicht konfigurierte Server erhalten weiterhin den konfigurierten Standard; keine künstlichen Standarddatensätze anlegen. Prefix-Standard `T.` und alle vorhandenen Serverwerte erhalten. Statistik-Historie betrifft wie bisher die Installation.

## Vorbereitete Indexdatei

`prepare-indexes.js` ist für **mongosh** vorgesehen. Sie erstellt auf ausdrücklichen Aufruf Collections/Indizes, importiert aber keine Daten. Sie liest nur `MONGODB_DATABASE` und verlangt die explizite Freigabe `TORI_MONGO_PREPARE=YES`. Die Verbindung wird separat über mongosh eingerichtet. Nicht beim Bot-Start aufrufen. Unique-Indizes verhindern doppelte natürliche Schlüssel; es werden keine TTL-Indizes oder automatischen Löschungen eingerichtet.

## Späterer Umzug in Etappen

1. Repository-Schnittstellen für Stats/Lifecycle, Prefixe, Moderation und Sprache definieren. SQLite-Implementierungen zuerst unverändert hinter diese Schnittstellen setzen. `PrefixSettings` greift derzeit direkt auf JDBC zu; diese Kopplung vor dem Umschalten lösen.
2. MongoDB-Treiber und entsprechende Implementierungen ergänzen. Verbindungsfehler dürfen keinen stillen Wechsel auf eine andere Datenbank auslösen. Den aktiven Speicher explizit pro migriertem Bereich konfigurieren.
3. Zunächst Stats-Kontext **zusammen mit** Lifecycle-Lesen/-Schreiben umstellen: `/stats` liest aktuell die Ereignisse aus derselben Datenbank. Prefixe und Moderation können zunächst bei SQLite bleiben.
4. In einer Testdatenbank einen wiederholbaren Import bauen: Upserts mit den oben genannten natürlichen Schlüsseln, keine zufälligen Import-Schlüssel. Bestehende Moderationsfälle nur übernehmen, niemals Webhooks erneut senden. Tabellen-/Collection-Zählungen und vollständige Datensatzvergleiche prüfen, einschließlich Umlauten, NULL-Werten, Zeitstempeln und langen IDs.
5. Vor dem finalen Export Bot-Schreibzugriffe stoppen und eine konsistente SQLite-Sicherung erstellen. Bei WAL-Betrieb nicht nur die laufende `.db`-Datei kopieren. Sprachdatei ebenfalls sichern. Import mit diesem Snapshot wiederholen, vergleichen und erst dann den betreffenden Bereich umschalten. Kein unkontrolliertes paralleles Schreiben in beide Systeme.
6. Slash- und Prefix-Commands, Restart/Lifecycle sowie Sprach- und Owner-Regeln gezielt prüfen. SQLite-Sicherung unverändert aufbewahren. Vor Rückkehr zu SQLite nach neuen MongoDB-Schreibvorgängen zuerst diese Änderungen zurückführen; einfaches Zurückschalten würde Daten verlieren.

Noch zu implementieren: Java-Backend, Export/Import, Vergleichswerkzeug und Umschaltung. Diese Vorbereitung behauptet keine bereits vorhandene Migration.

Offizielle Referenzen: [Verbindungsformate](https://www.mongodb.com/docs/manual/reference/connection-string/), [eindeutige Indizes](https://www.mongodb.com/docs/manual/core/index-unique/).
