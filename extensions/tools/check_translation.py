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
"""builds and checks a translation of the app strings.

usage:
    python3 tools/check_translation.py app/src/main/res/values-pl/strings.xml
    python3 tools/check_translation.py app/src/main/res/values-pl/strings.xml --build \
        --from app/src/main/res/values/strings.xml --map pl_1.json pl_2.json

checks:
  * the XML is well formed and every entry is <string name="...">
  * every key exists in the default strings.xml, a typo would be dead weight
  * format placeholders (%1$s, %2$d, %s) match the default string exactly -
    a mismatch crashes at runtime with IllegalFormatException
  * prints the coverage (how many of the default strings are translated)
"""

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

PLACEHOLDER_RE = re.compile(r"%(?:\d+\$)?[sdfx]|%%")
STRING_RE = re.compile(r'<string name="([a-z0-9_]+)">(.*?)</string>', re.S)


def load_default(path):
    text = Path(path).read_text(encoding="utf-8")
    return dict(STRING_RE.findall(text))


def placeholders(text):
    return sorted(PLACEHOLDER_RE.findall(text.replace("%%", "")))


def escape(text):
    return (text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("'", "\\'").replace('"', '\\"'))


def build(default_path, maps, out_path):
    default = load_default(default_path)
    merged = {}
    for name in maps:
        with open(name, encoding="utf-8") as handle:
            for key, value in json.load(handle).items():
                if key not in default:
                    print(f"error: '{key}' is not a string resource of the app", file=sys.stderr)
                    return 1
                merged[key] = value

    lines = ["<?xml version=\"1.0\" encoding=\"utf-8\"?>",
             "<!-- Polish translation. Untranslated strings fall back to the default language. -->",
             "<resources>"]
    for key in default:
        if key in merged:
            lines.append(f'    <string name="{key}">{escape(merged[key])}</string>')
    lines.append("</resources>")

    out = Path(out_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {out} with {len(merged)} strings")
    return 0


def check(path, default_path):
    default = load_default(default_path)
    try:
        tree = ET.parse(path)
    except ET.ParseError as error:
        print(f"error: invalid XML: {error}", file=sys.stderr)
        return 1

    translated = {}
    for element in tree.getroot():
        if element.tag != "string":
            print(f"error: unexpected element <{element.tag}>", file=sys.stderr)
            return 1
        translated[element.get("name")] = element.text or ""

    problems = 0
    for key, text in translated.items():
        if key not in default:
            print(f"unknown key: {key}")
            problems += 1
            continue
        expected = placeholders(default[key])
        actual = placeholders(text)
        if expected != actual:
            print(f"placeholder mismatch in {key}: default {expected} vs translation {actual}")
            problems += 1

    missing = [k for k in default if k not in translated]
    print(f"translated   {len(translated)} / {len(default)} ({100 * len(translated) // max(1, len(default))}%)")
    print(f"untranslated {len(missing)} (these fall back to the default language)")
    if problems:
        print(f"\n{problems} problem(s) found", file=sys.stderr)
        return 1
    print("placeholders and keys are consistent")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("translation", help="the translated values-XX/strings.xml")
    parser.add_argument("--build", action="store_true", help="build the file from JSON maps instead of checking it")
    parser.add_argument("--from", dest="default", default="app/src/main/res/values/strings.xml")
    parser.add_argument("--map", nargs="*", default=[], help="JSON files with key -> translation")
    args = parser.parse_args()

    if args.build:
        if not args.map:
            print("error: --build needs at least one --map file", file=sys.stderr)
            return 2
        return build(args.default, args.map, args.translation)
    return check(args.translation, args.default)


if __name__ == "__main__":
    sys.exit(main())
