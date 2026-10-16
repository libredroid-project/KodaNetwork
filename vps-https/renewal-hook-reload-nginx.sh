#!/bin/sh
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
#
# certbot deploy hook: nginx caches certificates in memory, so a renewal alone
# does not change what is served. copy this to
#   /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
# (chmod +x) and every successful renewal reloads nginx right away - but only
# when the configuration still passes the test.
nginx -t >/dev/null 2>&1 && systemctl reload nginx
exit 0
