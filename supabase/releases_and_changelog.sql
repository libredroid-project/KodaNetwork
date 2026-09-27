-- Releases + changelog (Discord, Website, Admin-Panel und Update-Screen in der App).
--
-- Eine Zeile pro Release. Geschrieben wird nur mit dem Service-Role-Key (Admin-Panel, Bot,
-- oder von Hand im Supabase-Dashboard) - gelesen wird oeffentlich, aber nur veroeffentlichte
-- Releases. Der Bot kuendigt neue Zeilen an und traegt danach announced_at nach.
--
--   INSERT INTO releases (version_code, version_name, title, changelog, download_url, is_published)
--   VALUES (8511, 'v0.133beta', 'KodaDash Update', E'* Neu: ...\n* Fix: ...', 'https://...', true);
--   UPDATE app_settings SET value = '{"ts": 8511}'::jsonb WHERE key = 'latest_app_version';

CREATE TABLE IF NOT EXISTS public.releases (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    version_code       integer NOT NULL UNIQUE,
    version_name       text    NOT NULL,
    channel            text    NOT NULL DEFAULT 'stable',   -- stable | beta
    title              text,
    changelog          text    NOT NULL,
    download_url       text,
    is_published       boolean NOT NULL DEFAULT false,
    announced_at       timestamptz,                          -- gesetzt vom Discord-Bot
    discord_message_id text,
    created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS releases_published_idx
    ON public.releases (is_published, version_code DESC);

ALTER TABLE public.releases ENABLE ROW LEVEL SECURITY;

-- Lesen: nur veroeffentlichte Releases (App, Website). Schreiben: ausschliesslich Service-Role.
DROP POLICY IF EXISTS "Published releases are readable" ON public.releases;
CREATE POLICY "Published releases are readable" ON public.releases
    FOR SELECT TO anon, authenticated USING (is_published);

GRANT SELECT ON public.releases TO anon, authenticated;

-- Ein einziger Einstiegspunkt fuer die App: das neueste veroeffentlichte Release.
CREATE OR REPLACE FUNCTION public.rpc_get_latest_release(p_channel text DEFAULT NULL)
RETURNS TABLE(version_code integer, version_name text, channel text, title text,
              changelog text, download_url text, released_at timestamptz)
LANGUAGE sql SECURITY DEFINER SET search_path = public AS $fn$
    SELECT r.version_code, r.version_name::text, r.channel::text, r.title::text,
           r.changelog::text, r.download_url::text, r.created_at
      FROM public.releases r
     WHERE r.is_published
       AND (p_channel IS NULL OR r.channel = p_channel)
     ORDER BY r.version_code DESC
     LIMIT 1;
$fn$;

-- Auch die aelteren Releases fuer /changelog im Discord und die Website-Liste.
CREATE OR REPLACE FUNCTION public.rpc_get_releases(p_limit integer DEFAULT 10)
RETURNS TABLE(version_code integer, version_name text, channel text, title text,
              changelog text, download_url text, released_at timestamptz)
LANGUAGE sql SECURITY DEFINER SET search_path = public AS $fn$
    SELECT r.version_code, r.version_name::text, r.channel::text, r.title::text,
           r.changelog::text, r.download_url::text, r.created_at
      FROM public.releases r
     WHERE r.is_published
     ORDER BY r.version_code DESC
     LIMIT GREATEST(1, LEAST(COALESCE(p_limit, 10), 50));
$fn$;

REVOKE ALL ON FUNCTION public.rpc_get_latest_release(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.rpc_get_releases(integer)   FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.rpc_get_latest_release(text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.rpc_get_releases(integer)    TO anon, authenticated;

NOTIFY pgrst, 'reload schema';
