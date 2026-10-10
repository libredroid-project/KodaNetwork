#!/usr/bin/env bash
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
# pushes the AngelAuraMC android-arm64 JRE builds into the Supabase "artifacts" bucket.
# the app tries these urls first (Supabase first) and falls back to GitHub while
# the files are not uploaded yet.
#
# one time setup:
#   1. create the "artifacts" bucket (Supabase Dashboard -> Storage -> New bucket, PUBLIC)
#   2. copy the service role key from dashboard -> Settings -> API
#
# how to run it:
#   SERVICE_KEY=eyJ... ./supabase/upload-jres.sh

set -euo pipefail
PROJECT_URL="${SUPABASE_URL:-https://your-project.supabase.co}"
: "${SERVICE_KEY:?Bitte SERVICE_KEY (Supabase service_role) setzen}"

upload() {
  local version="$1" tag="$2" file="$3"
  echo ">> Lade $file (Java $version)..."
  curl -sL -o "/tmp/$file" "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/$tag/$file"
  curl -s -X POST "$PROJECT_URL/storage/v1/object/artifacts/openjdk$version/$file" \
    -H "Authorization: Bearer $SERVICE_KEY" \
    -H "apikey: $SERVICE_KEY" \
    -H "Content-Type: application/octet-stream" \
    --data-binary "@/tmp/$file"
  echo ""
  rm -f "/tmp/$file"
}

upload 8  "download_jre8"  "jre8-android-arm64.tar.xz"
upload 17 "download_jre17" "jre17-android-arm64.tar.xz"
upload 21 "download_jre21" "jre21-android-arm64.tar.xz"

echo "Fertig. Die App erwartet:"
echo "  $PROJECT_URL/storage/v1/object/public/artifacts/openjdk{8,17,21}/openjdk{8,17,21}-android-arm64.tar.xz"
echo "(Dateinamen im Bucket auf openjdk<N>-android-arm64.tar.xz umbenennen, falls noetig)"
