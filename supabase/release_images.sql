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
-- ============================================================================
-- RELEASE IMAGES 2026-10-04
-- ============================================================================
-- images for release announcements: the admin panel uploads them into the public
-- "release_images" storage bucket, image_url holds the public URL and the discord
-- bot drops it into the embed (setImage).
--
-- the bucket already exists (made through the storage api), the insert below is
-- idempotent and only there for documentation. STILL OUTSTANDING: the ALTER - the
-- management api token had expired, so run this once SUPABASE_ACCESS_TOKEN in
-- .secrets.env is renewed:
--   node tools/koda_sql.mjs "$(cat supabase/release_images.sql)"

ALTER TABLE public.releases ADD COLUMN IF NOT EXISTS image_url text;

INSERT INTO storage.buckets (id, name, public)
VALUES ('release_images', 'release_images', true)
ON CONFLICT (id) DO NOTHING;

-- public read policy for the bucket (storage api default for public buckets,
-- spelled out here in case the flag alone is not enough):
-- (service role writes, the public reads through /object/public/)
DROP POLICY IF EXISTS release_images_public_read ON storage.objects;
CREATE POLICY release_images_public_read ON storage.objects
  FOR SELECT USING (bucket_id = 'release_images');
