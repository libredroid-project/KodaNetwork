/*
 * Copyright (c) 2026 KodaHosting
 *
 * Triple-Licensed under GPL-3.0 / LOPL v1.0 PREVIEW / Commercial License.
 *
 * KodaHosting release bot.
 *
 *   * announces every newly published row of the "releases" table in a Discord channel
 *     (it writes announced_at back, so nothing is posted twice, even after a restart)
 *   * answers /changelog with the newest releases
 *   * answers /version to show the newest version only
 *
 * The releases themselves are written elsewhere: the admin panel, the admin app or by hand in
 * Supabase. This bot only reads and announces.
 */
import 'dotenv/config';
import { Client, GatewayIntentBits, EmbedBuilder, SlashCommandBuilder, REST, Routes } from 'discord.js';

const {
    DISCORD_TOKEN,
    DISCORD_CHANNEL_ID,
    DISCORD_GUILD_ID,
    SUPABASE_URL,
    SUPABASE_SERVICE_ROLE_KEY,
    POLL_SECONDS = '60',
    EMBED_COLOR = '#FF6B00'
} = process.env;

if (!DISCORD_TOKEN || !SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
    console.error('Missing configuration - see .env.example (token, Supabase url + service key)');
    process.exit(1);
}

const color = parseInt(EMBED_COLOR.replace('#', ''), 16);

const client = new Client({ intents: [GatewayIntentBits.Guilds] });

/** Announcement channel: configured, or detected by name (changelog/announcements/...) once we are logged in. */
let announceChannelId = (DISCORD_CHANNEL_ID || '').trim();
const CHANNEL_NAMES = ['changelog', 'changelogs', 'releases', 'announcements', 'news', 'updates'];

function resolveAnnounceChannel() {
    const textChannels = () => client.guilds.cache.flatMap(guild =>
        guild.channels.cache.filter(channel => channel.isTextBased && channel.isTextBased())
            .map(channel => ({ guild: guild.name, name: channel.name, id: channel.id })));

    if (!announceChannelId) {
        const available = textChannels();
        for (const wanted of CHANNEL_NAMES) {
            const hit = available.find(channel => channel.name.toLowerCase().includes(wanted));
            if (hit) {
                announceChannelId = hit.id;
                console.log(`announcing in #${hit.name} (${hit.id}) of ${hit.guild}`);
                break;
            }
        }
    }

    if (!announceChannelId) {
        console.log('No changelog channel found - pick one and put its id in DISCORD_CHANNEL_ID:');
        for (const channel of textChannels()) {
            console.log(`   #${channel.name}  ${channel.id}  (${channel.guild})`);
        }
    }
}

/** Small helper around the Supabase REST API (service role, so it may read and write). */
async function supabase(path, options = {}) {
    const response = await fetch(`${SUPABASE_URL}/rest/v1/${path}`, {
        ...options,
        headers: {
            apikey: SUPABASE_SERVICE_ROLE_KEY,
            Authorization: `Bearer ${SUPABASE_SERVICE_ROLE_KEY}`,
            'Content-Type': 'application/json',
            Prefer: 'return=representation',
            ...(options.headers || {})
        }
    });
    if (!response.ok) throw new Error(`Supabase ${response.status}: ${await response.text()}`);
    const text = await response.text();
    return text ? JSON.parse(text) : null;
}

function releaseEmbed(release) {
    const embed = new EmbedBuilder()
        .setColor(color)
        .setTitle(release.title || `${release.version_name} is out`)
        .setDescription(release.changelog || '_No changelog provided._')
        .addFields(
            { name: 'Version', value: release.version_name, inline: true },
            { name: 'Channel', value: release.channel, inline: true }
        )
        .setFooter({ text: 'KodaHosting' })
        .setTimestamp(release.created_at ? new Date(release.created_at) : new Date());

    if (release.download_url) embed.setURL(release.download_url);
    return embed;
}

