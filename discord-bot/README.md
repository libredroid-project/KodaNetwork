# KodaHosting release bot

Announces every published release from the `releases` table in Discord and answers `/changelog`
and `/version`.

## 1. Create the Discord application (one time, takes two minutes)

1. <https://discord.com/developers/applications> -> **New Application**, name it e.g. `KodaHosting`.
2. Tab **Bot** -> **Reset Token** -> copy the token (that is `DISCORD_TOKEN`).
3. Same page, enable **Message Content Intent** is *not* needed; leave it off.
4. Tab **OAuth2 -> URL Generator**: scopes `bot` + `applications.commands`,
   bot permissions `Send Messages`, `Embed Links`, `Read Message History`.
   Open the generated URL and add the bot to your server.
5. In Discord enable **Settings -> Advanced -> Developer Mode**, then right click the channel that
   should receive announcements -> **Copy Channel ID** (that is `DISCORD_CHANNEL_ID`).
   Right click the server icon -> **Copy Server ID** (that is `DISCORD_GUILD_ID`, optional but
   makes the slash commands appear immediately).

## 2. Install on the VPS

```bash
sudo apt install -y nodejs npm            # if node is missing
sudo useradd -r -s /usr/sbin/nologin kodabot || true
sudo mkdir -p /opt/koda-release-bot
# copy bot.mjs, package.json and your .env into /opt/koda-release-bot
cd /opt/koda-release-bot && sudo npm install --omit=dev
sudo cp koda-release-bot.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now koda-release-bot
sudo journalctl -u koda-release-bot -f
```

## 3. Publishing a release

Insert a row (admin panel, admin app or by hand in Supabase) and raise the app version:

```sql
INSERT INTO releases (version_code, version_name, title, changelog, download_url, is_published)
VALUES (8511, 'v0.133beta', 'KodaDash Update',
        E'* Neu: ...\n* Fix: ...',
        'https://github.com/libredroid-project/KodaNetwork/releases', true);

UPDATE app_settings SET value = '{"ts": 8511}'::jsonb WHERE key = 'latest_app_version';
```

The bot picks the row up within a minute, posts it and writes `announced_at` back. The app shows the
same changelog on its update screen (it reads `rpc_get_latest_release`).
