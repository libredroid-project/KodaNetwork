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
--
-- e-mail verification for web-only accounts, 2026-11-09.
--
-- the app writes koda_users.email_verified_at, but koda_users is one row per
-- device and a developer may register on the website without ever linking the
-- app. account_profiles already exists exactly once per auth_id, so the console
-- verification lives there and the API accepts either place.

alter table public.account_profiles
  add column if not exists email_verified_at timestamptz;

comment on column public.account_profiles.email_verified_at is
  'set by the developer console after the account proved control of its mail address';
