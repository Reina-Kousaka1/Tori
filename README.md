# Discord Music + Moderation Bot (Java 21)

Vollständig auf Java umgestellter Anwendungscode mit Gradle (Groovy DSL), JDA 6.5.0,
Lavalink 4.2.2 mit DAVE und LavaSrc 4.8.3. Der Lavalink-Client bringt intern weiterhin
seine Kotlin-Laufzeit mit; du musst weder Kotlin installieren noch Kotlin-Code bearbeiten.
Bot-Antworten, Hilfe, Eingabefehler, Moderationsmeldungen und Webhook-Modlogs unterstützen Deutsch,
Englisch und Niederländisch.

## Start mit Docker

1. Docker Desktop mit Linux-Containern bzw. Docker Engine mit Compose installieren.
2. Im [Discord Developer Portal](https://discord.com/developers/applications) eine Application mit Bot anlegen.
3. Unter OAuth2 → URL Generator `bot` und `applications.commands` wählen. Bot einladen.
4. `.env.example` nach `.env` kopieren und `DISCORD_TOKEN` sowie ein eigenes `LAVALINK_PASSWORD` eintragen.
5. Optional `DISCORD_GUILD_ID` auf die ID deines Testservers setzen. Die Slash-Commands werden dann dort
   registriert; ohne ID global. Globale Änderungen können verzögert sichtbar werden.
6. Für Spotify-Suche zusätzlich `SPOTIFY_CLIENT_ID` und `SPOTIFY_CLIENT_SECRET` aus deiner
   [Spotify Developer App](https://developer.spotify.com/dashboard) eintragen.
7. Im Projektordner starten:

```sh
docker compose up -d --build
docker compose logs -f bot lavalink
```

Nach einer Konfigurationsänderung erneut `docker compose up -d --build` ausführen.
Beenden: `docker compose down`. `.env` wird nicht ins Docker-Image oder ZIP aufgenommen.
Lavalink ist ausschließlich im internen Compose-Netz erreichbar.

## Discord-Berechtigungen

### Befehle im Slash-Menü registrieren

Alle 23 Befehle, einschließlich `/ping` und `/status`, werden beim Bot-Start bei Discord registriert.
Die Konsole bestätigt anschließend die von Discord akzeptierten Befehle und den Registrierungsbereich.
Du kannst die Liste auch separat aktualisieren, ohne den Bot oder Lavalink zu starten:

```powershell
.\gradlew.bat registerCommands
```

Dafür werden nur `DISCORD_TOKEN` und optional `DISCORD_GUILD_ID` aus der Konfiguration benötigt.
Mit gesetzter `DISCORD_GUILD_ID` werden die Befehle für genau diesen Server aktualisiert; ohne ID global.
Die erfolgreiche Registrierung allein startet keinen Bot-Prozess. Zum Ausführen der Befehle muss der Bot laufen.
In einem Textkanal des Servers `/` eingeben und den Bot auswählen. Discord zeigt nur Befehle an,
die deine Kanal-, Rollen- und App-Berechtigungen erlauben; Moderationsbefehle behalten ihre Einschränkungen.
`/status` bleibt zur Ausführung auf den Bot-Inhaber beschränkt. Änderungen am Java-Code oder den Optionen
werden erst durch eine erneute Registrierung an Discord übertragen.

Referenz: [Discord Slash-Commands und Berechtigungen](https://docs.discord.com/developers/interactions/application-commands).

### Benötigte Rechte

Musik: **View Channels**, **Send Messages**, **Connect**, **Speak**.
Moderation nach Bedarf: **Kick Members**, **Ban Members**, **Moderate Members**,
**Manage Messages**, **Read Message History**, **Manage Channels**.
Die höchste Bot-Rolle muss über den moderierten Rollen stehen. Administratorrechte sind nicht erforderlich.
Für `/snipe` muss Message Content Intent im Discord Developer Portal unter Bot aktiviert sein. Server Members bleibt deaktiviert.

Mod-Befehle besitzen passende Default-Berechtigungen. Zusätzlich prüft jede Ausführung die
aktuellen Moderatorrechte, Bot-Rechte und bei Mitgliedsaktionen beide Rollenhierarchien.
Der Serverinhaber, der ausführende Moderator und der Bot selbst sind geschützt.
Timeouts gegen Administratoren werden abgelehnt. Kanal-Overrides werden bei `/purge` und `/slowmode` berücksichtigt.
Antworten sind im Kanal für alle sichtbar; Gründe enthalten die Moderator-ID im Discord-Audit-Log.

## Sprache und Hilfe

| Befehl | Funktion |
| --- | --- |
| `/language` | Aktuelle Serversprache anzeigen |
| `/language code:de` | Deutsch speichern |
| `/language code:en` | Englisch speichern |
| `/language code:nl` | Niederländisch speichern |
| `/help` | Alle 23 Befehle mit kurzer Verwendung anzeigen |
| `/ping` | WebSocket- und Bot-REST-Latenz in Millisekunden anzeigen |
| `/status` | Einstellungen des rotierenden Bot-Status anzeigen (nur Bot-Inhaber) |
| `/status action:start texts:Musik \| /help interval_ms:120000` | Statustexte alle 120000 ms wechseln |
| `/status action:stop` | Eigene Rotation stoppen und Standardrotation wiederherstellen |

`/ping` ist für alle Servermitglieder verfügbar. WebSocket zeigt die zuletzt gemessene
Heartbeat-Latenz; Bot (REST) misst eine neue Anfrage an die Discord-API. Beide Werte sind in `ms`.
`/status` verwaltet den benutzerdefinierten Statustext des Bots. Mit `action:start` eine neue Rotation
starten oder die laufende ersetzen: `texts` enthält 1 bis 10 Texte, getrennt mit `|` oder Zeilenumbrüchen.
Leere Einträge und überflüssige Trennzeichen werden ignoriert. Jeder Text darf höchstens 128 Zeichen haben;
bei längeren Texten nennt die Fehlermeldung den betroffenen Eintrag und seine Länge.
Ein einzelner Text setzt einen festen Status, beispielsweise `/status action:start texts:Paying w my bbfs!`.
Ab zwei Texten wechselt der Bot zwischen den Einträgen. Das Intervall wird mit `interval_ms` in Millisekunden
gesetzt: 30000 bis 3600000, standardmäßig 120000. Beispiel in Discord:

```text
/status action:start texts:🎵 Musik | /help für Befehle | Bereit zum Abspielen interval_ms:120000
```

Ohne Optionen (oder mit `action:show`) zeigt `/status` die Anzahl der Texte und das aktuelle Intervall.
`action:stop` beendet die eigene Rotation und stellt die beiden Standardstatus wieder her. `texts` und `interval_ms` sind nur
bei `action:start` erlaubt. Es gibt eine gemeinsame Rotation für den gesamten Bot, unabhängig vom Server.
Zwischen Statusänderungen liegen mindestens 30000 ms; auch schnelles Neustarten oder Stoppen
umgeht diese Pause nicht. Die neueste Änderung wird innerhalb der nächsten 30000 ms angewendet,
sofern der Bot mit Discord verbunden ist. Die Rotation gilt bis zum Stoppen oder Bot-Neustart;
nach einem Neustart erneut mit `action:start` aktivieren.
Nur die mit `BOT_OWNER_ID` in `.env` konfigurierte Person darf `/status` ausführen.
Serverinhaber und Administratoren erhalten dadurch keinen Zugriff. Die Antworten sind im Kanal sichtbar. Nach dem Update den Bot neu starten,
damit die neuen Slash-Commands registriert werden.

Referenz: [Discord-Limit für Presence-Updates](https://docs.discord.com/developers/events/gateway-events#update-presence).

Die Sprachwahl gilt für den ganzen Server. Anzeigen und Hilfe sind für alle Mitglieder verfügbar;
zum Ändern ist **Manage Server / Server verwalten** erforderlich. Die Bestätigung erscheint sofort
in der neuen Sprache. Musik, Moderation, Fehlermeldungen und neue Modlogs folgen dieser Einstellung.
Nutzernamen, Songtitel und selbst eingegebene Gründe werden unverändert übernommen.

Ohne gespeicherte Auswahl gilt `BOT_DEFAULT_LANGUAGE=de` aus `.env` (alternativ `en` oder `nl`).
Die Servereinstellungen liegen in `data/languages.properties`; Docker Compose speichert sie im
Volume `bot-data`, sodass sie normale Neustarts und Container-Neubauten überstehen.
`docker compose down -v` entfernt dieses Volume und damit die gespeicherten Sprachen.
Bei lokalem Java-Start kann `BOT_DATA_DIR` den Datenordner ändern. Nur einen Bot-Prozess je Datenordner betreiben.
Eine fehlgeschlagene Speicherung lässt die bisherige Sprache aktiv und wird dem Nutzer gemeldet.

Die Namen `/play`, `/help`, `/language` und die Optionsnamen bleiben in allen Sprachen gleich.
Die Beschreibungen der Slash-Commands und Optionen sind ebenfalls übersetzt; Discord wählt diese anhand
der persönlichen Discord-Sprache. Das ist unabhängig von der per `/language` festgelegten Antwortsprache.
Technische Konsolenmeldungen und diese Startanleitung werden nicht durch den Serverbefehl umgestellt.

Übersetzungstexte stehen zentral unter `src/main/resources/i18n/de.properties`, `en.properties` und
`nl.properties`. Platzhalter wie `%s` beim Bearbeiten erhalten. Tests prüfen die Vollständigkeit
aller drei Kataloge, Befehlsbeschreibungen und die Länge der Hilfe.

## Musik

| Befehl | Funktion |
| --- | --- |
| `/play query:Suchbegriff` | YouTube-Suche, erstes Ergebnis über yt-dlp und Lavalink abspielen |
| `/play query:Suchbegriff source:Spotify` | Spotify-Suche über LavaSrc |
| `/play query:Spotify-Link` | Titel, Album oder Playlist übernehmen |
| `/play query:spotify:track:ID` | Spotify-URI; auch `album` und `playlist` |
| `/play query:SoundCloud-oder-Bandcamp-Link` | Bestehende Link- und Playlist-Unterstützung |
| `/queue` | Aktueller Titel und nächste zehn Titel |
| `/skip`, `/pause`, `/resume` | Wiedergabe steuern |
| `/volume percent:0-100` | Lautstärke |
| `/stop` | Wiedergabe stoppen und Queue leeren |
| `/leave` | Queue leeren und Sprachkanal verlassen |

Nur Nutzer im selben normalen Sprachkanal dürfen die Wiedergabe steuern.
Die Warteschlange ist pro Server getrennt und speichert maximal 200 wartende Titel.
Bei leerer Queue bleibt der Bot bis `/leave` im Kanal. Neustart oder Trennen leert die Queue.

### Was Spotify hier bedeutet

Spotify liefert weiterhin Metadaten über LavaSrc. Der Java-Bot sucht Titel und Künstler über
`search.list` der offiziellen YouTube Data API v3 und prüft mit `videos.list` Titel, Künstler
und Laufzeit der bis zu fünf Treffer. Lavalink spielt anschließend die ausgewählte YouTube-URL
über `youtube-plugin:1.18.2`. Ein Spotify-Audiostream wird dabei nicht übertragen.
Andere Versionen, Covers oder Remixe sind trotz Abgleich möglich.

Die Auflösung erfolgt erst, wenn ein Titel an die Reihe kommt. Erfolgreiche Treffer werden
sechs Stunden lang im Speicher gespeichert (höchstens 512 Einträge). Eine neue Suche benötigt
je einen `search.list`- und `videos.list`-Aufruf. Bei API-Limits werden weitere Abfragen fünf
Minuten ausgesetzt; ein ausgeschöpftes Tageskontingent wird dadurch nicht zurückgesetzt.

Bei fehlenden Treffern oder Ladefehlern pausiert die Wiedergabe. Der fehlgeschlagene Titel wird
verworfen; die übrige Warteschlange bleibt erhalten. Mit `/skip` geht es zum nächsten Titel.
Fehler während eines Befehls erscheinen in dessen Antwort, Fehler beim automatischen Titelwechsel
im Bot-Log. Spotify-Titel werden nicht stillschweigend über eine andere Quelle abgespielt.

#### YouTube einrichten

1. In deinem [Google-Cloud-Projekt](https://console.cloud.google.com/apis/library/youtube.googleapis.com)
   **YouTube Data API v3** aktivieren und unter „APIs & Dienste → Anmeldedaten“ einen API-Schlüssel erstellen.
2. In der `.env` im gestarteten Projektordner ergänzen:

   ```dotenv
   YOUTUBE_API_KEY=dein_api_schluessel
   ```

   Der Schlüssel gehört zum **Bot**, nicht zu Lavalink. Compose reicht ihn automatisch weiter.
   Die verschachtelte Projektkopie benötigt beim direkten Gradle-Start die Umgebungsvariable
   `$env:YOUTUBE_API_KEY = 'dein_api_schluessel'`.
3. Bot und Lavalink mit der neuen Konfiguration starten:

   ```powershell
   docker compose up -d --build
   ```

   Bei lokalem Java-Start: `docker compose up -d --force-recreate lavalink`, dann den bisherigen
   Bot mit **Ctrl+C** beenden und `./gradlew.bat run --no-daemon` ausführen.
4. In Discord `/play query:SPOTIFY_LINK` oder `/play query:Titel source:Spotify` verwenden.

Der Google-Schlüssel ermöglicht Suche und Metadatenabgleich. Die separate Audioverbindung
benötigt das konfigurierte [Lavalink-YouTube-Plugin](https://github.com/lavalink-devs/youtube-source).
Der eingebaute alte YouTube-Source bleibt deshalb ausgeschaltet. YouTube kann die Wiedergabe
serverseitig ablehnen; ein gültiger Data-API-Schlüssel behebt solche Wiedergabefehler nicht.
Die normale SoundCloud- und Bandcamp-Wiedergabe funktioniert weiterhin ohne Google-Schlüssel.
API-Referenzen: [search.list](https://developers.google.com/youtube/v3/docs/search/list),
[videos.list](https://developers.google.com/youtube/v3/docs/videos/list).

Vollständige `open.spotify.com`-Links verwenden; `spotify.link`-Kurzlinks, Podcasts,
private Playlists, Spotify-Kontoverknüpfung und lokale Spotify-Dateien sind nicht Teil dieser Version.
LavaSrc lädt durch die Konfiguration höchstens zwei Playlist-Seiten bzw. vier Album-Seiten
(je nach Spotify-Antwort bis zu 200 Titel). Größere Sammlungen können damit unvollständig übernommen werden.
Eine Übernahme, die die verbleibende Queue-Kapazität überschreitet, wird vollständig abgelehnt.

Spotify-Suche benötigt Client-ID und Client-Secret. Link-Auflösung verwendet die von LavaSrc
unterstützten Spotify-Endpunkte und kann auch ohne Such-Credentials funktionieren; das ist nicht garantiert.
App-Modus, API-Berechtigungen, Länderfreigaben und Spotify-Zugriffsbeschränkungen beeinflussen die Verfügbarkeit.
Es werden keine Cookies oder fremden Konten benötigt. Bei einer Sperre wird kein Zugriff umgangen.

## Moderation

| Befehl | Verhalten / erforderliches Recht |
| --- | --- |
| `/kick user:... reason:...` | Mitglied entfernen / Kick Members |
| `/ban user:... reason:...` | Mitglied bannen, keine alten Nachrichten löschen / Ban Members |
| `/unban user_id:... reason:...` | Bann per numerischer Discord-ID aufheben / Ban Members |
| `/timeout user:... minutes:... reason:...` | Timeout für 1–40320 Minuten (28 Tage) / Moderate Members |
| `/untimeout user:... reason:...` | Timeout entfernen / Moderate Members |
| `/purge count:... reason:...` | Letzte 1–100 Nachrichten prüfen und geeignete löschen / Manage Messages |
| `/slowmode seconds:... reason:...` | 0–21600 Sekunden; 0 deaktiviert / Manage Channels |

`reason` ist optional. `/ban` und `/kick` richten sich an aktuelle Servermitglieder.
`/purge` und `/slowmode` funktionieren in normalen Textkanälen, nicht in Threads, Foren oder Ankündigungskanälen.
`/purge` behält gepinnte Nachrichten und Nachrichten ab knapp 14 Tagen Alter; die Antwort nennt
Gelöschte und Übersprungene. Löschungen und Mitgliedsaktionen werden sofort beim Slash-Aufruf ausgeführt.

## Modlogs per Discord-Webhook

1. Im gewünschten Modlog-Textkanal **Kanal bearbeiten → Integrationen → Webhooks → Neuer Webhook** öffnen
   (zum Anlegen benötigst du **Manage Webhooks**).
2. Webhook-URL kopieren und in `.env` eintragen:

   ```dotenv
   MODLOG_WEBHOOK_URL=https://discord.com/api/webhooks/DEINE_ID/DEIN_TOKEN
   ```

3. `docker compose up -d --build bot` ausführen. Beim lokalen Java-Start den Bot nach der
   Änderung an `.env` neu starten.

Leer bedeutet deaktiviert. Die URL ist ein Zugangsschlüssel und bleibt in deiner lokalen Konfiguration.
Der Bot braucht zum Versenden über einen vorhandenen Webhook keine zusätzliche Discord-Berechtigung.
Für einen bestehenden Thread ist optional `?thread_id=DEINE_THREAD_ID` an der URL möglich.

Alle sieben Mod-Befehle protokollieren **nach bestätigter Ausführung** ein Embed mit Aktion,
Server/Kanal, Moderator und ID, Ziel/Parametern, Grund, Ergebnis, Zeitstempel und Fall-ID (Interaction-ID).
Bei Timeout steht die Dauer dabei, bei Purge die tatsächliche Löschzahl und die übersprungenen Nachrichten.
Ein Purge ohne löschbare Nachrichten erscheint ausdrücklich als „Keine Änderung“.
Rechteprüfungen und fehlgeschlagene Discord-Aktionen erzeugen keinen Erfolgseintrag.
Moderation außerhalb dieser Bot-Befehle wird nicht erfasst.

Die konfigurierte URL ist ein **zentraler Modlog für alle Server dieses Bot-Prozesses**;
Servername und Server-ID unterscheiden die Einträge. Es gibt keine automatische Webhook-Erstellung
und keine serverindividuellen URLs. Pings sind in allen Webhook-Nachrichten deaktiviert.

Der Versand läuft separat und ändert nie das Ergebnis einer bereits ausgeführten Moderation.
Discord-Ratelimits werden berücksichtigt (bis zu drei Versuche, maximal 60 Sekunden Wartezeit pro Versuch).
Bei längerer Sperre wird der Versand als fehlgeschlagen gespeichert; weitere Requests werden bis zum Ende der Sperre unterdrückt.
Bei anderen HTTP-/Netzwerkfehlern erfolgt keine automatische Wiederholung, um mögliche Doppeleinträge
nach unklarer Zustellung zu vermeiden. Die Konsole meldet die Fall-ID ohne Webhook-URL oder Token.
Die Versandwarteschlange hält maximal 256 Einträge im Arbeitsspeicher; sie ist nicht persistent.
Ein voller Puffer, ein Neustart oder ein Versandfehler kann Webhook-Nachrichten verhindern; gespeicherte Datenbankfälle bleiben erhalten.

Technische Referenz: [Discord Execute Webhook](https://docs.discord.com/developers/resources/webhook).

## Java-Entwicklung

JDK 21 installieren. Gradle wird durch den mitgelieferten Wrapper geladen.

Im Projektordner `.env` anlegen (falls noch nicht vorhanden: `Copy-Item .env.example .env`).
In `.env` deinen Bot-Token als `DISCORD_TOKEN` und das Passwort des Lavalink-Servers als
`LAVALINK_PASSWORD` eintragen. Dann im Projektordner starten:

```powershell
.\gradlew.bat clean test installDist
.\gradlew.bat run
```

Die Java-Anwendung liest `.env` aus dem aktuellen Arbeitsverzeichnis, auch beim Start in IntelliJ
oder über die installierte Distribution. In IntelliJ deshalb den Projektordner als Arbeitsverzeichnis wählen.
Bereits gesetzte Umgebungsvariablen haben Vorrang; alternativ zur Datei kannst du beispielsweise
`$env:DISCORD_TOKEN = "dein-token"` setzen. Ohne `.env` werden nur Umgebungsvariablen verwendet.
Die Datei wird bei jedem Bot-Start neu eingelesen. `.env.example` dient nur als Vorlage.
Für lokale `.env`-Werte keine oder doppelte Anführungszeichen verwenden; `${...}`-Verweise werden nicht expandiert.
Unter Linux/macOS `sh gradlew ...` und bei Bedarf `export` für Umgebungsvariablen nutzen.
`LAVALINK_URI` ist lokal standardmäßig `ws://localhost:2333`; für einen anderen Server in `.env` setzen.
Für lokal gestartete Bots unter `services.lavalink` in `compose.yaml` ergänzen:
`ports: ["127.0.0.1:2333:2333"]`, dann `docker compose up -d lavalink`.
Spotify-Credentials verbleiben in der Lavalink-Umgebung; die Java-Anwendung benötigt sie nicht.

Die ausführbare Distribution liegt nach dem Build unter `build/install/discord-music-bot`.
Einstiegspunkt: `src/main/java/music/Main.java`. Musik, Eingabevalidierung, Moderation
und Moderationsregeln sind in eigenen Klassen organisiert. Musik und Moderation nutzen getrennte Worker.

## Prüfungen und Grenzen

`gradlew test` prüft Queue-Reihenfolge, atomare Kapazitätsgrenzen, Spotify-Linknormalisierung,
Eingabeprüfung und geschützte Moderationsziele/Rollenbedingungen. Webhook-Tests prüfen außerdem
Payloads, Ping-Sperre, URL-Validierung, Ratelimits und isolierte Versandfehler mit simuliertem Transport.
Tests benötigen keine Tokens und senden keine Nachrichten an Discord.
Live-Wiedergabe, tatsächliche Spotify-Auflösung und Moderationsaktionen müssen mit deinen Credentials
auf einem Testserver überprüft werden; ein erfolgreicher Build ersetzt diese Tests nicht.

Keine persistente Queue, keine Warn-Datenbank, kein AutoMod, keine DJ-Rollenverwaltung.
Bei längeren Verbindungsstörungen gegebenenfalls `/leave` und `/play` erneut ausführen.
Wenn Lavalink noch startet, kurz warten und `/play` wiederholen.

Quellen: [JDA](https://github.com/discord-jda/JDA),
[Lavalink / DAVE](https://lavalink.dev/changelog/v4),
[LavaSrc 4.8.3](https://github.com/topi314/LavaSrc/tree/4.8.3),
[Spotify-API](https://developer.spotify.com/documentation/web-api).

## Owner commands

Set `BOT_OWNER_ID` in `.env` to your Discord **user ID** (Developer Mode > Copy User ID).
`/restart` checks this exact ID; server administrators cannot bypass it. If unset, restart is disabled.
`/status` uses only the same configured ID and is disabled when it is unset.
`/help` lists both commands in the owner section.

`/restart` acknowledges the owner command and persists `BOT_STOPPED` with reason `RESTART` in SQLite.
The existing command workers, music players, voice connections, webhook service, Lavalink client and JDA are closed,
then Docker starts a fresh process through `restart: unless-stopped` (`BOT_RESTART_EXTERNAL=true` in Compose).
A local IDE/Gradle launch creates a new bot session after successful cleanup and reloads .env. Startup or cleanup failures stop the process instead of retrying indefinitely. Code changes still require a new build and launch.
Docker allows 75 seconds for graceful shutdown. No shutdown/restart presence is sent or cleared.
If the restart event cannot be saved, restart is cancelled and the console reports the database problem.

Each bot session sets `startedAt = Instant.now()`. Public `/stats` reports elapsed time from that session start,
starting at 0s and increasing normally; previous sessions' timestamps are not reused.
After JDA is ready, slash commands are registered and the Lavalink node is configured, startup records `BOT_STARTED`.
Playback and queues reset on process restart. Docker environment/code changes require rebuilding or recreating the container.

The default 2-minutes rotation preserves Status 1: **Playing with my Besties 🎀 | N servers | N shards**, ONLINE.
Status 2  ** at the training 🏐| (N)** with Discord status ONLINE. N in Status 2 is only the dynamic shard count.
Owner `/status` overrides still work; `/status action:stop` restores the normal pair.
## Snipe and public replies

Server slash-command replies are public in the invoking channel. Command permissions still apply.
`/snipe` requires Manage Messages, View Channel and Read Message History in that text channel.
Enable **Message Content Intent** in Discord Developer Portal > Bot > Privileged Gateway Intents before relaunching.
The bot caches at most 2000 observed messages and 2000 channel deletions in memory, with a one-hour expiry checked on cache access.
Snipe shows the latest cached deletion in the current channel (text and attachment links); it cannot recover messages from before startup or expired/evicted messages.
Bot/webhook messages are not cached. Restart clears the cache. Mentions in replies do not send notifications.
## Moderation database

Moderation cases are stored in SQLite before webhook delivery is queued. The table is created automatically at startup.
Local configuration in `.env`:

```dotenv
MODLOG_DB_PATH=data/moderation.db
```

An omitted or blank path defaults to `BOT_DATA_DIR/moderation.db` (`data/moderation.db` locally).
Docker uses `/app/data/moderation.db` in the existing persistent `bot-data` volume, regardless of the local path.
No database server or database password is required. Relaunch/rebuild the bot once to load this change.

The `moderation_cases` table stores the case and server IDs, action, channel, moderator, target, reason, result,
time and language. It also tracks `webhook_status`, `webhook_attempts`, `webhook_http_status`,
`webhook_error` and `webhook_updated_at`. Webhook URLs, credentials and HTTP response bodies are not stored.
Cases are retained even with a blank `MODLOG_WEBHOOK_URL`. Existing cases are not backfilled from Discord.
The seven moderation actions that change server state are recorded after success (including purge no-ops);
`/snipe` reads, rejected commands and failed Discord actions do not create cases.

Delivery states: `DISABLED`, `PENDING`, `SENDING`, `DELIVERED`, `FAILED`, `NOT_SENT`.
Each case is unique by `(guild_id, case_id)`; republishing the same case does not duplicate its webhook.
Queued cases survive as database records, but are not automatically resent after a restart.
`PENDING` or `SENDING` after an abrupt shutdown means delivery was not confirmed; inspect before resending to avoid duplicates.
Database initialization errors stop startup. Runtime database errors are logged without private configuration;
they do not undo completed moderation or prevent a webhook attempt.

Example read-only query in a SQLite client:

```sql
SELECT occurred_at, action, moderator_id, target, reason, result,
       webhook_status, webhook_attempts, webhook_http_status
FROM moderation_cases
WHERE guild_id = 'YOUR_SERVER_ID'
ORDER BY occurred_at DESC
LIMIT 50;
```

Records have no automatic expiration. Back up the database using a SQLite-aware backup tool,
or stop the bot before copying the database and any WAL files. Database files are excluded from Git and Docker builds.
## Help and avatars

`/help` shows a public embed with Music, Moderation, General and Owner sections in the server language.
`/avatar user_id:123456789012345678` shows that Discord user's global profile picture, a full-size image link and their ID.
Anyone can use it; the target does not need to be a member of the current server. Invalid or unknown IDs return an error.
Default avatars and animated avatars are supported. The bot needs **Embed Links** in the channel to display embeds.
Rebuild and relaunch once to register `/avatar` and load the new help layout. No new `.env` settings are needed.

### SQLite lifecycle events

The existing SQLite store also creates `bot_events` alongside `moderation_cases`; no database engine migration is performed.
Columns: `session_id`, `event_type`, `reason`, `occurred_at`, `started_at`.
A unique `(session_id, event_type)` key prevents repeated restart requests or a shutdown hook from duplicating events.
Successful process starts get `BOT_STARTED`; orderly stops get `BOT_STOPPED` (`RESTART` for the owner command, `SHUTDOWN` for other orderly exits).
An abrupt kill or machine crash cannot run shutdown cleanup or guarantee a stop event.
The existing moderation records and webhook delivery behavior are retained.

```sql
SELECT event_type, reason, occurred_at, started_at, session_id
FROM bot_events ORDER BY rowid DESC LIMIT 20;
```

### /stats
Zeigt Sitzungslaufzeit, Serveranzahl, summierte Mitgliederzahl (inklusive Bots und Mehrfachmitgliedschaften) und Bot-Version. Die Versionsnummer wird zentral in src/main/resources/bot-version.properties gepflegt und auch als Gradle-Projektversion verwendet.


### Audio-Wiedergabe mit yt-dlp

Die Standardsuche von /play ist YouTube. source:SoundCloud bleibt auswählbar. YouTube- und SoundCloud-Titel werden unmittelbar vor der Wiedergabe mit yt-dlp als Audiostream aufgelöst; die Warteschlange behält die ursprünglichen Titel. Lavalink benötigt youtube-plugin 1.18.2 für Metadaten und die aktivierte HTTP-Quelle für Audio.

Beim lokalen Windows-Start liegen die Audiohelfer unter tools/yt-dlp.exe und tools/node.exe. Andere Installationen können YTDLP_PATH und YTDLP_JS_RUNTIME (zum Beispiel node oder node:C:/Tools/node.exe) in .env setzen. Docker enthält die Helfer im Image (Linux x86-64). Downloads stammen aus dem offiziellen yt-dlp-Projekt; die im Dockerfile festgelegte Version wird per SHA-256 geprüft. Eingeschränkte oder nicht verfügbare Videos werden als Fehler gemeldet.
# Datenbankgestützte Bot-Statistik

`/stats` zeigt aktuelle Laufzeit, Server-/Mitgliederzahl, Version, den aufrufenden Server und Kanal, Bot-Owner, Ersteller und Java/JDA/Lavalink. Der Footer kennzeichnet die Antwort als Bot-Nachricht. `BOT_OWNER_ID` bestimmt den Owner; `BOT_CREATOR` setzt den frei wählbaren Ersteller-Namen. Fehlende Angaben werden als nicht konfiguriert angezeigt.

Die vorhandene SQLite-Datei (`MODLOG_DB_PATH`, sonst `BOT_DATA_DIR/moderation.db`) enthält zusätzlich `bot_stats_context`. Pro Bot, Server und aufrufendem Kanal wird der neueste Name und Zeitstempel gespeichert. Nachrichteninhalte werden dafür nicht gespeichert. Startanzahl und letzter angeforderter Neustart stammen aus `bot_events` und bleiben über Neustarts erhalten. Diese Historie bezieht sich auf die Bot-Installation, die diese Datenbank verwendet.

Jeder Aufruf aktualisiert die Statistik; bereits gesendete Embeds ändern sich nicht automatisch. Bei einem Datenbankfehler bleibt die aktuelle Statistik sichtbar und meldet den Speicherfehler. `/restart` ist weiterhin nur für den konfigurierten Bot-Owner zugänglich; eine Statistikabfrage löst keinen Neustart aus.
# YouTube und Lyrics

`/play query:https://www.youtube.com/watch?v=...` und `https://youtu.be/...` laden YouTube-Videos; eine Textsuche nutzt standardmäßig YouTube. Die vorbereitete Lavalink-Konfiguration mit YouTube-Plugin, HTTP-Quelle und yt-dlp wird dafür benötigt.

`/lyrics` sucht bei LRCLIB nach dem aktuellen Titel. `/lyrics query:ADÉLA Ain't In LA` sucht unabhängig von der Wiedergabe; ein Voice-Beitritt ist dafür nicht nötig. Der Bot zeigt den gefundenen Titel und Künstler mit Songtext und Quellenlink. Lange Texte werden im Embed gekürzt. Die Suchbegriffe werden an LRCLIB gesendet; ein API-Key ist nicht erforderlich. Suchtreffer können eine andere Version des Songs sein; dann Titel und Künstler ausdrücklich angeben.
# Commands mit eigenem Server-Prefix

Alle registrierten Commands sind zusätzlich als normale Nachrichten verfügbar. Standard ist `T.`:

- `T.play Ain't in L.A. from Adela` oder `T.play https://youtu.be/...`
- `T.play song name --source soundcloud`
- `T.lyrics` oder `T.lyrics ADÉLA Ain't In LA`
- `T.stats`, `T.queue`, `T.skip`, `T.pause`, `T.resume`
- `T.prefix ?` oder `/prefix value:?` speichert einen neuen Prefix für diesen Server. `T.prefix` bzw. `/prefix` zeigt den aktuellen Wert. Zum Zurücksetzen `/prefix value:T.` verwenden.

Prefixe dürfen 1–10 Buchstaben, Ziffern oder unterstützte Satzzeichen enthalten, z. B. `t!`. Änderungen erfordern **Server verwalten**. Die Tabelle `guild_prefixes` in der vorhandenen SQLite-Datenbank speichert sie dauerhaft. Nach einer Änderung gilt sofort nur noch der neue Prefix; Slash-Commands bleiben erreichbar.

Argumente folgen der Reihenfolge der Slash-Optionen. Suchtexte, Gründe und Status-Texte dürfen Leerzeichen enthalten. Mit `--optionsname wert` lassen sich Optionen ausdrücklich setzen, z. B. `T.status start --texts Music | Volleyball --interval_ms 120000`. `T.timeout @Mitglied 5 Spam` verwendet dieselben Rechte- und Hierarchieprüfungen wie `/timeout`. `T.restart` bleibt auf den Bot-Owner beschränkt und startet erst nach erfolgreicher Antwort neu. Bots, Webhooks und DMs lösen keine Prefix-Commands aus.

Im Discord Developer Portal unter **Bot → Privileged Gateway Intents** muss **Message Content Intent** aktiviert sein. Der Code fordert dieses Intent bereits an. Der Bot braucht außerdem Zugriff auf den Kanal und die Berechtigung, dort Nachrichten zu senden. Für Änderungen an diesem Code muss der neue Build installiert und der Bot-Prozess neu gestartet werden.
# Repeat

`/repeat` or `T.repeat` toggles current-song repeat. Use `/repeat mode:on` / `T.repeat on` or `off` to set it explicitly. You must be in the bot's voice channel with a current song. Repeat applies on normal track completion; skip and playback failures advance the queue. Stop and leave reset repeat. Repeat is kept per server for the current bot session.
