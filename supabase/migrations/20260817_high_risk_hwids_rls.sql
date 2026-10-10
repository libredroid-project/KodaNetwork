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
-- 2026-08-17: turn RLS on for high_risk_hwids
-- it was off before, so with the anon key anyone could read AND write the whole
-- table (edit or delete risk entries, no questions asked).
-- now public can only read, all writes go through the
-- SECURITY DEFINER rpc report_high_risk().
-- applied live on 2026-08-17 (rollback: supabase/backup-20260817/03_policies_rls_backup.sql).

ALTER TABLE public.high_risk_hwids ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Allow public read access" ON public.high_risk_hwids;
CREATE POLICY "Allow public read access" ON public.high_risk_hwids
  FOR SELECT TO public USING (true);
