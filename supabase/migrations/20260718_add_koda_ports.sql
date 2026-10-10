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
CREATE TABLE IF NOT EXISTS public.koda_ports (
    port integer PRIMARY KEY,
    host varchar NOT NULL,
    created_at timestamp with time zone DEFAULT now()
);

-- RLS has to be on before the policy below means anything
ALTER TABLE public.koda_ports ENABLE ROW LEVEL SECURITY;

-- ports are shared info, hiding them would only break the client
CREATE POLICY "Allow public read access" ON public.koda_ports FOR SELECT USING (true);
