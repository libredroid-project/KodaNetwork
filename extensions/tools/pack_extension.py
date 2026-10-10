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
"""packs an extension folder into a .kodaext package.

usage:
    python3 extensions/tools/pack_extension.py extensions/examples/live-log
    python3 extensions/tools/pack_extension.py <folder> -o /tmp/live-log.kodaext

validates manifest.json with the same rules the app applies
(see app/src/main/java/eu/kodanetwork/mchost/extension/ExtensionManifest.java),
writes the package with all files at the ZIP root and prints the size and SHA-256.
"""

import argparse
import hashlib
import json
import os
import re
import sys
import zipfile

ID_RE = re.compile(r"^[a-z0-9]+(\.[a-z0-9_-]+)+$")
VERSION_RE = re.compile(r"^[0-9]+(\.[0-9]+)*$")
HOST_RE = re.compile(r"^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
LANG_CODE_RE = re.compile(r"^[a-z]{2}(-[A-Za-z]{2,4})?$")
PACK_ID_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{1,31}$")
HEX_COLOR_RE = re.compile(r"^#[0-9A-Fa-f]{6}$")
VIEW_ID_RE = re.compile(r"^[a-z][a-z0-9_]{1,48}$")
THEME_COLOR_KEYS = {"primary", "surface", "card", "text", "text_dim", "outline", "online", "error"}

BASE_PERMISSIONS = {
    "storage", "servers.read", "console.read", "console.send", "servers.control",
    "players.read", "players.actions", "files.read", "notifications",
}
SKIP_DIRS = {".git", "__pycache__", ".idea", "node_modules"}
SKIP_FILES = {".DS_Store"}


class PackError(Exception):
    pass


def check_relative_path(value, field):
    value = value.strip().replace("\\", "/")
    if not value:
        raise PackError(f"{field}: missing")
    if value.startswith("/") or ".." in value or ":" in value:
        raise PackError(f"{field}: illegal path")
    return value


def validate_manifest(manifest, folder):
    if not isinstance(manifest, dict):
        raise PackError("manifest.json must be a JSON object")

    if manifest.get("schema") != 1:
        raise PackError("schema must be 1")

    ext_id = manifest.get("id", "")
    if not isinstance(ext_id, str) or not ID_RE.match(ext_id) or len(ext_id) > 64:
        raise PackError(f"invalid id: {ext_id!r} (lowercase reverse-domain)")

    name = manifest.get("name", "")
    if not isinstance(name, str) or not name.strip() or len(name) > 48:
        raise PackError("name must be 1..48 characters")

    version = manifest.get("version", "")
    if not isinstance(version, str) or not VERSION_RE.match(version):
        raise PackError("version must look like 1.0.0")

    version_code = manifest.get("versionCode")
    if not isinstance(version_code, int) or isinstance(version_code, bool) or version_code < 1:
        raise PackError("versionCode must be an integer >= 1")

    min_app = manifest.get("minAppVersion", 0)
    if not isinstance(min_app, int) or min_app < 0:
        raise PackError("minAppVersion must be a non-negative integer")

    license_id = manifest.get("license", "")
    if not isinstance(license_id, str) or not license_id.strip():
        raise PackError("license is required, e.g. \"GPL-3.0-or-later\" or \"MIT\"")

    permissions = manifest.get("permissions", [])
    if not isinstance(permissions, list) or len(permissions) > 16:
        raise PackError("permissions must be a list with at most 16 entries")
    for permission in permissions:
        if not isinstance(permission, str):
            raise PackError("permissions must be strings")
        normalized = permission.strip().lower()
        if normalized.startswith("http:"):
            if not HOST_RE.match(normalized[len("http:"):]):
                raise PackError(f"invalid http permission: {permission!r}")
        elif normalized not in BASE_PERMISSIONS:
            raise PackError(f"unknown permission: {permission!r}")

    for key in ("source_url", "homepage"):
        value = manifest.get(key)
        if value is not None and (not isinstance(value, str) or not value.startswith("https://")):
            raise PackError(f"{key} must be an https URL")

    ui = manifest.get("ui")
    if ui is not None:
        if not isinstance(ui, dict):
            raise PackError("ui must be an object")
        entry = check_relative_path(str(ui.get("entry", "")), "ui.entry")
        if not os.path.isfile(os.path.join(folder, entry)):
            raise PackError(f"ui.entry not found in folder: {entry}")
        title = ui.get("title")
        if title is not None and (not isinstance(title, str) or len(title) > 32):
            raise PackError("ui.title must be at most 32 characters")

    automations = manifest.get("automations")
    if automations is not None:
        automations = check_relative_path(str(automations), "automations")
        if not automations.lower().endswith(".json"):
            raise PackError("automations must be a .json file")
        if not os.path.isfile(os.path.join(folder, automations)):
            raise PackError(f"automations file not found: {automations}")

    validate_contributes(manifest.get("contributes"), folder)
    return ext_id, version_code


def validate_contributes(contributes, folder):
    """what the extension adds to the app: contributes.languages, .themes and .ui."""
    if contributes is None:
        return
    if not isinstance(contributes, dict):
        raise PackError("contributes must be an object")

    languages = contributes.get("languages")
    if languages is not None:
        if not isinstance(languages, list) or len(languages) > 8:
            raise PackError("contributes.languages must be a list with at most 8 entries")
        seen = set()
        for entry in languages:
            if not isinstance(entry, dict):
                raise PackError("languages entries must be objects")
            code = str(entry.get("code", "")).strip()
            if not LANG_CODE_RE.match(code):
                raise PackError(f"invalid language code: {code!r} (e.g. \"pl\" or \"pt-BR\")")
            if code.lower() in seen:
                raise PackError(f"duplicate language code: {code}")
            seen.add(code.lower())
            name = str(entry.get("name", "")).strip()
            if not name or len(name) > 32:
                raise PackError("language name must be 1..32 characters")
            lang_file = check_relative_path(str(entry.get("file", "")), "languages.file")
            if not lang_file.lower().endswith(".json"):
                raise PackError("language packs must be .json files")
            if not os.path.isfile(os.path.join(folder, lang_file)):
                raise PackError(f"language pack not found: {lang_file}")

    themes = contributes.get("themes")
    if themes is not None:
        if not isinstance(themes, list) or len(themes) > 8:
            raise PackError("contributes.themes must be a list with at most 8 entries")
        seen = set()
        for entry in themes:
            if not isinstance(entry, dict):
                raise PackError("themes entries must be objects")
            theme_id = str(entry.get("id", "")).strip()
            if not PACK_ID_RE.match(theme_id):
                raise PackError(f"invalid theme id: {theme_id!r}")
            if theme_id in seen:
                raise PackError(f"duplicate theme id: {theme_id}")
            seen.add(theme_id)
            name = str(entry.get("name", "")).strip()
            if not name or len(name) > 32:
                raise PackError("theme name must be 1..32 characters")
            color = str(entry.get("color", "")).strip()
            if not HEX_COLOR_RE.match(color):
                raise PackError(f"theme color must look like #RRGGBB: {color!r}")
            colors = entry.get("colors")
            if colors is not None:
                if not isinstance(colors, dict) or len(colors) > 8:
                    raise PackError("theme colors must be an object with at most 8 entries")
                for key, value in colors.items():
                    if key not in THEME_COLOR_KEYS:
                        raise PackError(f"unknown theme color: {key!r}")
                    if not isinstance(value, str) or not HEX_COLOR_RE.match(value):
                        raise PackError(f"theme color {key} must look like #RRGGBB")

    server_tabs = contributes.get("server_tabs")
    if server_tabs is not None:
        if not isinstance(server_tabs, list) or len(server_tabs) > 4:
            raise PackError("contributes.server_tabs must be a list with at most 4 entries")
        seen_tabs = set()
        for entry in server_tabs:
            if not isinstance(entry, dict):
                raise PackError("server_tabs entries must be objects")
            tab_id = str(entry.get("id", "")).strip()
            if not PACK_ID_RE.match(tab_id):
                raise PackError(f"invalid server tab id: {tab_id!r}")
            if tab_id in seen_tabs:
                raise PackError(f"duplicate server tab id: {tab_id}")
            seen_tabs.add(tab_id)
            title = str(entry.get("title", "")).strip()
            if not title or len(title) > 32:
                raise PackError("server tab title must be 1..32 characters")
            tab_entry = entry.get("entry")
            if tab_entry is not None:
                tab_entry = check_relative_path(str(tab_entry), "server_tabs.entry")
                if not tab_entry.lower().endswith(".html"):
                    raise PackError("server tab entry must be an .html file")
                if not os.path.isfile(os.path.join(folder, tab_entry)):
                    raise PackError(f"server tab entry not found: {tab_entry}")
            target = entry.get("target")
            if target is not None and (not isinstance(target, str) or not PACK_ID_RE.match(target)):
                raise PackError(f"invalid server tab target: {target!r}")

    settings = contributes.get("settings")
    if settings is not None:
        if not isinstance(settings, list) or len(settings) > 4:
            raise PackError("contributes.settings must be a list with at most 4 entries")
        for entry in settings:
            if not isinstance(entry, dict):
                raise PackError("settings entries must be objects")
            title = str(entry.get("title", "")).strip()
            if not title or len(title) > 32:
                raise PackError("settings title must be 1..32 characters")
            subtitle = entry.get("subtitle")
            if subtitle is not None and (not isinstance(subtitle, str) or len(subtitle) > 80):
                raise PackError("settings subtitle must be at most 80 characters")
            target = entry.get("target")
            if target is not None and (not isinstance(target, str) or not PACK_ID_RE.match(target)):
                raise PackError(f"invalid settings target: {target!r}")

    ui = contributes.get("ui")
    if ui is not None:
        if not isinstance(ui, dict):
            raise PackError("contributes.ui must be an object")
        hide = ui.get("hide")
        if hide is not None:
            if not isinstance(hide, list) or len(hide) > 24:
                raise PackError("contributes.ui.hide must be a list with at most 24 entries")
            for view_id in hide:
                if not isinstance(view_id, str) or not VIEW_ID_RE.match(view_id.strip()):
                    raise PackError(f"invalid view id: {view_id!r} (e.g. \"btn_github_main\")")
        chips = ui.get("console_chips")
        if chips is not None:
            if not isinstance(chips, list) or len(chips) > 16:
                raise PackError("contributes.ui.console_chips must be a list with at most 16 entries")
            for chip in chips:
                if not isinstance(chip, str) or not chip.strip() or len(chip) > 64 or "\n" in chip:
                    raise PackError(f"invalid console chip: {chip!r}")


def collect_files(folder):
    files = []
    for root, dirs, names in os.walk(folder):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in names:
            if name in SKIP_FILES:
                continue
            absolute = os.path.join(root, name)
            relative = os.path.relpath(absolute, folder).replace("\\", "/")
            if relative.startswith(".."):
                raise PackError(f"file outside the folder: {relative}")
            files.append((absolute, relative))
    files.sort(key=lambda pair: pair[1])
    return files


def main():
    parser = argparse.ArgumentParser(description="Pack a KodaHosting extension folder into a .kodaext file.")
    parser.add_argument("folder", help="extension folder that contains manifest.json")
    parser.add_argument("-o", "--output", help="output file (default: <id>-<version>.kodaext next to the folder)")
    args = parser.parse_args()

    folder = os.path.abspath(args.folder)
    if not os.path.isdir(folder):
        print(f"error: folder not found: {folder}", file=sys.stderr)
        return 1

    manifest_path = os.path.join(folder, "manifest.json")
    if not os.path.isfile(manifest_path):
        print("error: manifest.json not found in the folder", file=sys.stderr)
        return 1

    try:
        with open(manifest_path, "r", encoding="utf-8") as handle:
            manifest = json.load(handle)
    except json.JSONDecodeError as error:
        print(f"error: manifest.json is not valid JSON: {error}", file=sys.stderr)
        return 1

    try:
        ext_id, _ = validate_manifest(manifest, folder)
        files = collect_files(folder)
    except PackError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    output = args.output or os.path.join(
        os.path.dirname(folder), f"{ext_id}-{manifest['version']}.kodaext")

    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for absolute, relative in files:
            archive.write(absolute, relative)

    digest = hashlib.sha256()
    with open(output, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 16), b""):
            digest.update(chunk)

    size = os.path.getsize(output)
    print(f"packed {len(files)} files")
    print(f"output  {output}")
    print(f"size    {size} bytes")
    print(f"sha256  {digest.hexdigest()}")
    print()
    print("Test it: open the app, Settings, EXTENSIONS, +, and pick this file.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
