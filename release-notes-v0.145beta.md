# KodaHosting v0.145beta (versionCode 8525)

## English

### Play Store (closed testing, max 500 characters)

<en-US>
+ New Account page with avatar, username, 2FA, notifications and devices
+ Pick your account and username during setup
+ Devices & sessions: send servers, unlink and manage devices
+ Upload a profile picture that also appears on the website
+ Light mode uses the same server cards as dark mode
+ Fixed bottom sheets and navigation bars
+ Better ZIP import, including Forge 1.12.2 backups
+ Setup no longer touches servers on your other devices
- Bedrock remains temporarily unavailable
</en-US>

### App updater (`releases.changelog_short`, English)

+ Account page: avatar, username, 2FA, devices
+ Pick account and username during setup
+ Devices & sessions: send, unlink, refresh
+ Better ZIP and Forge 1.12.2 import
+ Setup no longer touches other devices' servers
- Bedrock still off

### Full notes

**Account and setup**
- Account is now a short page with categories: Profile (email verification, profile picture, username, password, two-factor), Notifications, Devices & sessions, Transfer, Privacy & data and Account (delete account, log out).
- Upload a profile picture in the app. It is the same picture the website shows. The username can be changed in Profile.
- Setup asks whether you want an account and collects the username on the sign-up screen. The random nickname prompt in the app is gone.
- Cancelling a category sheet returns to the main account sheet.

**Devices and transfers**
- Devices & sessions is a native bottom sheet. It opens at about 70% and can be dragged up to full screen.
- Each device shows the KodaHosting user, its Minecraft accounts, Android/app version, last seen, battery, RAM and network.
- Device options: send servers to this device, unlink, copy device ID and refresh. The current device cannot be unlinked.
- The session token is refreshed automatically when it expires, so the device list no longer fails after an hour.

**Setup and server ownership**
- First-run recovery only lists servers owned by the current device.
- Own hosts are adopted with `rpc_adopt_hosts`; `rpc_adopt_servers` no longer moves the whole account family.
- Servers on another device stay there instead of being imported or deleted by a new setup.

**ZIP and backup import**
- ZIP import reads the central directory first, falls back to local headers and tolerates a truncated tail. This fixes “Unexpected end of ZLIB input stream” and “END header not found” on otherwise usable backups.
- Forge 1.12.2 backups keep the universal jar under `maven/` or `libraries/`; it is detected, copied to the server root and set as the launcher.
- Folder imports detect `world/level.dat` in subfolders, so the “No world” warning is no longer shown for a complete world.
- A single wrapper folder in a ZIP is stripped during import.

**Design**
- Light mode uses the same server cards as dark mode. Fallback icons, offline lines, the language button and Minecraft account names are readable again.
- Language, Legacy Mode, Minecraft permissions and the transfer picker use the shared edge-to-edge sheet chrome and a correct navigation bar.

**Other**
- Support address is `support@kodaserv.eu` everywhere.
- Bedrock remains temporarily disabled while Geyser moves to NetherNet. Java servers are unaffected.

## Deutsch

### Play Store (Closed Testing, max. 500 Zeichen)

<de-DE>
+ Neue Account-Seite: Avatar, Benutzername, 2FA, Benachrichtigungen, Geräte
+ Konto und Benutzername beim Einrichten wählen
+ Geräte & Sitzungen: Server senden, trennen, verwalten
+ Profilbild hochladen, auch auf der Website sichtbar
+ Light Mode: gleiche Serverkarten wie Dark Mode
+ Untere Sheets und Navigationsleisten repariert
+ Besserer ZIP-Import, auch für Forge-1.12.2-Backups
+ Setup fasst Server auf anderen Geräten nicht mehr an
- Bedrock bleibt vorübergehend nicht verfügbar
</de-DE>

### Vollständige Notizen

