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
-- run this in the supabase SQL editor to get rid of the database blocker!

-- 1. kick out all the old (probably broken) select policies
DROP POLICY IF EXISTS "Public can select" ON public.koda_users;
DROP POLICY IF EXISTS "Allow select for everyone" ON public.koda_users;

DROP POLICY IF EXISTS "Public can select" ON public.koda_servers;
DROP POLICY IF EXISTS "Allow select for servers" ON public.koda_servers;
DROP POLICY IF EXISTS "Allow select for servers public" ON public.koda_servers;

-- 2. fresh clean policies so the website can always read these two tables
CREATE POLICY "Public can select" ON public.koda_users FOR SELECT USING (true);
CREATE POLICY "Public can select" ON public.koda_servers FOR SELECT USING (true);
