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
-- EXTENSION STORE 2026-10-15
-- ============================================================================
-- the registry behind api.kodanetwork.eu/v1/extensions. one row per extension
-- plus one row per uploaded version; nothing goes live before the review in the
-- admin panel flips the version (and for the first publish the extension) to
-- APPROVED.
--
-- lifecycle
--   extension: PENDING -> APPROVED -> (DELISTED by admin | WITHDRAWN by the author)
--              PENDING -> REJECTED (admin, with a note) -> resubmission goes PENDING
--   version:   PENDING -> APPROVED | REJECTED; only APPROVED versions of an
--              APPROVED extension are listed and downloadable. a new version of a
--              published extension serves the old one until the review approves it.
--
-- reachability: RLS on, NO policies anywhere. everything flows through the "api"
-- edge function (service role) and the admin panel (service role); nothing here is
-- reachable via postgrest with anon/authenticated keys.
--
-- buckets: extension_packages is PRIVATE (downloads only through short signed urls
-- from the api function), extension_assets and avatars are public read (icons,
-- screenshots, profile pictures).

CREATE TABLE IF NOT EXISTS public.extensions (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ext_id         text NOT NULL UNIQUE CHECK (ext_id ~ '^[a-z0-9]+(\.[a-z0-9_-]+)+$'),
    owner_auth_id  uuid NOT NULL,
    name           text NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
    summary        text,
    description    text,
    category       text NOT NULL DEFAULT 'other',
    icon_url       text,
    screenshots    jsonb NOT NULL DEFAULT '[]'::jsonb,
    license        text NOT NULL,
    author         text,
    homepage_url   text,
    source_url     text,
    status         text NOT NULL DEFAULT 'PENDING'
                   CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'DELISTED', 'WITHDRAWN')),
    review_note    text,
    downloads      bigint NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    published_at   timestamptz,
    ended_at       timestamptz
);

CREATE TABLE IF NOT EXISTS public.extension_versions (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    extension_id    uuid NOT NULL REFERENCES public.extensions(id) ON DELETE CASCADE,
    version         text NOT NULL,
    version_code    integer NOT NULL CHECK (version_code >= 1),
    min_app_version integer NOT NULL DEFAULT 0,
    changelog       text,
    permissions     jsonb NOT NULL DEFAULT '[]'::jsonb,
    file_path       text NOT NULL,
    file_size       bigint,
    sha256          text,
    status          text NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    review_note     text,
    verified_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    reviewed_at     timestamptz,
    UNIQUE (extension_id, version_code)
);

CREATE TABLE IF NOT EXISTS public.account_profiles (
    auth_id     uuid PRIMARY KEY,
    avatar_url  text,
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS extensions_status_idx
    ON public.extensions (status, updated_at DESC);
CREATE INDEX IF NOT EXISTS extensions_owner_idx
    ON public.extensions (owner_auth_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS extension_versions_ext_idx
    ON public.extension_versions (extension_id, status, version_code DESC);

-- at most one submission in flight per extension, keeps the submission flow honest
CREATE UNIQUE INDEX IF NOT EXISTS extension_versions_one_pending_idx
    ON public.extension_versions (extension_id) WHERE status = 'PENDING';

-- atomic download counter, called by the api function on every served package
CREATE OR REPLACE FUNCTION public.fn_ext_bump_downloads(p_ext_id text)
RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path = public AS $fn$
    UPDATE public.extensions SET downloads = downloads + 1 WHERE ext_id = p_ext_id;
$fn$;

REVOKE ALL ON FUNCTION public.fn_ext_bump_downloads(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.fn_ext_bump_downloads(text) TO service_role;

ALTER TABLE public.extensions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.extension_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.account_profiles ENABLE ROW LEVEL SECURITY;
-- no policies on purpose: the store is served by the api edge function only.

REVOKE ALL ON public.extensions FROM anon, authenticated;
REVOKE ALL ON public.extension_versions FROM anon, authenticated;
REVOKE ALL ON public.account_profiles FROM anon, authenticated;
GRANT ALL ON public.extensions TO service_role;
GRANT ALL ON public.extension_versions TO service_role;
GRANT ALL ON public.account_profiles TO service_role;

-- buckets -------------------------------------------------------------------
-- packages stay private: even an approved .kodaext leaves through a 60 second
-- signed url, so pending or rejected uploads are never reachable by url guessing.
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES ('extension_packages', 'extension_packages', false, 20971520,
        ARRAY['application/zip', 'application/x-zip-compressed', 'application/octet-stream'])
ON CONFLICT (id) DO UPDATE SET
    public = EXCLUDED.public,
    file_size_limit = EXCLUDED.file_size_limit,
    allowed_mime_types = EXCLUDED.allowed_mime_types;

INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES ('extension_assets', 'extension_assets', true, 5242880,
        ARRAY['image/png', 'image/jpeg', 'image/webp'])
ON CONFLICT (id) DO UPDATE SET
    public = EXCLUDED.public,
    file_size_limit = EXCLUDED.file_size_limit,
    allowed_mime_types = EXCLUDED.allowed_mime_types;

INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES ('avatars', 'avatars', true, 2097152,
        ARRAY['image/png', 'image/jpeg'])
ON CONFLICT (id) DO UPDATE SET
    public = EXCLUDED.public,
    file_size_limit = EXCLUDED.file_size_limit,
    allowed_mime_types = EXCLUDED.allowed_mime_types;

-- public read for the two asset buckets (same pattern as release_images);
-- service role writes, no upload policy for anyone else.
DROP POLICY IF EXISTS extension_assets_public_read ON storage.objects;
CREATE POLICY extension_assets_public_read ON storage.objects
    FOR SELECT USING (bucket_id = 'extension_assets');

DROP POLICY IF EXISTS avatars_public_read ON storage.objects;
CREATE POLICY avatars_public_read ON storage.objects
    FOR SELECT USING (bucket_id = 'avatars');

NOTIFY pgrst, 'reload schema';
