<!--
  Copyright (c) 2026 KodaHosting

  This file is part of KodaHosting (KodaNetwork).
  KodaHosting is free software: you can redistribute it and/or modify it under the
  terms of the GNU General Public License as published by the Free Software
  Foundation, version 3 of the License.

  KodaHosting is distributed in the hope that it will be useful, but WITHOUT ANY
  WARRANTY, without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
  PARTICULAR PURPOSE. See the GNU General Public License for more details.

  You should have received a copy of the GNU General Public License along with
  KodaHosting. If not, see <https://www.gnu.org/licenses/>.

  SPDX-FileCopyrightText: 2026 KodaHosting
  SPDX-License-Identifier: GPL-3.0-only
-->
# KodaHosting Extensions

Extensions ("Erweiterungen" in the app) are small packages that add your own
screens, actions and automations to the KodaHosting app. They are plain HTML,
CSS and JavaScript, running inside a sandboxed WebView, and they talk to the
app through a versioned JavaScript API (`window.koda`). No native code, no
recompiling the app, no Play Store review of your own.

The whole system is open source (GPL-3.0), exactly like the app itself:
format, runtime, API and the example extensions in this folder.

> Status: the extension host, the bridge API version 1 (read-only) and the
> sideload install are live. Server actions, automations and the in-app store
> follow in the next phases; the manifest fields for them are already final.

## Package format

An extension is a ZIP file with the `.kodaext` extension, files at the ZIP
root (a single wrapping folder is tolerated, but do not ship one):

```
manifest.json          required
ui/index.html          required when the extension has a screen
ui/app.js, ui/style.css
automations.json       optional (phase 2)
icon.png               optional, 128x128 recommended
README.md, LICENSE     recommended
```

### manifest.json

```json
{
  "schema": 1,
  "id": "eu.example.live-log",
  "name": "Live Log",
  "version": "1.0.0",
  "versionCode": 1,
  "author": "YourName",
  "description": "Shows the console of your server live.",
  "license": "GPL-3.0-or-later",
  "source_url": "https://github.com/you/live-log",
  "minAppVersion": 8514,
  "permissions": ["servers.read", "console.read", "storage"],
  "ui": { "entry": "ui/index.html", "title": "Live Log" },
  "automations": "automations.json"
}
```

| Field | Required | Rules |
|---|---|---|
| `schema` | yes | must be `1` |
| `id` | yes | lowercase reverse-domain, `a-z0-9.-_`, at most 64 chars, at least two segments |
| `name` | yes | at most 48 chars |
| `version` | yes | numbers and dots, e.g. `1.2.0` |
| `versionCode` | yes | integer >= 1, must grow with every update |
| `license` | yes | SPDX style identifier, shown in the app |
| `source_url` | recommended | https URL, shown as "source code" link |
| `minAppVersion` | no | app `versionCode` the extension needs |
| `permissions` | no | see below, unknown values are rejected |
| `ui.entry` | for screens | relative path inside the package |
| `contributes` | no | language packs, themes, UI tweaks - see below |
| `automations` | phase 2 | relative path to the automations file |

The JSON schema for editors is in [`schema/manifest.schema.json`](schema/manifest.schema.json).

## Permissions

Nothing is granted implicitly: every call the extension makes is checked
against this list, and the app shows it to the user before install.

| Permission | Allows |
|---|---|
| `storage` | `koda.storage.*` (1 MB per extension) |
| `servers.read` | `koda.servers.list()`, `koda.servers.get(id)` |
| `console.read` | `koda.console.tail(id, lines)` |
| `console.send` | `koda.console.send(id, command)` (phase 2, blocked commands stay blocked) |
| `servers.control` | `koda.server.control(id, "start" \| "stop" \| "restart")` (phase 2, with in-app confirmation) |
| `players.read` | `koda.players.list(id)` (phase 2) |
| `players.actions` | player actions like heal, kick, gamemode (phase 2) |
| `files.read` | `koda.files.list/read` inside the server folder (phase 2) |
| `notifications` | `koda.notify(title, text)` (phase 2) |
| `http:<host>` | `koda.http.fetch(...)` to exactly that host, https only (phase 2) |

