-- Copyright (c) 2026 KodaHosting
--
-- This file is part of KodaHosting (KodaNetwork).
-- KodaHosting is free software: you can redistribute it and/or modify it under the
-- terms of the GNU General Public License as published by the Free Software
-- Foundation, version 3 of the License.
--
-- KodaHosting is distributed in the hope that it will be useful, but WITHOUT ANY
-- WARRANTY, without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
-- PARTICULAR PURPOSE. See the GNU General Public License for more details.
--
-- You should have received a copy of the GNU General Public License along with
-- KodaHosting. If not, see <https://www.gnu.org/licenses/>.
--
-- SPDX-FileCopyrightText: 2026 KodaHosting
-- SPDX-License-Identifier: GPL-3.0-only
-- 2026-08-17: auto-hibernate moved from 14 to 180 days
-- (applied live via cron.alter_job on 2026-08-17, see supabase/backup-20260817/)
-- job 4 (active, runs daily at 03:00): hibernates servers offline for >180 days
-- and removes their DNS link via the Edge Function.

SELECT cron.alter_job(
  4,
  null,
  replace(
    (SELECT command FROM cron.job WHERE jobid = 4),
    'INTERVAL ''14 days''',
    'INTERVAL ''180 days'''
  ),
  null, null, null
);
