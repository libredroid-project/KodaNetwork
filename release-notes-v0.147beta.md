# KodaHosting v0.147beta (versionCode 8527)

## English

### Play Store (closed testing, max 500 characters)

<en-US>
+ KodaDash MCP: let an AI client inspect and change your server
+ Separate MCP token with enable, read-only and capability switches
+ Set up MCP in the KodaDash dashboard or the KodaHosting app
+ Account avatar loads again and uploads refresh an expired session
- The AI can never start, stop or restart the server
</en-US>

### App updater (`releases.changelog_short`, English)

+ KodaDash MCP for AI clients
+ Separate token with read-only and capability switches
+ Account avatar and upload fixed
- AI cannot start, stop or restart the server

### Full notes

**KodaDash MCP**
- KodaDash now exposes a Model Context Protocol endpoint at `https://<server>/mcp`. AI clients that support Streamable HTTP can connect with a Bearer token.
- The token is separate from the dashboard token and is generated when MCP is enabled. It can be regenerated at any time; the old token stops working immediately.
- Setup lives in the KodaDash dashboard (Settings, AI access) and in the KodaHosting app (Server, KodaDash, AI access).

**What the AI can do**
- Read server status, console, players, playtime, stats history, files, logs, plugins, server.properties, schedules and the backup index.
- Run console commands, write or delete files, change server.properties, enable or disable plugins and install a plugin from a public https URL.
- It cannot start, stop, restart or hibernate the server. Those commands are rejected over MCP; only the human user can do them in the app or dashboard.

**Security**
- Read-only mode hides every write tool.
- Separate switches for console, file writes, server.properties and plugins.
- Destructive tools require `confirm:true`; file writes require it for overwrites.
- Every call is written to `plugins/KodaDash/mcp-audit.log`, with file contents and secrets redacted.
- `plugins/KodaDash/config.yml`, the audit log and `.frpc.toml` are blocked from the MCP file tools.

**AI context**
- The MCP system prompt explains what KodaHosting is, which server and phone it is working on, and the rules.
- Server software, version, worlds, plugins, players and TPS come from the running server. Phone data comes from the app's local `device-info.json`.
- The cloud/app mirror (`server-info.json`) is labelled as secondary and may be stale. A wrong cloud `server_version` is never used as the authoritative version.

**Account**
- The account page asks the profile RPC for `avatar_url` again, so the real profile picture replaces the orange initial.
- Avatar uploads refresh an expired session token once and retry, instead of failing with “unauthorized” until the app is restarted.
- The MCP setup is split into Connection, Permissions, AI instructions, How to set up and Troubleshooting, like the account categories. The instruction preview and the help texts scroll without dragging the sheet, and tapping AI access while KodaDash is off explains that KodaDash must be turned on first.

## Deutsch

### Play Store (Closed Testing, max. 500 Zeichen)

<de-DE>
+ KodaDash MCP: KI-Zugriff auf den Server
+ Eigenes MCP-Token mit Aktivieren, Nur-Lesen und Rechte-Schaltern
+ MCP im KodaDash-Dashboard oder in der KodaHosting-App einrichten
+ Profilbild lädt wieder, Uploads erneuern eine abgelaufene Sitzung
- Die KI kann den Server niemals starten, stoppen oder neu starten
</de-DE>

### Vollständige Notizen

**KodaDash MCP**
- KodaDash bietet jetzt einen Model-Context-Protocol-Endpunkt unter `https://<server>/mcp`. KI-Clients mit Streamable HTTP verbinden sich mit einem Bearer-Token.
- Das Token ist getrennt vom Dashboard-Token und wird beim Aktivieren von MCP erzeugt. Es lässt sich jederzeit neu erzeugen; das alte Token wird sofort ungültig.
- Die Einrichtung liegt im KodaDash-Dashboard (Settings, AI access) und in der KodaHosting-App (Server, KodaDash, AI access).

**Was die KI darf**
- Serverstatus, Konsole, Spieler, Spielzeit, Statistikverlauf, Dateien, Logs, Plugins, server.properties, Zeitpläne und den Backup-Index lesen.
- Konsolenbefehle ausführen, Dateien schreiben oder löschen, server.properties ändern, Plugins aktivieren oder deaktivieren und ein Plugin von einer öffentlichen https-URL installieren.
- Sie kann den Server nicht starten, stoppen, neu starten oder hibernieren. Diese Befehle werden über MCP abgelehnt; nur der menschliche Nutzer darf das in der App oder im Dashboard.

**Sicherheit**
- Nur-Lesen-Modus blendet alle Schreibwerkzeuge aus.
- Eigene Schalter für Konsole, Dateischreiben, server.properties und Plugins.
- Destruktive Werkzeuge brauchen `confirm:true`; Überschreiben braucht es ebenfalls.
- Jeder Aufruf landet in `plugins/KodaDash/mcp-audit.log`, Dateiinhalte und Geheimnisse werden redigiert.
- `plugins/KodaDash/config.yml`, das Audit-Log und `.frpc.toml` sind für die MCP-Dateiwerkzeuge gesperrt.

**KI-Kontext**
- Der MCP-Systemprompt erklärt, was KodaHosting ist, an welchem Server und Handy gearbeitet wird und welche Regeln gelten.
- Serversoftware, Version, Welten, Plugins, Spieler und TPS kommen vom laufenden Server. Handydaten kommen aus der lokalen `device-info.json` der App.
- Der Cloud-/App-Spiegel (`server-info.json`) ist als zweitrangig markiert und kann veraltet sein. Eine falsche Cloud-`server_version` wird nie als verbindliche Version verwendet.

**Konto**
- Die Account-Seite fragt den Profil-RPC wieder nach `avatar_url`, dadurch ersetzt das echte Profilbild das orange Initial.
- Avatar-Uploads erneuern ein abgelaufenes Sitzungs-Token einmal und versuchen es erneut, statt bis zum App-Neustart mit „unauthorized“ zu scheitern.
- Die MCP-Einrichtung ist in Verbindung, Rechte, KI-Anweisungen, Einrichtung und Fehlerbehebung aufgeteilt, wie die Kategorien der Account-Seite. Die Anweisungs-Vorschau und die Hilfetexte scrollen, ohne das Sheet zu ziehen, und ein Tipp auf AI access bei ausgeschaltetem KodaDash erklärt, dass KodaDash zuerst eingeschaltet werden muss.

## Discord

**KodaHosting v0.147beta is out for closed testing**

- KodaDash MCP: connect an AI client to your server
- Separate MCP token, read-only mode and per-capability switches
- Set it up in the KodaDash dashboard or the KodaHosting app
- Every AI call is written to an audit log
- Account avatar and upload fixed
- The AI can never start, stop or restart the server

Java servers keep working normally. Full notes and the F-Droid repository:
https://host.kodanetwork.eu/fdroid/
