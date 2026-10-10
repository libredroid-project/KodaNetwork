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
# Branding assets

`kodadash_icon_source.png` is the master file for the **KodaDash and website** mark.
`kodadash_icon.png` is the square crop used for generation.

**The Android app has its own, unchanged launcher icon.** These assets are not used for it.

| Asset | Path |
| --- | --- |
| KodaDash plugin icon | `KodaDash/src/main/resources/icon.png` |
| Website logo + favicon | `../website/assets/logo.png` |
| Admin panel logo | `../admin_panel/public/logo.png` |
| Lobby / transfer plugins | use the app icon (unchanged) |

Regenerate with `python3 tools/make_brand_icons.py` after replacing the source image.