**Konto und Einrichtung**
- Account ist jetzt eine kurze Seite mit Kategorien: Profile (E-Mail-Verifizierung, Profilbild, Benutzername, Passwort, Zwei-Faktor), Notifications, Devices & sessions, Transfer, Privacy & data und Account (Konto löschen, abmelden).
- Profilbild in der App hochladen. Es ist dasselbe Bild, das die Website zeigt. Der Benutzername lässt sich unter Profile ändern.
- Beim Einrichten wird gefragt, ob du ein Konto willst, und der Benutzername direkt im Registrierungsformular erfasst. Der zufällige Nickname-Dialog in der App ist weg.
- Cancel in einer Kategorie führt zurück zum Haupt-Account-Sheet.

**Geräte und Transfer**
- Devices & sessions ist ein natives Bottom Sheet. Es startet bei etwa 70 % und lässt sich zum Fullscreen nach oben ziehen.
- Jedes Gerät zeigt den KodaHosting-Benutzer, die Minecraft-Konten, Android-/App-Version, zuletzt online, Akku, RAM und Netzwerk.
- Geräteoptionen: Server an dieses Gerät senden, trennen, Geräte-ID kopieren und aktualisieren. Das aktuelle Gerät kann nicht getrennt werden.
- Der Session-Token wird bei Ablauf automatisch erneuert, deshalb fällt die Geräteliste nicht mehr nach einer Stunde aus.

**Setup und Server-Ownership**
- Der Recovery-Schritt beim ersten Einrichten zeigt nur noch Server des aktuellen Geräts.
- Eigene Hosts werden über `rpc_adopt_hosts` übernommen; `rpc_adopt_servers` zieht nicht mehr die ganze Account-Familie um.
- Server auf einem anderen Gerät bleiben dort, statt von einem neuen Setup importiert oder gelöscht zu werden.

**ZIP- und Backup-Import**
- Der ZIP-Import liest zuerst das zentrale Verzeichnis, fällt auf lokale Header zurück und toleriert ein abgeschnittenes Ende. Das behebt „Unexpected end of ZLIB input stream" und „END header not found" bei eigentlich nutzbaren Backups.
- Forge-1.12.2-Backups behalten die Universal-Jar unter `maven/` oder `libraries/`; sie wird erkannt, in den Server-Root kopiert und als Launcher gesetzt.
- Ordner-Importe erkennen `world/level.dat` in Unterordnern, deshalb kommt die falsche „No world"-Warnung bei einer vollständigen Welt nicht mehr.
- Ein einzelner Wrapper-Ordner im ZIP wird beim Import entfernt.

**Design**
- Light Mode nutzt dieselben Serverkarten wie Dark Mode. Fallback-Icons, Offline-Linien, der Language-Button und Minecraft-Kontonamen sind wieder lesbar.
- Language, Legacy Mode, Minecraft-Permissions und der Transfer-Picker nutzen das gemeinsame Edge-to-Edge-Sheet mit korrekter Navigationsleiste.

**Sonstiges**
- Support-Adresse ist überall `support@kodaserv.eu`.
- Bedrock bleibt vorübergehend deaktiviert, während Geyser auf NetherNet umstellt. Java-Server sind nicht betroffen.

## Discord

**KodaHosting v0.145beta is out for closed testing**

- Account page rebuilt with categories: profile and avatar, username, password, 2FA, notifications and devices
- Pick your account and username during setup; the random nickname prompt is gone
- Devices & sessions: send servers to a device, unlink, copy the device ID, refresh
- Session token now refreshes itself, so the device list no longer empties after an hour
- ZIP and backup import rewritten: central directory with local-header fallback, tolerant tail, Forge 1.12.2 jar detected and promoted
- Setup no longer adopts or deletes servers on your other devices
- Light mode uses the same server cards as dark mode; bottom sheets and navigation bars fixed
- Support address is support@kodaserv.eu everywhere
- Bedrock stays off while Geyser moves to NetherNet

Java servers keep working normally. Full notes and the F-Droid repository:
https://host.kodanetwork.eu/fdroid/