## The `window.koda` API (version 1)

`window.koda` is injected into every HTML file of your extension before your
own scripts run, so you can use it right away. All methods return promises;
every promise resolves to a JSON object, errors come back as `{ "error": "..." }`.

```js
const { servers } = await koda.servers.list();
const { lines } = await koda.console.tail(servers[0].id, 200);
await koda.storage.set("lastServer", servers[0].id);
const { value } = await koda.storage.get("lastServer");
const theme = await koda.theme();          // colours of the app, for matching your UI
const { lang } = await koda.locale();      // "en", "de", "zh", ...
koda.ui.toast("Hello");
const { ok } = await koda.ui.confirm("Restart?", "Players will be kicked.");
```

`koda.api` is `1`; check it so your extension can show a friendly message on
newer runtime versions. Server objects use a fixed whitelist of fields: `id`,
`name`, `type`, `mcVersion`, `state`, `port`, `address`, `ramMB`,
`maxPlayers`, `isDatabase`. Nothing else is exposed, and no secrets are
reachable from an extension at all (no tokens, no chat, no admin channel).

## What an extension can contribute

An extension can do more than show its own screen: with `contributes` in the
manifest it extends the app itself with **language packs**, **themes** and
**UI tweaks**. Everything is user controlled, nothing changes until it is
applied in the extension's actions (tap an extension that has no screen,
long press any row in the hub, or use the `...` menu inside its screen).

```json
"contributes": {
  "languages": [
    { "code": "pl", "name": "Polski", "file": "lang/pl.json" }
  ],
  "themes": [
    { "id": "ocean", "name": "Ocean", "color": "#006874" }
  ],
  "ui": {
    "hide": ["btn_github_main"],
    "console_chips": ["/tps", "/list", "time set day"]
  }
}
```

### Language packs

A language pack is a JSON object that maps **string resource names** of the app
to your translation. Missing keys fall back to the app language, so a pack can
grow over time and be shipped long before it is complete.

```json
{
  "tab_dashboard": "Panel",
  "jars_title": "MODY I WTYCZKI",
  "sd_toast_log_copied": "Skopiowano log!",
  "ext_ui_chips_count": "%1$d dodatkowych komend w konsoli serwera."
}
```

* Resource names are the `name="..."` values in
  [`app/src/main/res/values/strings.xml`](../app/src/main/res/values/strings.xml).
* Keep placeholders such as `%1$s` inside the translated text.
* Validate before shipping:
  `python3 extensions/tools/check_language_pack.py lang/pl.json`
  (unknown keys silently do nothing, the tool catches typos).
* Applying a pack needs an app restart. See
  [`examples/language-polish`](examples/language-polish) for a real, working pack.
* Language packs and contributions require app build **8514** or newer.

### Themes

A theme is an accent seed colour - or a complete palette on top of it:

```json
"themes": [
  { "id": "ocean", "name": "Ocean", "color": "#006874" },
  { "id": "midnight", "name": "Midnight", "color": "#00D9FF",
    "colors": {
      "primary": "#00D9FF", "surface": "#07070F", "card": "#14141F",
      "text": "#EDEDF5", "text_dim": "#8C8C9C", "outline": "#2A2A3A",
      "online": "#3FA34D", "error": "#FF4455"
    } }
]
```

Every contributed theme gets **its own switch** in the extension's actions
sheet: turning one on applies it (and turns the previous one off), turning it
off returns to the app's own colour mode. Seed-only themes go through the app's
Material 3 seed mechanism; themes with `colors` override whole colour roles, so
they retheme the entire styled UI. Both apply in Material 3 mode.

