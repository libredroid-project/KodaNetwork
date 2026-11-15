# KodaHosting v0.144beta (versionCode 8524)

## English

### Play Store (closed testing, max 500 characters)

<en-US>
+ Inbox with categories, read state and deep links
+ Request your data as ZIP/JSON/HTML, request full deletion
+ Question and idea tickets
+ New login/register, Google sign-in, e-mail verification
+ Redesigned settings, server sheets and danger zones
+ File trash with restore, undo for rename/delete
+ Boot progress, new crash screen with Ask AI
+ KodaDash playtime leaderboard
+ Bedrock diagnostics
- Bedrock temporarily off while Geyser moves to NetherNet
</en-US>

### App updater (`releases.changelog_short`, English, 458 characters)

The update dialog in the app reads `changelog_short` from the `releases` table. This is the
list stored for version 8524; the long text stays in `changelog` for the website and the
archive.

+ Inbox with categories, read state and deep links
+ Request your data as ZIP/JSON/HTML, request full deletion
+ Question and idea tickets
+ New login/register, Google sign-in, e-mail verification
+ Redesigned settings, server sheets and danger zones
+ File trash with restore, undo for rename/delete
+ Boot progress, new crash screen with Ask AI
+ KodaDash playtime leaderboard
+ Bedrock diagnostics
- Bedrock temporarily off while Geyser moves to NetherNet

### Full notes

**Account and privacy**
- New notification inbox with a bell badge, categories, read/unread, delete one or all, mute per category and deep links into tickets, servers, releases and data requests. Admins can send messages to one account or everyone.
- “Request my data” builds a complete export after review: machine-readable JSON for every area, a human-readable HTML file, README and checksums, all in a ZIP. Download from the app or the e-mail.
- “Request full data deletion” is a separate request with admin approval. The account-only delete no longer touches servers or files. Legally required records move into a separate retention table (billing 10 years, contract 6 years, security 180 days) and are purged automatically after the deadline.
- Exports, decision mails, deletion mails and the export HTML are English now.
- Support tickets can be Question or Idea, not only bug/server reports.
- New login and register screens in the app design, a real Google button, and e-mail verification: skip it during signup, verify later in Account, required before publishing extensions or requesting a data export.

**Settings and sheets**
- Account row with role and permissions, manage/unlink actions as text actions, language sheet, legacy mode as a switch, Java runtime and start-jar pickers with the same design.
- Settings and server danger zones are compact rows instead of stacked buttons; all destructive sheets use the red action, countdown and double vibration instead of a warning triangle.
- Every server action is a real bottom sheet with the shared Koda chrome, edge-to-edge nav bar and fly-in animation. The Minecraft version chooser has a new wheel and a calm confirmation sheet.
- Sheet buttons sit in one row, developer tools use equal-height rows, and the top labels align.

**Servers and files**
- Files have a trash screen in the Files header: restore or delete items without opening them, plus “restore all” and “empty” with a countdown. Deleted files stay for 7 days.
- Undo for renaming and deleting files.
- Boot progress on the loading screen shows the real start phases.
- KodaDash links are checked before opening; a broken tunnel shows a sheet with the HTTP code and a “open directly” action instead of a browser error.
- Mod compatibility warning for mods with desktop-only native libraries such as Valkyrien Skies.
- Forge 1.17+ installations are detected correctly and `unix_args.txt` is passed as single arguments. Fabric 26.x runs on Java 25. Crash detection also recognises a fresh `crash-reports/` folder when the process exits with code 0.
- New crash screen in the sheet design, Ask AI as a bottom sheet and FULL LOGS opening the editor again.
- KodaDash has a playtime leaderboard: join/quit sessions are stored per player and the Players tab shows the top 10.
- Bedrock diagnostics in the server dashboard: TCP signaling, UDP gameplay and tunnel status.
- **Bedrock is temporarily disabled.** Geyser 2.12 needs NetherNet and its native library does not run on Android bionic yet. The toggle explains this instead of enabling a broken server. Java servers are unaffected.
- Pumpkin stays disabled.
- Security fixes for native-server log formatting and the RAM warning.

## Deutsch

### Play Store (Closed Testing, max. 500 Zeichen)

<de-DE>
+ Posteingang mit Kategorien, Gelesen-Status und Deep Links
+ Datenexport als ZIP/JSON/HTML, vollständige Löschung beantragen
+ Frage- und Ideen-Tickets
+ Neuer Login/Registrierung, Google-Login, E-Mail-Verifizierung
+ Einstellungen, Server-Sheets und Danger Zones neu
+ Datei-Papierkorb mit Wiederherstellen, Undo für Umbenennen/Löschen
+ Startfortschritt, neuer Crash-Screen mit Ask AI
+ KodaDash-Spielzeit-Bestenliste
- Bedrock vorübergehend aus, während Geyser auf NetherNet umstellt
</de-DE>

