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
-- copy this into the supabase dashboard, SQL editor tab, and run it!

-- 1. table for app-wide settings (maintenance mode and friends)
CREATE TABLE IF NOT EXISTS public.app_settings (
    key VARCHAR(255) PRIMARY KEY,
    value JSONB NOT NULL
);

-- seed the initial maintenance mode values
INSERT INTO public.app_settings (key, value)
VALUES (
    'maintenance_mode',
    '{"active": false, "reason": "Wir führen gerade Wartungsarbeiten durch. Bitte habe etwas Geduld.", "duration_minutes": 60}'::jsonb
)
ON CONFLICT (key) DO NOTHING;

-- RLS for app_settings
ALTER TABLE public.app_settings ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "Public can read app_settings" ON public.app_settings;
CREATE POLICY "Public can read app_settings" ON public.app_settings FOR SELECT TO anon USING (true);
-- update/insert is admins only (service role key), so no policies for that here.

-- 2. ban flag for users
ALTER TABLE public.koda_users ADD COLUMN IF NOT EXISTS is_banned BOOLEAN DEFAULT false;

-- 3. and the same for servers
ALTER TABLE public.koda_servers ADD COLUMN IF NOT EXISTS is_banned BOOLEAN DEFAULT false;