/** Posts every published release that has not been announced yet. */
async function announcePendingReleases() {
    try {
        const pending = await supabase('releases?is_published=is.true&announced_at=is.null&order=version_code.asc');
        if (!pending || pending.length === 0) return;

        if (!announceChannelId) {
            console.log(`${pending.length} release(s) waiting, but no announcement channel is set.`);
            return;
        }
        const channel = await client.channels.fetch(announceChannelId).catch(() => null);
        if (!channel) {
            console.error('Channel not found:', announceChannelId);
            return;
        }

        for (const release of pending) {
            const message = await channel.send({ embeds: [releaseEmbed(release)] });
            await supabase(`releases?id=eq.${release.id}`, {
                method: 'PATCH',
                body: JSON.stringify({
                    announced_at: new Date().toISOString(),
                    discord_message_id: message.id
                })
            });
            console.log(`announced ${release.version_name} (${release.version_code})`);
        }
    } catch (error) {
        console.error('announce failed:', error.message);
    }
}

async function registerCommands() {
    const commands = [
        new SlashCommandBuilder()
            .setName('changelog')
            .setDescription('Shows the newest KodaHosting releases')
            .addIntegerOption(option => option
                .setName('count')
                .setDescription('How many releases to show (1-10)')
                .setMinValue(1).setMaxValue(10))
            .toJSON(),
        new SlashCommandBuilder()
            .setName('version')
            .setDescription('Shows the newest KodaHosting version')
            .toJSON()
    ];

    const rest = new REST({ version: '10' }).setToken(DISCORD_TOKEN);
    // Guild commands appear instantly; without a configured guild we use the first server the bot is in.
    const guildId = (DISCORD_GUILD_ID || '').trim() || client.guilds.cache.first()?.id;
    const route = guildId
        ? Routes.applicationGuildCommands(client.user.id, guildId)
        : Routes.applicationCommands(client.user.id);
    await rest.put(route, { body: commands });
    console.log('slash commands registered');
}

client.once('ready', async () => {
    console.log(`logged in as ${client.user.tag}`);
    console.log(`servers: ${client.guilds.cache.map(guild => guild.name).join(', ') || 'none - invite the bot first'}`);
    resolveAnnounceChannel();
    await registerCommands().catch(error => console.error('command registration failed:', error.message));
    await announcePendingReleases();
    setInterval(announcePendingReleases, Math.max(15, parseInt(POLL_SECONDS, 10)) * 1000);
});

client.on('interactionCreate', async (interaction) => {
    if (!interaction.isChatInputCommand()) return;

    try {
        if (interaction.commandName === 'changelog') {
            const count = interaction.options.getInteger('count') || 3;
            const releases = await supabase(`releases?is_published=is.true&order=version_code.desc&limit=${count}`);
            if (!releases || releases.length === 0) {
                await interaction.reply({ content: 'No releases published yet.', ephemeral: true });
                return;
            }
            await interaction.reply({ embeds: releases.map(releaseEmbed) });
        } else if (interaction.commandName === 'version') {
            const releases = await supabase('releases?is_published=is.true&order=version_code.desc&limit=1');
            if (!releases || releases.length === 0) {
                await interaction.reply({ content: 'No releases published yet.', ephemeral: true });
                return;
            }
            const release = releases[0];
            await interaction.reply({
                embeds: [new EmbedBuilder()
                    .setColor(color)
                    .setTitle(`Newest version: ${release.version_name}`)
                    .setDescription(release.title || 'KodaHosting')
                    .addFields({ name: 'Channel', value: release.channel, inline: true })
                    .setFooter({ text: 'KodaHosting' })]
            });
        }
    } catch (error) {
        console.error('interaction failed:', error.message);
        const message = { content: 'Something went wrong while reading the releases.', ephemeral: true };
        if (interaction.replied || interaction.deferred) await interaction.followUp(message).catch(() => {});
        else await interaction.reply(message).catch(() => {});
    }
});

client.login(DISCORD_TOKEN);