### Vollständige Notizen

**Konto und Datenschutz**
- Neuer Benachrichtigungs-Posteingang mit Glocken-Badge, Kategorien, gelesen/ungelesen, einzeln oder alle löschen, Stummschalten pro Kategorie und Deep Links in Tickets, Server, Releases und Datenanträge. Admins können an einzelne Konten oder an alle senden.
- „Meine Daten anfordern“ baut nach der Prüfung einen vollständigen Export: maschinenlesbares JSON für alle Bereiche, eine lesbare HTML-Datei, README und Prüfsummen in einer ZIP. Download in der App oder per E-Mail.
- „Vollständige Datenlöschung beantragen“ ist ein eigener Antrag mit Admin-Freigabe. „Konto löschen“ löscht nur noch das Konto, keine Server oder Dateien. Gesetzlich erforderliche Unterlagen kommen in eine separate Aufbewahrungstabelle (Billing 10 Jahre, Vertrag 6 Jahre, Security 180 Tage) und werden danach automatisch gelöscht.
- Exporte, Entscheidungs-Mails, Lösch-Mails und die Export-HTML sind jetzt auf Englisch.
- Support-Tickets können Frage oder Idee sein, nicht nur Bug- und Server-Reports.
- Neuer Login- und Registrierungs-Screen im App-Design, echter Google-Button und E-Mail-Verifizierung: beim Registrieren überspringbar, später im Konto nachholbar, Pflicht für Extension-Uploads und Datenexporte.

**Einstellungen und Sheets**
- Konto-Zeile mit Rolle und Rechten, Manage/Unlink als Textaktionen, Sprach-Sheet, Legacy-Modus als Schalter, Java-Runtime- und Start-Jar-Wähler im gleichen Design.
- Settings- und Server-Danger-Zones sind kompakte Zeilen statt gestapelter Buttons; alle destruktiven Sheets nutzen die rote Aktion, Countdown und doppelte Vibration statt Warndreieck.
- Jede Server-Aktion ist ein echtes Bottom Sheet mit gemeinsamem Koda-Chrome, Edge-to-Edge-Nav-Bar und Einflug-Animation. Der Minecraft-Versionswähler hat ein neues Rad und eine ruhige Bestätigung.
- Sheet-Buttons stehen in einer Reihe, Entwickler-Tools haben gleich hohe Zeilen und die Beschriftungen starten oben bündig.

**Server und Dateien**
- Dateien haben einen Papierkorb im Files-Header: Wiederherstellen oder Löschen ohne Öffnen, „Restore all“ und „Empty“ mit Countdown. Gelöschte Dateien bleiben 7 Tage.
- Undo für Umbenennen und Löschen von Dateien.
- Startfortschritt auf dem Ladescreen mit den echten Startphasen.
- KodaDash-Links werden vor dem Öffnen geprüft; ein kaputter Tunnel zeigt ein Sheet mit HTTP-Code und „Direkt öffnen“ statt einer Browser-Fehlerseite.
- Mod-Kompatibilitätswarnung für Mods mit Desktop-only-Native-Libraries wie Valkyrien Skies.
- Forge 1.17+ wird korrekt erkannt und `unix_args.txt` als einzelne Argumente übergeben. Fabric 26.x läuft mit Java 25. Die Crash-Erkennung erkennt auch einen frischen `crash-reports/`-Ordner bei Exit-Code 0.
- Neuer Crash-Screen im Sheet-Design, Ask AI als Bottom Sheet und FULL LOGS öffnet wieder den Editor.
- KodaDash hat eine Spielzeit-Bestenliste: Join/Quit-Sessions werden pro Spieler gespeichert, der Players-Tab zeigt die Top 10.
- Bedrock-Diagnose im Server-Dashboard: TCP-Signaling, UDP-Gameplay und Tunnelstatus.
- **Bedrock ist vorübergehend deaktiviert.** Geyser 2.12 braucht NetherNet, und die native Bibliothek läuft noch nicht auf Android bionic. Der Schalter erklärt das, statt einen kaputten Server zu aktivieren. Java-Server sind nicht betroffen.
- Pumpkin bleibt deaktiviert.
- Sicherheitsfixes für Native-Server-Logformatierung und die RAM-Warnung.

## Discord

**KodaHosting v0.144beta is out for closed testing**

- Notification inbox with categories and deep links
- Full data export and account deletion requests
- Question and idea support tickets
- New login/register, Google sign-in and e-mail verification
- Redesigned settings, server sheets and danger zones
- File trash, undo for rename/delete, boot progress
- New crash screen with Ask AI
- KodaDash playtime leaderboard
- Bedrock diagnostics; Bedrock itself is temporarily off while Geyser moves to NetherNet

Java servers keep working normally. Full notes and the F-Droid repository:
https://host.kodanetwork.eu/fdroid/
