#!/usr/bin/env python3
# Copyright (c) 2026 KodaHosting
#
# This file is part of KodaHosting (KodaNetwork).
# KodaHosting is free software: you can redistribute it and/or modify it under the
# terms of the GNU General Public License as published by the Free Software
# Foundation, version 3 of the License.
#
# KodaHosting is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY, without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
# PARTICULAR PURPOSE. See the GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License along with
# KodaHosting. If not, see <https://www.gnu.org/licenses/>.
#
# SPDX-FileCopyrightText: 2026 KodaHosting
# SPDX-License-Identifier: GPL-3.0-only
"""checks a language pack against the string resources of the app.

usage:
    python3 extensions/tools/check_language_pack.py <pack.json> [strings.xml]

every key of the pack has to be a string resource name of the app
(app/src/main/res/values/strings.xml), keys that do not exist are dead weight:
the app looks up resources by name, so a typo silently does nothing.
the script prints the coverage and exits non-zero when it finds unknown keys.
"""

import json
import re
import sys
from pathlib import Path

DEFAULT_STRINGS = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "res" / "values" / "strings.xml"


def load_string_names(path):
    text = path.read_text(encoding="utf-8")
    return set(re.findall(r'<string name="([a-z0-9_]+)"', text))


def main():
    if len(sys.argv) < 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2

    pack_path = Path(sys.argv[1])
    strings_path = Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_STRINGS

    if not pack_path.is_file():
        print(f"error: pack not found: {pack_path}", file=sys.stderr)
        return 2
    if not strings_path.is_file():
        print(f"error: strings.xml not found: {strings_path}", file=sys.stderr)
        return 2

    pack = json.loads(pack_path.read_text(encoding="utf-8"))
    if not isinstance(pack, dict):
        print("error: the pack must be a JSON object of resource names", file=sys.stderr)
        return 2

    known = load_string_names(strings_path)
    unknown = sorted(k for k in pack.keys() if k not in known)
    empty = sorted(k for k, v in pack.items() if not isinstance(v, str) or not v.strip())

    print(f"pack    {pack_path}")
    print(f"strings {len(pack)} entries, {len(pack) - len(unknown) - len(empty)} usable")
    print(f"app     {len(known)} string resources")

    if empty:
        print(f"\nempty or non-string values ({len(empty)}):")
        for key in empty[:20]:
            print(f"  {key}")

    if unknown:
        print(f"\nunknown keys ({len(unknown)}) - these do nothing, fix the names:")
        for key in unknown[:40]:
            print(f"  {key}")
        return 1

    print("\nall keys match app string resources")
    return 0


if __name__ == "__main__":
    sys.exit(main())
