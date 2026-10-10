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
# Koda Lab - the test bench

The most complete extension this system supports today. It is built to *test*
the extension feature, not to be pretty: every bridge call is logged with its
raw response, and the Self-Test page runs the whole API surface as assertions.

## What it exercises

| Area | What happens |
|---|---|
| `window.koda` read API | `locale`, `theme`, `servers.list`, `servers.get`, `console.tail`, `storage.*`, `ui.toast`, `ui.confirm` |
| Permissions | declares **all** permissions (including the phase-2 ones) so the scaffold is complete |
| Language pack | contributes a small Spanish demo pack (`lang/es.json`) |
| Themes | contributes two seeds: *Lab Cyan* and *Lab Magenta* |
| UI tweaks | hides `btn_github_main`, adds 4 console quick commands |
| Automations | ships `automations.json` with 3 rules (format only, engine follows in the next phase) |
| Storage | round-trip, per-key sizes, export/import as JSON, and a quota test that writes until the host refuses |
| Rate limit | Self-Test and Stress fire 60/120 rapid calls to show the host's "rate limited" answer |
| Sandbox | asserts that a remote `fetch()` is blocked and that unknown methods are unreachable |

## Install and use

```bash
python3 extensions/tools/pack_extension.py extensions/examples/koda-lab
```

Then in the app: **Settings -> EXTENSIONS -> +**, pick the `.kodaext`, tap the
entry (it has a screen, so it opens here) and try the tabs.

The contributions are applied separately: long press the row in the hub (or use
the `...` menu here) and pick **Actions** - themes and the language pack are
activated there.

## Notes

* The Spanish pack is deliberately small: it shows how partial packs behave
  (everything missing stays in the app language).
* `automations.json` is validated on install but not executed yet; the file
  documents the format the next phase will run.
* While this extension is enabled the GitHub button in the main top bar is
  hidden (that is the `ui.hide` demo). Disable the extension to get it back.