### Server tabs

```json
"server_tabs": [
  { "id": "lab", "title": "Lab", "target": "console" }
]
```

Each entry adds a tab to every server screen. The tab loads the extension's page
(own `entry`, otherwise `ui.entry`) with the context appended as a hash:
`#<target>&server=<server id>`. Read it in your page with
`location.hash` - that is how an extension knows which server it belongs to and
which view to open. At most 4 tabs per extension (not available in the optional
sleek layout, which has no shared panel container).

### Settings entries

```json
"settings": [
  { "title": "My extension", "subtitle": "What it does", "target": "overview" }
]
```

Each entry shows up in the app's settings screen right below the extension hub,
with a small **info button** that names the extension (name, version, author,
license) so users can see where the row comes from. Tapping the row opens the
extension's screen; `target` is appended to the page URL as `#target`, so the
extension can deep-link into one of its own tabs. At most 4 entries per
extension.

### UI tweaks

* `hide` takes **view ids** from the app layouts (`android:id="@+id/..."`,
  e.g. `btn_github_main` in `app/src/main/res/layout/activity_main.xml`).
  The elements are hidden while the extension is enabled.
* `console_chips` adds quick commands to the server console chip row.
* UI tweaks are active while the extension is enabled, there is no extra switch.

## Making the UI look like the app

The host screen is pure Koda style; your web UI sits inside it. Copy
[`examples/live-log/ui/koda-ui.css`](examples/live-log/ui/koda-ui.css) into
your extension and use the CSS variables (`--koda-orange`, `--koda-bg`,
`--koda-cell`, ...). `koda.theme()` returns the same values at runtime.

## Safety rules of the sandbox

* Your extension only ever runs locally: the WebView loads files from the
  installed package, everything else is blocked before it leaves the WebView.
* There is no direct network access from the page. If you need data from the
  internet, declare `http:<host>` and use `koda.http.fetch` (phase 2).
* Assets must be bundled. No CDNs, no remote scripts, no eval-driven loading
  of third party code. Target ES2019 so older system WebViews keep working.
* File paths are checked, packages are size capped, and a crashing screen can
  never take the app itself down.

## Examples in this repository

| Example | Shows |
|---|---|
| [`examples/koda-lab`](examples/koda-lab) | the test bench: every bridge call with raw responses, all contribution types, storage quota, rate limit, sandbox assertions |
| [`examples/live-log`](examples/live-log) | a screen in the app: WebView UI + `koda.console.tail` live log |
| [`examples/language-polish`](examples/language-polish) | a real Polish language pack (150 strings) |
| [`examples/theme-ocean`](examples/theme-ocean) | a teal Material 3 theme, a hidden button, three console commands |
| [`examples/crash-alarm`](examples/crash-alarm) | automations file format (engine follows in the next phase) |

## Testing your extension

1. Pack your folder:

   ```bash
   python3 extensions/tools/pack_extension.py extensions/examples/live-log
   ```

   The tool validates the manifest with the same rules the app uses
   (including `contributes`), so an unknown view id or a missing language file
   fails on the computer instead of on the phone.

2. In the app: Settings, "EXTENSIONS", "+", pick the `.kodaext` file.
3. Tap the entry to open your screen. The `...` menu has "Reload" for quick
   iteration and "Remove".

During development you can keep the folder structure from the examples and
re-pack after each change; the app keeps your extension's own storage when
you reinstall an updated version.

## Licensing

The app and this extension system are GPL-3.0. Your extension is your own
work: pick any license you like and put it into `license` and `LICENSE`, the
app shows it to users. Because extensions run as interpreted code against a
documented API, they stay a separate work; if you reuse app code, keep it
GPL-compatible.

## Publishing in the store

The in-app store ("Entdecken") is the next phase: it will list reviewed
extensions with their license and source link. Until then, share your
`.kodaext` file directly; sideloading is fully supported.
