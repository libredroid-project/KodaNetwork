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
# Polski - Polish language pack

> Note: the app itself ships Polish built in (`app/src/main/res/values-pl`,
> selectable under Settings -> Language). This pack stays in the repository as
> a reference implementation of the language-pack format for extensions.

A real, partial Polish translation of the app: 150 of the most visible strings
(main screen, dashboard, console, files, plugins, automations, notifications
and the extension hub itself). Everything not in the pack stays in the app
language, so the pack is useful from day one and can grow.

## Install

```bash
python3 extensions/tools/pack_extension.py extensions/examples/language-polish
```

Then in the app: **Settings -> EXTENSIONS -> +**, pick the `.kodaext` file, tap
the new entry (it has no screen, so the actions sheet opens) and press
**Apply** on *Polski*. Restart the app and it speaks Polish.

## Extending the pack

* Every key must be a string resource name of the app. Look them up in
  [`app/src/main/res/values/strings.xml`](../../../app/src/main/res/values/strings.xml) -
  the English text there is the source of truth.
* Keep placeholders like `%1$s` in the translation.
* Check before packing:

  ```bash
  python3 extensions/tools/check_language_pack.py extensions/examples/language-polish/lang/pl.json
  ```

  Unknown keys do nothing at runtime, this tool catches them on the computer.
* When you ship an update, raise `versionCode` in `manifest.json` so the app
  installs it as an update.
