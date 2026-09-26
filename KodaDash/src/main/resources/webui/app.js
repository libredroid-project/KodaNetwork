/*
 * KodaDash dashboard logic.
 *
 * No framework, no build step: one classic script using the single api() helper.
 * The console talks to the server over SSE with automatic, lossless reconnects
 * (the server sends "id:" per line, the browser resumes via Last-Event-ID).
 */
'use strict';

var state = {
    token: null,
    password: null,
    baseUrl: '',
    connected: false,
    currentTab: 'overview',
    currentPath: '',
    statsTimer: null,
    console: {
        source: null,
        lines: {},
        lastIndex: -1,
        maxDom: 1000,
        level: 'ALL',
        search: '',
        matchIndex: -1,
        matches: [],
        follow: true
    },
    history: [],
    historyPos: -1,
    players: [],
    settings: null,
    settingsDirty: {},
    suggestions: { commands: [], players: [], arguments: {} },
    completion: { base: '', list: [], index: -1 },
    editor: { instance: null, ready: false, loading: false, pending: null, file: null },
    stats: { tps: [], ram: [] },
    needsPassword: false
};

/* ------------------------------------------------------------------ helpers */

function $(id) { return document.getElementById(id); }

function escapeHtml(value) {
    return String(value == null ? '' : value)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function formatBytes(bytes) {
    if (bytes == null || isNaN(bytes)) return '--';
    var units = ['B', 'KB', 'MB', 'GB'];
    var i = 0, v = Number(bytes);
    while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
    return (i === 0 ? v : v.toFixed(1)) + ' ' + units[i];
}

/** The stats API reports memory in megabytes, not bytes. */
function formatMegabytes(mb) {
    if (mb == null || isNaN(mb)) return '--';
    var v = Number(mb);
    if (v >= 1024) return (v / 1024).toFixed(1) + ' GB';
    return Math.round(v) + ' MB';
}

function formatUptime(ms) {
    if (ms == null || isNaN(ms) || ms < 0) return '--';
    var s = Math.floor(ms / 1000);
    var d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600),
        m = Math.floor((s % 3600) / 60), sec = s % 60;
    if (d > 0) return d + 'd ' + h + 'h ' + m + 'm';
    if (h > 0) return h + 'h ' + m + 'm';
    if (m > 0) return m + 'm ' + sec + 's';
    return sec + 's';
}

function formatTime(ms) {
    var d = new Date(ms);
    function pad(n) { return String(n).length < 2 ? '0' + n : String(n); }
    return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
}

function showToast(message, kind) {
    var el = document.createElement('div');
    el.className = 'toast' + (kind ? ' ' + kind : '');
    el.textContent = message;
    $('toast-container').appendChild(el);
    setTimeout(function () {
        el.classList.add('out');
        setTimeout(function () { el.remove(); }, 220);
    }, 3200);
}

/** Convert Minecraft legacy color codes into coloured spans. */
function parseMinecraftColors(text) {
    if (text == null) return '';
    var parts = String(text).split(/(\u00a7[0-9a-fk-or])/i);
    var html = '';
    var cls = '';
    for (var i = 0; i < parts.length; i++) {
        var part = parts[i];
        if (/^\u00a7[0-9a-f]$/i.test(part)) { cls = 'mc-' + part[1].toLowerCase(); continue; }
        if (/^\u00a7[kl-or]$/i.test(part)) {
            var code = part[1].toLowerCase();
            if (code === 'r') { cls = ''; continue; }
            var extra = { l: 'mc-l', o: 'mc-o', n: 'mc-n', m: 'mc-m', k: '' }[code];
            if (extra) html += '<span class="' + extra + '">';
            continue;
        }
        html += cls ? '<span class="' + cls + '">' + escapeHtml(part) + '</span>' : escapeHtml(part);
    }
    return html;
}

/**
 * The server console carries ANSI SGR escapes (Paper renders section-sign colors
 * that way). Translate them into the Minecraft codes above and drop everything
 * else - control sequences must never reach the visible output.
 */
function parseConsoleText(text) {
    if (text == null) return '';
    var ansiToSection = {
        0: '\u00a7r', 39: '\u00a7r', 49: '\u00a7r',
        1: '\u00a7l', 4: '\u00a7n',
        30: '\u00a70', 31: '\u00a7c', 32: '\u00a7a', 33: '\u00a7e',
        34: '\u00a79', 35: '\u00a7d', 36: '\u00a7b', 37: '\u00a7f',
        90: '\u00a78', 91: '\u00a7c', 92: '\u00a7a', 93: '\u00a7e',
        94: '\u00a79', 95: '\u00a7d', 96: '\u00a7b', 97: '\u00a7f'
    };
    var converted = String(text).replace(/\u001b\[([0-9;]*)m/g, function (match, codes) {
        var out = '';
        codes.split(';').forEach(function (code) {
            var mapped = ansiToSection[code === '' ? 0 : parseInt(code, 10)];
            if (mapped) out += mapped;
        });
        return out;
    });
    converted = converted.replace(/\u001b\[[0-9;?]*[A-Za-z]/g, '').replace(/\u001b./g, '');
    return parseMinecraftColors(converted);
}

/* --------------------------------------------------------------------- api */

/** Token as query parameters - needed by <img> and window.open, which cannot send headers. */
function authQuery() {
    var query = 'token=' + encodeURIComponent(state.token);
    if (state.password) query += '&password=' + encodeURIComponent(state.password);
    return query;
}

function api(path, options) {
    var opts = Object.assign({ method: 'GET' }, options || {});
    opts.headers = Object.assign({ 'Content-Type': 'application/json' }, opts.headers || {});
    if (state.token) opts.headers['Authorization'] = 'Bearer ' + state.token;
    if (state.password) opts.headers['X-Dashboard-Password'] = state.password;
    if (opts.body && typeof opts.body !== 'string') opts.body = JSON.stringify(opts.body);

    return fetch(state.baseUrl + path, opts).then(function (res) {
        return res.text().then(function (text) {
            var data = null;
            if (text) { try { data = JSON.parse(text); } catch (e) { data = { raw: text }; } }
            if (!res.ok) {
                var message = (data && (data.error || data.message)) || ('HTTP ' + res.status);
                var error = new Error(message);
                error.status = res.status;
                throw error;
            }
            return data;
        });
    });
}

/* ------------------------------------------------------------------- login */

function doLogin() {
    var token = $('token-input').value.trim();
    var password = $('password-input').value;
    var errorEl = $('login-error');
    var btn = $('login-btn');
    errorEl.textContent = '';

    if (!token) { errorEl.textContent = 'Please paste your access token.'; return; }

    btn.disabled = true;
    btn.textContent = 'Connecting...';
    var previousToken = state.token, previousPassword = state.password;
    state.token = token;
    state.password = password || null;

    api('/api/auth', { method: 'POST', body: { token: token, password: password || undefined } })
        .then(function (data) {
            sessionStorage.setItem('kodaDashToken', token);
            if (password) sessionStorage.setItem('kodaDashPassword', password);
            else sessionStorage.removeItem('kodaDashPassword');
            $('server-name').textContent = (data && data.serverName) || 'Server';
            $('mobile-title').textContent = (data && data.serverName) || 'KodaDash';
            enterDashboard();
        })
        .catch(function (e) {
            state.token = previousToken;
            state.password = previousPassword;
            if (/password/i.test(e.message)) {
                state.needsPassword = true;
                $('password-group').classList.remove('hidden');
                errorEl.textContent = 'This dashboard needs its secondary password.';
            } else {
                errorEl.textContent = e.message;
            }
        })
        .then(function () {
            btn.disabled = false;
            btn.textContent = 'Connect';
        });
}

function enterDashboard() {
    $('login-screen').style.display = 'none';
    $('app').classList.add('ready');
    state.connected = true;
    switchTab(state.currentTab || 'overview');
    startStatsPolling();
    connectConsole();
    loadServerActionState();
    loadSuggestions();
}

function signOut() {
    if (state.console.source) { try { state.console.source.close(); } catch (e) {} }
    state.console.source = null;
    state.token = null;
    state.password = null;
    state.connected = false;
    sessionStorage.removeItem('kodaDashToken');
    sessionStorage.removeItem('kodaDashPassword');
    if (state.statsTimer) clearInterval(state.statsTimer);
    $('app').classList.remove('ready');
    $('login-screen').style.display = '';
    $('token-input').value = '';
    setConnectionStatus('Not connected', '');
}

function setConnectionStatus(text, cls) {
    $('status-text').textContent = text;
    $('status-dot').className = 'status-dot' + (cls ? ' ' + cls : '');
}

/* -------------------------------------------------------------------- tabs */

function switchTab(tab) {
    state.currentTab = tab;
    var items = document.querySelectorAll('.nav-item[data-tab]');
    for (var i = 0; i < items.length; i++) {
        items[i].classList.toggle('active', items[i].getAttribute('data-tab') === tab);
    }
    var sections = document.querySelectorAll('.tab-content');
    for (var j = 0; j < sections.length; j++) sections[j].classList.remove('active');
    var section = $('tab-' + tab);
    if (section) section.classList.add('active');
    closeSidebar();

    if (tab === 'overview') loadOverview();
    else if (tab === 'players') loadPlayers();
    else if (tab === 'files') loadFiles(state.currentPath);
    else if (tab === 'plugins') loadPlugins();
    else if (tab === 'settings') loadSettings();
}

function openSidebar() { $('sidebar').classList.add('open'); $('scrim').classList.add('open'); }
function closeSidebar() { $('sidebar').classList.remove('open'); $('scrim').classList.remove('open'); }

/* ---------------------------------------------------------------- overview */

function startStatsPolling() {
    if (state.statsTimer) clearInterval(state.statsTimer);
    state.statsTimer = setInterval(function () {
        if (state.currentTab === 'overview') loadOverview();
    }, 3000);
}

function loadOverview() {
    api('/api/server').then(function (d) {
        var tps = Array.isArray(d.tps) ? d.tps : [];
        var tpsNow = tps.length ? tps[0] : null;
        $('stat-tps').textContent = tpsNow != null ? Number(tpsNow).toFixed(2) : '--';
        $('stat-tps-foot').textContent = tps.length > 1
            ? '1m ' + Number(tps[0]).toFixed(2) + ' / 5m ' + Number(tps[1]).toFixed(2) + (tps[2] != null ? ' / 15m ' + Number(tps[2]).toFixed(2) : '')
            : 'ticks per second';

        var maxRam = (d.ram && d.ram.max) || d.maxRam || 0;
        var usedRam = d.usedRam != null ? d.usedRam : 0;
        var pct = maxRam > 0 ? Math.min(100, Math.round(usedRam / maxRam * 100)) : 0;
        $('stat-ram').textContent = maxRam > 0 ? pct + '%' : '--';
        $('stat-ram-foot').textContent = maxRam > 0 ? formatMegabytes(usedRam) + ' of ' + formatMegabytes(maxRam) : 'of allocated';
        $('stat-ram-bar').style.width = pct + '%';

        var online = (d.players && d.players.online) || 0;
        var maxPlayers = (d.players && d.players.max) || d.maxPlayers || 0;
        $('stat-players').textContent = online + (maxPlayers ? ' / ' + maxPlayers : '');
        $('stat-players-foot').textContent = online === 1 ? 'player online' : 'players online';
        var badge = $('player-count-badge');
        badge.textContent = online;
        badge.classList.toggle('hidden', online === 0);

        $('stat-uptime').textContent = formatUptime(d.uptime);

        $('info-version').textContent = d.version || '--';
        $('info-motd').innerHTML = parseMinecraftColors(d.motd || '--');
        $('info-world').textContent = d.worldName || '--';
        $('info-gamemode').textContent = d.gamemode || '--';
        $('info-difficulty').textContent = d.difficulty || '--';
        $('info-onlinemode').textContent = d.onlineMode ? 'yes' : 'no';
        $('info-port').textContent = d.port || '--';
        var footerVersion = $('footer-version');
        if (footerVersion) footerVersion.textContent = 'KodaDash on ' + (d.name || 'server') + ' ' + (d.version || '');

        pushHistory('tps', tpsNow);
        pushHistory('ram', maxRam > 0 ? pct : null);
        if (!state.deviceLoaded || Date.now() - state.deviceLoaded > 30000) {
            state.deviceLoaded = Date.now();
            loadDeviceInfo();
        }
        drawSparkline('spark-tps', state.stats.tps, 20, 'var(--brand)');
        drawSparkline('spark-ram', state.stats.ram, 100, 'var(--md-primary)');
    }).catch(function (e) {
        if (e.status === 401) handleUnauthorized();
    });
}

/** Host details: the Android device (provided by the app) plus the system the JVM runs on. */
function loadDeviceInfo() {
    api('/api/device').then(function (d) {
        var device = d.device || {};
        var system = d.system || {};
        var memory = d.memory || {};
        var disk = d.disk || {};
        var runtime = d.runtime || {};

        function mb(value) { return value == null ? null : formatMegabytes(value); }
        function gb(value) {
            if (value == null) return null;
            // Small folders read better in MB than as "0 GB"
            return value < 1 ? Math.max(1, Math.round(value * 1024)) + ' MB' : value + ' GB';
        }

        var deviceRows = [
            ['Model', device.model], ['Manufacturer', device.manufacturer || device.brand],
            ['Android', device.android], ['API level', device.sdk],
            ['Device RAM', mb(device.totalRamMb)],
            ['Battery', device.batteryPct != null ? device.batteryPct + ' %' : null]
        ].filter(function (row) { return row[1] != null && row[1] !== ''; });

        $('device-device').innerHTML = deviceRows.length
            ? deviceRows.map(function (row) { return infoRow(row[0], row[1]); }).join('')
            : '<div class="muted small">No device details reported by the app yet.</div>';

        // Android hides /proc/cpuinfo and /proc/loadavg from apps, so the CPU falls back to
        // the SoC the app reports and rows without data are left out entirely.
        var cpu = system.cpuModel || (device.soc && device.soc.trim()) || device.hardware || null;
        var systemRows = [
            infoRow('Platform', system.os + ' / ' + system.arch),
            cpu ? infoRow('CPU', cpu) : '',
            system.cores ? infoRow('Cores', system.cores) : '',
            system.loadAvg && system.loadAvg.length ? infoRow('Load (1m)', Number(system.loadAvg[0]).toFixed(2)) : ''
        ].filter(Boolean);
        $('device-system').innerHTML = systemRows.join('');

        $('device-memory').innerHTML = [
            infoRow('System RAM', memory.totalMb ? mb(memory.totalMb) + ' total' : '--'),
            infoRow('Available', mb(memory.availableMb)),
            infoRow('Server heap', mb(memory.jvmUsedMb) + ' of ' + mb(memory.jvmMaxMb))
        ].join('');

        $('device-disk').innerHTML = [
            infoRow('Storage', disk.totalGb != null ? gb(disk.totalGb) + ' total' : '--'),
            infoRow('Free', gb(disk.freeGb)),
            infoRow('Server folder', disk.serverDirGb != null ? gb(disk.serverDirGb) : '--')
        ].join('');

        var kernel = String(system.kernel || '');
        $('device-runtime').innerHTML = [
            infoRow('Java', runtime.java), infoRow('VM', runtime.jvm),
            infoRow('Uptime', formatUptime(runtime.uptimeMs)), infoRow('Threads', runtime.threads),
            kernel ? infoRow('Kernel', kernel.split(' ').slice(0, 3).join(' ')) : ''
        ].filter(Boolean).join('');

        $('device-kernel').textContent = system.kernel || '';
    }).catch(function () { /* the panel simply stays empty on older builds */ });
}

function pushHistory(kind, value) {
    var arr = state.stats[kind];
    if (value == null || isNaN(value)) return;
    arr.push(Number(value));
    while (arr.length > 60) arr.shift();
}

function drawSparkline(canvasId, values, max, color) {
    var canvas = $(canvasId);
    if (!canvas || !canvas.getContext) return;
    var ctx = canvas.getContext('2d');
    var w = canvas.width, h = canvas.height;
    ctx.clearRect(0, 0, w, h);
    if (values.length < 2) return;

    // Keep the curve off the edges so a constant value reads as a line, not a filled block
    var pad = 4;
    var usable = h - pad * 2;
    var step = w / (values.length - 1);
    var stroke = color === 'var(--brand)' ? '#FF6B00' : '#FFB68C';
    var flat = values.every(function (v) { return v === values[0]; });

    ctx.beginPath();
    for (var i = 0; i < values.length; i++) {
        var x = i * step;
        var ratio = Math.max(0, Math.min(1, values[i] / max));
        var y = pad + usable - ratio * usable;
        if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    }
    ctx.strokeStyle = stroke;
    ctx.lineWidth = 2;
    ctx.lineJoin = 'round';
    ctx.stroke();

    if (!flat) {
        ctx.lineTo(w, h);
        ctx.lineTo(0, h);
        ctx.closePath();
        ctx.globalAlpha = 0.12;
        ctx.fillStyle = stroke;
        ctx.fill();
        ctx.globalAlpha = 1;
    }
}

/* --------------------------------------------------------- server actions */

function loadServerActionState() {
    api('/api/server-action').then(function (d) {
        var enabled = d.enabled !== false;
        $('btn-restart').disabled = !enabled;
        $('btn-stop').disabled = !enabled || d.allowStop === false;
        $('btn-cancel-action').classList.toggle('hidden', !d.pending);
        if (d.pending) {
            $('action-status').textContent = 'A ' + d.action + ' is scheduled in ' + d.seconds + ' seconds.';
        } else if (!enabled) {
            $('action-status').textContent = 'Server actions are disabled in the KodaDash config.';
        } else {
            $('action-status').textContent = 'Restart or shut down the server with a countdown for all players.';
        }
    }).catch(function () { /* older builds may not have this route */ });
}

function triggerServerAction(action) {
    var labels = { restart: 'restart the server', stop: 'shut down the server' };
    confirmDialog('Confirm ' + action, 'Do you really want to ' + labels[action] + '? All players are notified with a countdown.', false)
        .then(function (ok) {
            if (!ok) return;
            return api('/api/server-action', { method: 'POST', body: { action: action } })
                .then(function (d) {
                    showToast(action + ' scheduled in ' + d.seconds + 's', 'ok');
                    loadServerActionState();
                })
                .catch(function (e) { showToast(e.message, 'err'); });
        });
}

/* ---------------------------------------------------------------- console */

/** Command and player names for the TAB completion of the console input. */
function loadSuggestions() {
    api('/api/console/commands').then(function (d) {
        state.suggestions.commands = d.commands || [];
        state.suggestions.players = d.players || [];
        state.suggestions.arguments = d.arguments || {};
    }).catch(function () { /* older builds may not have this route */ });
}

/**
 * Shell-like completion: TAB cycles through the candidates for the token under the cursor,
 * Shift+TAB walks back. The candidates are printed once per cycle so the user sees the options.
 */
function completeConsoleInput(input, backwards) {
    var text = input.value;
    var cursor = input.selectionStart == null ? text.length : input.selectionStart;
    var before = text.slice(0, cursor);
    var token = (before.match(/[^\s]*$/) || [''])[0];
    var isCommand = before.trim().indexOf(' ') < 0;
    var prefix = token.toLowerCase();

    var candidates;
    if (isCommand) {
        candidates = state.suggestions.commands.filter(function (name) {
            return name.toLowerCase().indexOf(prefix) === 0;
        });
    } else {
        var command = before.trim().split(/\s+/)[0].toLowerCase();
        var hint = state.suggestions.arguments[command] || [];
        var names = state.suggestions.players.slice();
        candidates = names.concat(hint).filter(function (value) {
            return value.toLowerCase().indexOf(prefix) === 0;
        });
    }
    candidates = candidates.filter(function (value, index, all) { return all.indexOf(value) === index; }).sort();

    if (!candidates.length) return;

    var cycleKey = prefix + '|' + (isCommand ? 'cmd' : 'arg');
    if (state.completion.base !== cycleKey) {
        state.completion = { base: cycleKey, list: candidates, index: -1 };
        appendLocalLine('  ' + candidates.join('   '), 'cmd-hint');
    }
    var total = state.completion.list.length;
    state.completion.index = (state.completion.index + (backwards ? -1 : 1) + total) % total;

    var choice = state.completion.list[state.completion.index];
    var head = text.slice(0, cursor - token.length);
    var tail = text.slice(cursor);
    if (choice.indexOf(' ') >= 0) choice = '"' + choice + '"';
    input.value = head + choice + tail;
    var caret = (head + choice).length;
    input.setSelectionRange(caret, caret);
}

/** Jump back to the newest output and re-enable following. */
function scrollConsoleToBottom() {
    var out = $('console-output');
    out.scrollTop = out.scrollHeight;
    state.console.follow = true;
    $('follow-toggle').classList.add('selected');
}

/** Following pauses while the user reads older lines - like every good log viewer. */
function bindConsoleFollowPause() {
    var out = $('console-output');
    out.addEventListener('scroll', function () {
        var atBottom = out.scrollHeight - out.scrollTop - out.clientHeight < 24;
        if (atBottom && !state.console.follow) {
            state.console.follow = true;
            $('follow-toggle').classList.add('selected');
        } else if (!atBottom && state.console.follow) {
            state.console.follow = false;
            $('follow-toggle').classList.remove('selected');
        }
    });
}


function connectConsole() {
    var cfg = state.console;
    if (cfg.source) { try { cfg.source.close(); } catch (e) {} cfg.source = null; }

    var url = state.baseUrl + '/api/console/stream?' + authQuery();

    var source = new EventSource(url);
    cfg.source = source;

    source.onopen = function () { setConnectionStatus('Live', 'connected'); };
    source.onerror = function () {
        // EventSource reconnects automatically; the server resumes from Last-Event-ID
        setConnectionStatus('Reconnecting...', 'reconnecting');
    };
    source.onmessage = function (event) {
        setConnectionStatus('Live', 'connected');
        var data;
        try { data = JSON.parse(event.data); } catch (e) { return; }
        appendConsoleLine(data);
    };
}

function appendConsoleLine(line) {
    var cfg = state.console;
    var index = typeof line.index === 'number' ? line.index : (cfg.lastIndex + 1);
    if (cfg.lines[index]) return;                    // already rendered (backfill overlap)

    var el = document.createElement('div');
    el.className = 'console-line';
    el.setAttribute('data-index', index);
    var level = String(line.level || 'INFO').toUpperCase();
    el.setAttribute('data-level', level);
    var ts = line.timestamp ? formatTime(line.timestamp) : '';
    var levelClass = level.indexOf('ERROR') >= 0 ? 'log-error'
        : level.indexOf('WARN') >= 0 ? 'log-warn' : 'log-info';

    el.innerHTML = '<span class="ts">[' + escapeHtml(ts) + ']</span>'
        + '<span class="lvl ' + levelClass + '">[' + escapeHtml(level) + ']</span>'
        + '<span class="msg">' + parseConsoleText(line.message) + '</span>';

    var out = $('console-output');
    out.appendChild(el);
    while (out.childElementCount > cfg.maxDom) {
        var first = out.firstElementChild;
        if (!first) break;
        delete cfg.lines[first.getAttribute('data-index')];
        first.remove();
    }
    cfg.lines[index] = el;
    cfg.lastIndex = Math.max(cfg.lastIndex, index);
    applyFilterTo(el);
    if (cfg.follow) out.scrollTop = out.scrollHeight;
}

function appendLocalLine(text, cls) {
    var out = $('console-output');
    var el = document.createElement('div');
    el.className = 'console-line ' + (cls || 'cmd-echo');
    el.setAttribute('data-level', 'SYSTEM');
    el.innerHTML = parseConsoleText(text);
    out.appendChild(el);
    while (out.childElementCount > state.console.maxDom) {
        var first = out.firstElementChild;
        if (!first) break;
        delete state.console.lines[first.getAttribute('data-index')];
        first.remove();
    }
    if (state.console.follow) out.scrollTop = out.scrollHeight;
}

/** Apply the current level filter to one line. */
function applyFilterTo(el) {
    var level = state.console.level;
    var visible = true;
    if (level !== 'ALL') {
        var l = el.getAttribute('data-level') || '';
        if (level === 'INFO') visible = l.indexOf('ERROR') < 0 && l.indexOf('WARN') < 0;
        else if (level === 'WARN') visible = l.indexOf('WARN') >= 0;
        else if (level === 'ERROR') visible = l.indexOf('ERROR') >= 0;
    }
    el.classList.toggle('filtered', !visible);
}

function queryConsoleLines() {
    var out = $('console-output');
    var result = [];
    for (var i = 0; i < out.children.length; i++) {
        var el = out.children[i];
        if (el.classList.contains('console-line')) result.push(el);
    }
    return result;
}

function applyFilterToAll() {
    var lines = queryConsoleLines();
    for (var i = 0; i < lines.length; i++) applyFilterTo(lines[i]);
    if (state.console.search) runSearch();
}

function setConsoleLevel(level) {
    state.console.level = level;
    var chips = document.querySelectorAll('.chip[data-level]');
    for (var i = 0; i < chips.length; i++) {
        chips[i].classList.toggle('selected', chips[i].getAttribute('data-level') === level);
    }
    applyFilterToAll();
}

/* console search + navigation */
function runSearch() {
    var cfg = state.console;
    var term = cfg.search.trim().toLowerCase();
    cfg.matches = [];
    cfg.matchIndex = -1;
    var lines = queryConsoleLines();
    for (var i = 0; i < lines.length; i++) {
        var el = lines[i];
        var msg = el.querySelector('.msg');
        if (!msg) continue;
        if (msg.getAttribute('data-original') == null) msg.setAttribute('data-original', msg.innerHTML);
        var original = msg.getAttribute('data-original');
        if (!term) { msg.innerHTML = original; continue; }
        if (msg.textContent.toLowerCase().indexOf(term) >= 0) {
            msg.innerHTML = highlight(original, term);
            cfg.matches.push(el);
        } else {
            msg.innerHTML = original;
        }
    }
    $('search-count').textContent = '0/' + cfg.matches.length;
}

/** Case-insensitive highlight that leaves existing HTML tags intact. */
function highlight(html, term) {
    return html.replace(/(<[^>]+>)|([^<]+)/g, function (match, tag, text) {
        if (tag) return tag;
        if (!text) return match;
        var lower = text.toLowerCase();
        var out = '', pos = 0, idx;
        while ((idx = lower.indexOf(term, pos)) >= 0) {
            out += text.slice(pos, idx) + '<mark>' + text.slice(idx, idx + term.length) + '</mark>';
            pos = idx + term.length;
        }
        return out + text.slice(pos);
    });
}

function jumpToMatch(delta) {
    var cfg = state.console;
    if (!cfg.matches.length) return;
    cfg.matchIndex = (cfg.matchIndex + delta + cfg.matches.length) % cfg.matches.length;
    var el = cfg.matches[cfg.matchIndex];
    if (el.classList.contains('filtered')) el.classList.remove('filtered');
    if (el.scrollIntoView) el.scrollIntoView({ block: 'center', behavior: 'smooth' });
    $('search-count').textContent = (cfg.matchIndex + 1) + '/' + cfg.matches.length;
}

function sendCommand(command) {
    if (!command || !command.trim()) return;
    var cmd = command.trim();
    appendLocalLine('> ' + cmd);
    state.history.push(cmd);
    if (state.history.length > 100) state.history.shift();
    try { localStorage.setItem('kodaDashHistory', JSON.stringify(state.history)); } catch (e) {}
    state.historyPos = state.history.length;
    api('/api/console/command', { method: 'POST', body: { command: cmd } })
        .catch(function (e) {
            appendLocalLine('! ' + e.message, 'log-error');
            showToast(e.message, 'err');
        });
}

function downloadLog() {
    window.open(state.baseUrl + '/api/logs/download?' + authQuery(), '_blank');
}

function copyConsole() {
    var text = queryConsoleLines()
        .filter(function (el) { return !el.classList.contains('filtered'); })
        .map(function (el) { return el.textContent; }).join('\n');
    if (navigator.clipboard) {
        navigator.clipboard.writeText(text).then(function () {
            showToast('Output copied', 'ok');
        }).catch(function () { showToast('Clipboard blocked by the browser', 'err'); });
    } else {
        showToast('Clipboard not available', 'err');
    }
}

/* ---------------------------------------------------------------- players */

function loadPlayers() {
    var list = $('players-list');
    // Returns the promise so callers can re-render after the list is fresh
    return api('/api/players').then(function (d) {
        state.players = d.players || [];
        var badge = $('player-count-badge');
        badge.textContent = state.players.length;
        badge.classList.toggle('hidden', state.players.length === 0);
        if (!state.players.length) {
            list.innerHTML = '<div class="empty-state">'
                + '<svg class="icon"><use href="icons.svg#i-group"></use></svg>'
                + '<div>No players online right now</div></div>';
            return;
        }
        list.innerHTML = state.players.map(function (p, i) { return playerCard(p, i); }).join('');
        bindPlayerCards(list);
    }).catch(function (e) {
        list.innerHTML = '<div class="empty-state">' + escapeHtml(e.message) + '</div>';
    });
}

/** Coloured action button; "tone" maps to the state colours used across the dashboard. */
function actionButton(spec) {
    return '<button class="btn btn-tone-' + spec.tone + ' btn-sm"'
        + ' data-player-action="' + spec.action + '"'
        + (spec.altAction ? ' data-player-alt-action="' + spec.altAction + '"' : '')
        + (spec.state ? ' data-player-state="' + spec.state + '"' : '')
        + ' data-player-name="' + escapeHtml(spec.player) + '">'
        + '<svg class="icon icon-sm"><use href="icons.svg#' + spec.icon + '"></use></svg>'
        + escapeHtml(spec.label) + '</button>';
}

/**
 * The action set for one player. Stateful things (OP, whitelist) are a single button that
 * flips its label, colour and target action depending on the current state - no double buttons.
 */
function playerActions(p) {
    return [
        { action: 'heal', label: 'Heal', tone: 'ok', icon: 'i-favorite', player: p.name },
        { action: 'feed', label: 'Feed', tone: 'ok', icon: 'i-restaurant', player: p.name },
        // One button per stateful action: while it is on, the button turns it off again.
        p.isOp
            ? { action: 'op', altAction: 'deop', state: 'on', label: 'Remove OP', tone: 'warn', icon: 'i-star', player: p.name }
            : { action: 'op', state: 'off', label: 'Make OP', tone: 'primary', icon: 'i-star', player: p.name },
        p.isWhitelisted
            ? { action: 'whitelist', altAction: 'unwhitelist', state: 'on', label: 'Whitelisted', tone: 'ok', icon: 'i-shield', player: p.name }
            : { action: 'whitelist', state: 'off', label: 'Whitelist', tone: 'neutral', icon: 'i-shield', player: p.name },
        { action: 'message', label: 'Message', tone: 'neutral', icon: 'i-chat', player: p.name },
        { action: 'kick', label: 'Kick', tone: 'warn', icon: 'i-person_off', player: p.name },
        { action: 'ban', label: 'Ban', tone: 'error', icon: 'i-person_off', player: p.name },
        { action: 'kill', label: 'Kill', tone: 'error', icon: 'i-dangerous', player: p.name },
        { action: 'starve', label: 'Starve', tone: 'warn', icon: 'i-nofood', player: p.name },
        { action: 'wipe', label: 'Wipe data', tone: 'error', icon: 'i-wipe', player: p.name }
    ];
}

function playerCard(p, index) {
    var health = Math.max(0, Math.min(20, Number(p.health != null ? p.health : 20)));
    var pct = Math.round(health / 20 * 100);
    var gm = (p.gamemode || '').toLowerCase();
    var colour = pct > 50 ? 'var(--md-success)' : pct > 25 ? 'var(--md-warning)' : 'var(--md-error)';
    var playtime = p.stats && p.stats.playTimeHours != null ? p.stats.playTimeHours + 'h played' : '';
    var quick = [
        { action: 'heal', label: 'Heal', tone: 'ok', icon: 'i-favorite', player: p.name },
        { action: 'feed', label: 'Feed', tone: 'ok', icon: 'i-restaurant', player: p.name },
        { action: 'kick', label: 'Kick', tone: 'warn', icon: 'i-person_off', player: p.name }
    ];
    return '<div class="list-item" data-player-open="' + index + '" style="cursor:pointer">'
        + '<div class="avatar"><img src="https://mc-heads.net/avatar/' + encodeURIComponent(p.name) + '/44" alt=""></div>'
        + '<div class="body">'
        + '<div class="title">' + escapeHtml(p.name)
        + (p.isOp ? ' <span class="badge badge-warn">OP</span>' : '')
        + (p.isWhitelisted ? ' <span class="badge badge-ok">WL</span>' : '')
        + '<span class="badge badge-muted">' + escapeHtml(gm || 'unknown') + '</span></div>'
        + '<div class="meta">' + escapeHtml(p.world || '') + (playtime ? ' - ' + escapeHtml(playtime) : '') + '</div>'
        + '<div class="healthbar"><span style="width:' + pct + '%;background:' + colour + '"></span></div>'
        + '<div class="actions">' + quick.map(actionButton).join('') + '</div>'
        + '</div></div>';
}

function bindPlayerCards(scope) {
    var cards = scope.querySelectorAll('[data-player-open]');
    for (var i = 0; i < cards.length; i++) {
        cards[i].addEventListener('click', function (ev) {
            if (ev.target.closest('[data-player-action]')) return;
            openPlayerModal(parseInt(this.getAttribute('data-player-open'), 10));
        });
    }
    bindPlayerActions(scope);
}

function bindPlayerActions(scope) {
    var buttons = scope.querySelectorAll('[data-player-action]');
    for (var i = 0; i < buttons.length; i++) {
        buttons[i].addEventListener('click', function (ev) {
            ev.stopPropagation();
            // A stateful button fires its opposite action while the state is on
            var state = this.getAttribute('data-player-state');
            var action = this.getAttribute('data-player-action');
            var alt = this.getAttribute('data-player-alt-action');
            if (state === 'on' && alt) action = alt;
            runPlayerAction(action, this.getAttribute('data-player-name'));
        });
    }
}

/** Vanilla textures straight from a CDN; block/ is tried when there is no item/ texture. */
var ITEM_TEXTURE_BASE = 'https://cdn.jsdelivr.net/gh/InventivetalentDev/minecraft-assets@1.21.4/assets/minecraft/textures/';

window.itemIconFallback = function (img) {
    var blockSrc = img.getAttribute('data-block-src');
    if (blockSrc) {
        img.removeAttribute('data-block-src');
        img.src = blockSrc;
        return;
    }
    var slot = img.parentElement;
    if (slot) {
        slot.classList.add('no-texture');
        slot.setAttribute('data-fallback-label', img.getAttribute('data-label') || '?');
        img.remove();
    }
};

function itemIcon(type, amount) {
    var name = String(type || '').replace(/^minecraft:/, '').toLowerCase();
    var label = prettyItemName(type);
    return '<img class="inv-icon" alt="' + escapeHtml(label) + '" loading="lazy"'
        + ' data-label="' + escapeHtml(label) + '"'
        + ' data-block-src="' + ITEM_TEXTURE_BASE + 'block/' + name + '.png"'
        + ' src="' + ITEM_TEXTURE_BASE + 'item/' + name + '.png"'
        + ' onerror="itemIconFallback(this)">'
        + (amount > 1 ? '<span class="inv-count">' + amount + '</span>' : '');
}

function prettyItemName(type) {
    if (!type) return '';
    return String(type).replace(/^minecraft:/, '').split('_')
        .map(function (part) { return part.charAt(0).toUpperCase() + part.slice(1); }).join(' ');
}

/**
 * Inventory as the server sees it: 0-8 hotbar, 9-35 main, 36-39 armour, 40 offhand.
 * Slots can be rearranged - by dragging on a desktop or by tapping two slots on a phone.
 */
function inventoryHtml(inventory) {
    var slots = inventory.slots || [];
    var byIndex = {};
    slots.forEach(function (slot) { byIndex[slot.slot] = slot; });

    function slotHtml(index, extraClass) {
        var slot = byIndex[index] || {};
        var item = slot.item || null;
        return '<div class="inv-slot' + (item ? ' filled' : '') + (extraClass ? ' ' + extraClass : '') + '"'
            + ' data-slot="' + index + '"'
            + ' title="' + (item ? escapeHtml(prettyItemName(item.type) + ' x' + item.amount) : 'Slot ' + index) + '">'
            + (item ? itemIcon(item.type, item.amount) : '')
            + '</div>';
    }

    var hotbar = '', main = '';
    for (var i = 0; i <= 8; i++) hotbar += slotHtml(i);
    for (var j = 9; j <= 35; j++) main += slotHtml(j);

    return '<div class="inv-section"><div class="inv-label">Hotbar</div><div class="inv-row">' + hotbar + '</div></div>'
        + '<div class="inv-section"><div class="inv-label">Inventory</div><div class="inv-grid">' + main + '</div></div>'
        + '<div class="inv-section"><div class="inv-label">Armour &amp; offhand</div><div class="inv-row">'
        + slotHtml(36) + slotHtml(37) + slotHtml(38) + slotHtml(39) + '<span class="inv-gap"></span>' + slotHtml(40)
        + '</div></div>';
}

function openPlayerModal(index) {
    var p = state.players[index];
    if (!p) return;
    $('player-modal-title').textContent = p.name;
    var stats = p.stats || {};
    var inventory = p.inventory || {};

    var html = '<div class="row" style="gap:16px;align-items:center;margin-bottom:16px">'
        + '<div class="avatar" style="width:64px;height:64px"><img src="https://mc-heads.net/avatar/' + encodeURIComponent(p.name) + '/64" alt=""></div>'
        + '<div><div style="font-size:1.125rem;font-weight:500">' + escapeHtml(p.name)
        + (p.isOp ? ' <span class="badge badge-warn">OP</span>' : '')
        + (p.isWhitelisted ? ' <span class="badge badge-ok">Whitelisted</span>' : '')
        + '</div>'
        + '<div class="muted small mono">' + escapeHtml(p.uuid || '') + '</div></div></div>';

    html += '<div class="info-grid" style="margin-bottom:16px">'
        + infoRow('Gamemode', p.gamemode) + infoRow('World', p.world)
        + infoRow('Health', p.health != null ? Number(p.health).toFixed(1) + ' / 20' : '--')
        + infoRow('Whitelisted', p.isWhitelisted ? 'yes' : 'no')
        + infoRow('Deaths', stats.deaths) + infoRow('Mobs killed', stats.mobsKilled)
        + infoRow('Damage taken', stats.damageTaken)
        + infoRow('Play time', stats.playTimeHours != null ? stats.playTimeHours + ' h' : '--')
        + '</div>';

    html += '<div class="row" style="justify-content:space-between;align-items:baseline;margin-bottom:8px">'
        + '<h3 class="card-title" style="margin:0">Inventory</h3>'
        + '<span class="muted small" id="inv-hint">Drag an item onto another slot, or tap two slots to swap them</span>'
        + '</div><div class="inv-wrap" id="player-inventory">' + inventoryHtml(inventory) + '</div>';

    $('player-modal-body').innerHTML = html;
    bindInventory(p.name);

    $('player-modal-actions').innerHTML = playerActions(p).map(actionButton).join('');
    bindPlayerActions($('player-modal-actions'));

    openModal('player-overlay');
}

/** Selection + drag & drop for the inventory grid. */
function bindInventory(playerName) {
    var wrap = $('player-inventory');
    if (!wrap) return;
    var selected = null;

    function clearSelection() {
        if (selected) selected.classList.remove('selected');
        selected = null;
    }

    function swap(from, to) {
        if (from === to) return;
        api('/api/players/move-item', { method: 'POST', body: { player: playerName, from: from, to: to } })
            .then(function () { return reloadPlayerInventory(playerName); })
            .catch(function (e) { showToast(e.message, 'err'); });
    }

    wrap.querySelectorAll('.inv-slot').forEach(function (slot) {
        slot.setAttribute('draggable', 'true');

        slot.addEventListener('dragstart', function (ev) {
            ev.dataTransfer.setData('text/plain', slot.getAttribute('data-slot'));
            slot.classList.add('dragging');
        });
        slot.addEventListener('dragend', function () { slot.classList.remove('dragging'); });
        slot.addEventListener('dragover', function (ev) { ev.preventDefault(); slot.classList.add('drop-target'); });
        slot.addEventListener('dragleave', function () { slot.classList.remove('drop-target'); });
        slot.addEventListener('drop', function (ev) {
            ev.preventDefault();
            slot.classList.remove('drop-target');
            var from = parseInt(ev.dataTransfer.getData('text/plain'), 10);
            var to = parseInt(slot.getAttribute('data-slot'), 10);
            if (!isNaN(from) && !isNaN(to)) swap(from, to);
        });

        slot.addEventListener('click', function () {
            if (selected === slot) { clearSelection(); return; }
            if (!selected) { selected = slot; slot.classList.add('selected'); return; }
            var from = parseInt(selected.getAttribute('data-slot'), 10);
            var to = parseInt(slot.getAttribute('data-slot'), 10);
            clearSelection();
            swap(from, to);
        });
    });
}

/** Refresh the open inventory after a change, without closing the modal. */
function reloadPlayerInventory(playerName) {
    return api('/api/players').then(function (d) {
        state.players = d.players || [];
        var fresh = state.players.find(function (p) { return p.name === playerName; });
        if (fresh && $('player-overlay').classList.contains('open')) {
            var wrap = $('player-inventory');
            if (wrap) wrap.innerHTML = inventoryHtml(fresh.inventory || {});
            bindInventory(playerName);
        }
        return loadPlayers();
    });
}

function infoRow(key, value) {
    return '<div class="info-row"><span class="k">' + escapeHtml(key) + '</span><span class="v">'
        + escapeHtml(value == null || value === '' ? '--' : String(value)) + '</span></div>';
}

function shorten(type) {
    if (!type) return '';
    return type.replace(/^minecraft:/, '').replace(/_/g, ' ').slice(0, 12);
}

var DANGEROUS_PLAYER_ACTIONS = { kick: 1, ban: 1, wipe: 1, kill: 1, starve: 1 };

function runPlayerAction(action, playerName) {
    var chain;
    if (DANGEROUS_PLAYER_ACTIONS[action]) {
        chain = confirmDialog('Confirm ' + action, 'Really ' + action + ' ' + playerName + '?', true, 'Reason (optional)', '')
            .then(function (result) {
                if (result === false) return null;
                var body = { player: playerName };
                if (typeof result === 'string' && result.trim()) body.reason = result.trim();
                return body;
            });
    } else if (action === 'message') {
        chain = confirmDialog('Message to ' + playerName, 'The player receives this message in chat.', true, 'Message', '')
            .then(function (msg) {
                if (!msg) return null;
                return { player: playerName, message: msg };
            });
    } else {
        chain = Promise.resolve({ player: playerName });
    }

    chain.then(function (body) {
        if (!body) return;
        return api('/api/players/' + action, { method: 'POST', body: body })
            .then(function () {
                showToast(playerName + ': ' + action + ' done', 'ok');
                var idx = state.players.findIndex(function (x) { return x.name === playerName; });
                if (idx >= 0 && $('player-overlay').classList.contains('open')) {
                    // Keep the panel open so the button state visibly flips
                    loadPlayers().then(function () { openPlayerModal(idx); });
                } else {
                    loadPlayers();
                }
            })
            .catch(function (e) { showToast(e.message, 'err'); });
    });
}

/* ------------------------------------------------------------------ files */

function loadFiles(path) {
    state.currentPath = path || '';
    var list = $('files-list');
    var breadcrumb = $('files-breadcrumb');
    api('/api/files/' + encodePath(state.currentPath)).then(function (d) {
        if (!d || d.type !== 'directory') { list.innerHTML = '<div class="empty-state">Not a directory</div>'; return; }
        var entries = d.entries || [];
        breadcrumb.innerHTML = renderBreadcrumb(state.currentPath);
        bindBreadcrumb(breadcrumb);

        if (!entries.length) {
            list.innerHTML = '<div class="empty-state">'
                + '<svg class="icon"><use href="icons.svg#i-folder"></use></svg>'
                + '<div>This folder is empty</div></div>';
            return;
        }

        list.innerHTML = entries.map(function (entry) {
            var full = state.currentPath ? state.currentPath + '/' + entry.name : entry.name;
            var iconName = entry.isDirectory ? 'i-folder' : iconForFile(entry.name);
            return '<div class="file-row" data-dir="' + (entry.isDirectory ? '1' : '0') + '" data-path="' + escapeHtml(full) + '">'
                + '<svg class="icon icon-sm"><use href="icons.svg#' + iconName + '"></use></svg>'
                + '<span class="fname">' + escapeHtml(entry.name) + '</span>'
                + '<span class="fmeta">' + (entry.isDirectory ? 'folder' : formatBytes(entry.size)) + '</span>'
                + '<button class="icon-btn" data-file-action="rename" title="Rename"><svg class="icon icon-sm"><use href="icons.svg#i-edit"></use></svg></button>'
                + '<button class="icon-btn" data-file-action="download" title="Download"><svg class="icon icon-sm"><use href="icons.svg#i-download"></use></svg></button>'
                + '<button class="icon-btn" data-file-action="delete" title="Delete"><svg class="icon icon-sm"><use href="icons.svg#i-delete"></use></svg></button>'
                + '</div>';
        }).join('');
        bindFileRows(list);
    }).catch(function (e) {
        list.innerHTML = '<div class="empty-state">' + escapeHtml(e.message) + '</div>';
    });
}

function bindBreadcrumb(breadcrumb) {
    var crumbs = breadcrumb.querySelectorAll('[data-crumb]');
    for (var i = 0; i < crumbs.length; i++) {
        crumbs[i].addEventListener('click', function () { loadFiles(this.getAttribute('data-crumb')); });
    }
}

function bindFileRows(list) {
    var rows = list.querySelectorAll('.file-row');
    for (var i = 0; i < rows.length; i++) {
        rows[i].addEventListener('click', function (ev) {
            var fullPath = this.getAttribute('data-path');
            var actionBtn = ev.target.closest('[data-file-action]');
            if (actionBtn) {
                ev.stopPropagation();
                var action = actionBtn.getAttribute('data-file-action');
                if (action === 'rename') renameEntry(fullPath);
                else if (action === 'delete') deleteEntry(fullPath);
                else if (action === 'download') downloadFile(fullPath);
                return;
            }
            if (this.getAttribute('data-dir') === '1') loadFiles(fullPath);
            else openFile(fullPath);
        });
    }
}

function encodePath(path) {
    return String(path || '').split('/').map(encodeURIComponent).join('/');
}

function renderBreadcrumb(path) {
    var parts = String(path || '').split('/').filter(Boolean);
    var html = '<span class="crumb" data-crumb="">root</span>';
    var acc = '';
    parts.forEach(function (part) {
        acc = acc ? acc + '/' + part : part;
        html += '<span class="sep">/</span><span class="crumb" data-crumb="' + escapeHtml(acc) + '">' + escapeHtml(part) + '</span>';
    });
    return html;
}

function iconForFile(name) {
    var ext = (name.split('.').pop() || '').toLowerCase();
    if (['yml', 'yaml', 'json', 'properties', 'conf', 'config', 'toml', 'ini'].indexOf(ext) >= 0) return 'i-tune';
    if (ext === 'jar') return 'i-extension';
    if (['log', 'txt', 'md'].indexOf(ext) >= 0) return 'i-description';
    return 'i-description';
}

function renameEntry(path) {
    var current = path.split('/').pop();
    confirmDialog('Rename', 'New name for ' + current, true, 'Name', current).then(function (name) {
        if (!name || name === current) return;
        return api('/api/files/rename', { method: 'POST', body: { path: path, newName: name } })
            .then(function () { showToast('Renamed', 'ok'); loadFiles(state.currentPath); })
            .catch(function (e) { showToast(e.message, 'err'); });
    });
}

function deleteEntry(path) {
    var name = path.split('/').pop();
    confirmDialog('Delete', 'Delete "' + name + '"? This cannot be undone.', false).then(function (ok) {
        if (!ok) return;
        return api('/api/files/' + encodePath(path), { method: 'DELETE' })
            .then(function () { showToast('Deleted', 'ok'); loadFiles(state.currentPath); })
            .catch(function (e) { showToast(e.message, 'err'); });
    });
}

function downloadFile(path) {
    window.open(state.baseUrl + '/api/files/download?path=' + encodeURIComponent(path) + '&' + authQuery(), '_blank');
}

function createEntry(isFolder) {
    confirmDialog(isFolder ? 'New folder' : 'New file', 'Create in ' + (state.currentPath || 'root'),
        true, 'Name', '').then(function (name) {
        if (!name) return;
        var full = state.currentPath ? state.currentPath + '/' + name : name;
        var request = isFolder
            ? api('/api/files/' + encodePath(full), { method: 'PUT' })
            : api('/api/files/' + encodePath(full), { method: 'POST', body: { content: '' } });
        return request.then(function () {
            showToast((isFolder ? 'Folder' : 'File') + ' created', 'ok');
            loadFiles(state.currentPath);
        }).catch(function (e) { showToast(e.message, 'err'); });
    });
}

function uploadFile(file) {
    if (!file) return;
    if (file.size > 10 * 1024 * 1024) { showToast('File too large (max 10 MB)', 'err'); return; }
    var reader = new FileReader();
    reader.onload = function () {
        var base64 = String(reader.result).split(',')[1] || '';
        var full = state.currentPath ? state.currentPath + '/' + file.name : file.name;
        api('/api/files/' + encodePath(full), { method: 'POST', body: { base64: base64 } })
            .then(function () { showToast('Uploaded ' + file.name, 'ok'); loadFiles(state.currentPath); })
            .catch(function (e) { showToast(e.message, 'err'); });
    };
    reader.readAsDataURL(file);
}

/* ------------------------------------------------------------- file editor */

function ensureMonaco(callback) {
    if (state.editor.ready) { callback(); return; }
    state.editor.pending = callback;
    if (state.editor.loading) return;
    state.editor.loading = true;

    window.MonacoEnvironment = {
        getWorkerUrl: function () { return 'monaco/vs/base/worker/workerMain.js'; }
    };
    require.config({ paths: { vs: 'monaco/vs' } });
    require(['vs/editor/editor.main'], function () {
        state.editor.ready = true;
        state.editor.loading = false;
        if (state.editor.pending) { var cb = state.editor.pending; state.editor.pending = null; cb(); }
    }, function (err) {
        state.editor.loading = false;
        showToast('Editor failed to load: ' + err, 'err');
    });
}

function monacoLanguage(name) {
    var ext = (name.split('.').pop() || '').toLowerCase();
    var map = {
        yml: 'yaml', yaml: 'yaml', json: 'json', properties: 'ini', ini: 'ini', toml: 'ini',
        js: 'javascript', java: 'java', sh: 'shell', md: 'markdown', html: 'html', xml: 'xml',
        txt: 'plaintext', log: 'plaintext', conf: 'ini', cfg: 'ini'
    };
    return map[ext] || 'plaintext';
}

function openFile(path) {
    api('/api/files/' + encodePath(path)).then(function (data) {
        if (!data || data.type !== 'file') { showToast('Not a file', 'err'); return; }
        if (data.tooLarge) {
            showToast('File is too large or binary — use the download button instead.', 'err');
            return;
        }
        state.editor.file = path;
        $('editor-filename').textContent = path;
        openModal('editor-overlay');

        ensureMonaco(function () {
            var container = $('monaco-container');
            if (state.editor.instance) state.editor.instance.dispose();
            state.editor.instance = monaco.editor.create(container, {
                value: data.content || '',
                language: monacoLanguage(path),
                theme: 'vs-dark',
                automaticLayout: true,
                fontSize: 13,
                minimap: { enabled: false },
                scrollBeyondLastLine: false,
                renderWhitespace: 'selection'
            });
            state.editor.instance.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, saveFile);
        });
    }).catch(function (e) { showToast(e.message, 'err'); });
}

function saveFile() {
    if (!state.editor.instance || !state.editor.file) return;
    var content = state.editor.instance.getValue();
    api('/api/files/' + encodePath(state.editor.file), { method: 'POST', body: { content: content } })
        .then(function () {
            showToast('Saved ' + state.editor.file, 'ok');
            loadFiles(state.currentPath);
        })
        .catch(function (e) { showToast(e.message, 'err'); });
}

/* ---------------------------------------------------------------- plugins */

function loadPlugins() {
    var list = $('plugins-list');
    api('/api/plugins').then(function (d) {
        var plugins = d.plugins || [];
        if (!plugins.length) { list.innerHTML = '<div class="empty-state">No plugins installed</div>'; return; }
        list.innerHTML = plugins.map(function (p) {
            var enabled = p.enabled !== false;
            var colour = enabled ? 'var(--md-success)' : 'var(--md-outline)';
            var icon = p.hasIcon
                ? '<img class="plugin-icon" loading="lazy" alt="" src="api/plugins/icon?plugin='
                    + encodeURIComponent(p.name) + '&' + authQuery() + '">'
                : '<svg class="icon" style="color:' + colour + '"><use href="icons.svg#i-extension"></use></svg>';
            return '<div class="list-item">'
                + '<div class="avatar plugin-avatar">' + icon + '</div>'
                + '<div class="body"><div class="title">' + escapeHtml(p.name)
                + '<span class="badge ' + (enabled ? 'badge-ok' : 'badge-muted') + '">' + (enabled ? 'enabled' : 'disabled') + '</span></div>'
                + '<div class="meta">' + escapeHtml(p.version || '') + (p.authors ? ' - ' + escapeHtml(p.authors) : '') + '</div>'
                + (p.description ? '<div class="meta">' + escapeHtml(p.description) + '</div>' : '')
                + '<div class="actions"><button class="btn btn-tonal btn-sm" data-plugin-toggle="' + (enabled ? 'disable' : 'enable') + '" data-plugin-name="' + escapeHtml(p.name) + '">'
                + (enabled ? 'Disable' : 'Enable') + '</button></div></div></div>';
        }).join('');
        var buttons = list.querySelectorAll('[data-plugin-toggle]');
        for (var i = 0; i < buttons.length; i++) {
            buttons[i].addEventListener('click', function () {
                togglePlugin(this.getAttribute('data-plugin-toggle'), this.getAttribute('data-plugin-name'));
            });
        }
    }).catch(function (e) {
        list.innerHTML = '<div class="empty-state">' + escapeHtml(e.message) + '</div>';
    });
}

function togglePlugin(action, name) {
    confirmDialog(action === 'disable' ? 'Disable plugin' : 'Enable plugin',
        action + ' "' + name + '"? A restart may be required.', false).then(function (ok) {
        if (!ok) return;
        return api('/api/plugins/' + action, { method: 'POST', body: { plugin: name } })
            .then(function () { showToast(name + ' ' + action + 'd', 'ok'); loadPlugins(); })
            .catch(function (e) { showToast(e.message, 'err'); });
    });
}

function searchModrinth() {
    var query = $('modrinth-query').value.trim();
    var results = $('modrinth-results');
    if (!query) return;
    results.innerHTML = '<div class="empty-state"><svg class="icon spin"><use href="icons.svg#i-refresh"></use></svg><div>Searching...</div></div>';
    api('/api/plugins/search?query=' + encodeURIComponent(query)).then(function (d) {
        var hits = d.results || [];
        if (!hits.length) { results.innerHTML = '<div class="empty-state">No plugins found</div>'; return; }
        results.innerHTML = hits.map(function (hit) {
            var icon = hit.iconUrl
                ? '<img src="' + escapeHtml(hit.iconUrl) + '" alt="">'
                : '<svg class="icon"><use href="icons.svg#i-extension"></use></svg>';
            return '<div class="list-item"><div class="avatar">' + icon + '</div>'
                + '<div class="body"><div class="title">' + escapeHtml(hit.title) + '</div>'
                + '<div class="meta">' + escapeHtml(hit.author || '') + ' - ' + Number(hit.downloads || 0).toLocaleString() + ' downloads</div>'
                + '<div class="meta">' + escapeHtml((hit.description || '').slice(0, 160)) + '</div>'
                + '<div class="actions"><a class="btn btn-outlined btn-sm" target="_blank" rel="noopener" href="https://modrinth.com/plugin/' + encodeURIComponent(hit.slug || hit.projectId) + '">Open page</a>'
                + '<button class="btn btn-filled btn-sm" data-install="' + escapeHtml(hit.projectId) + '" data-name="' + escapeHtml(hit.title) + '">Install</button>'
                + '</div></div></div>';
        }).join('');
        var buttons = results.querySelectorAll('[data-install]');
        for (var i = 0; i < buttons.length; i++) {
            buttons[i].addEventListener('click', function () {
                installPlugin(this.getAttribute('data-name'));
            });
        }
    }).catch(function (e) {
        results.innerHTML = '<div class="empty-state">' + escapeHtml(e.message) + '</div>';
    });
}

function installPlugin(name) {
    confirmDialog('Install plugin', 'Install "' + name + '" from Modrinth? The server downloads the jar into the plugins folder.', true,
        'Direct .jar download URL', '').then(function (url) {
        if (!url) return;
        return api('/api/plugins/install', {
            method: 'POST',
            body: { url: url, filename: name.replace(/[^A-Za-z0-9_.-]/g, '_') + '.jar' }
        }).then(function () {
            showToast('Download started', 'ok');
            setTimeout(loadPlugins, 3000);
        }).catch(function (e) { showToast(e.message, 'err'); });
    });
}

/* --------------------------------------------------------------- settings */

var SETTING_GROUPS = {
    'Gameplay': ['gamemode', 'difficulty', 'pvp', 'force-gamemode', 'hardcore', 'allow-flight', 'player-idle-timeout', 'spawn-protection', 'max-players'],
    'World': ['level-name', 'level-seed', 'level-type', 'generate-structures', 'spawn-monsters', 'spawn-animals', 'spawn-npcs', 'allow-nether', 'max-world-size', 'view-distance', 'simulation-distance'],
    'Network': ['server-port', 'server-ip', 'online-mode', 'white-list', 'enforce-whitelist', 'enable-query', 'query.port', 'enable-rcon', 'rcon.port', 'network-compression-threshold'],
    'Performance': ['max-tick-time', 'entity-broadcast-range-percentage', 'sync-chunk-writes', 'use-native-transport'],
    'Other': []
};

function loadSettings() {
    var container = $('settings-sections');
    container.innerHTML = '<div class="empty-state"><svg class="icon spin"><use href="icons.svg#i-refresh"></use></svg><div>Loading...</div></div>';
    api('/api/settings').then(function (d) {
        state.settings = d.properties || {};
        state.settingsDirty = {};
        renderSettings();
    }).catch(function (e) {
        container.innerHTML = '<div class="empty-state">' + escapeHtml(e.message) + '</div>';
    });
}

function renderSettings() {
    var container = $('settings-sections');
    var filter = ($('settings-search').value || '').trim().toLowerCase();
    var props = state.settings || {};
    var assigned = {};
    Object.keys(SETTING_GROUPS).forEach(function (g) {
        SETTING_GROUPS[g].forEach(function (k) { assigned[k] = 1; });
    });

    var html = '';
    Object.keys(SETTING_GROUPS).forEach(function (group) {
        var keys = group === 'Other'
            ? Object.keys(props).filter(function (k) { return !assigned[k]; }).sort()
            : SETTING_GROUPS[group].filter(function (k) { return props[k] != null; });
        var visible = keys.filter(function (k) { return !filter || k.toLowerCase().indexOf(filter) >= 0; });
        if (!visible.length) return;
        html += '<div class="card" style="margin-bottom:16px"><h2 class="card-title">' + group + '</h2>';
        html += visible.map(function (key) { return settingRow(key, props[key]); }).join('');
        html += '</div>';
    });

    container.innerHTML = html || '<div class="empty-state">No settings match</div>';

    var inputs = container.querySelectorAll('[data-setting]');
    for (var i = 0; i < inputs.length; i++) {
        inputs[i].addEventListener('change', function () {
            var key = this.getAttribute('data-setting');
            state.settingsDirty[key] = this.type === 'checkbox' ? (this.checked ? 'true' : 'false') : this.value;
            $('settings-restart-notice').classList.remove('hidden');
        });
    }
}

function settingRow(key, value) {
    var str = String(value);
    var isBool = str === 'true' || str === 'false';
    var isNumber = /^-?\d+$/.test(str) && str.length < 10;
    var control;
    if (isBool) {
        control = '<input type="checkbox" data-setting="' + escapeHtml(key) + '"' + (str === 'true' ? ' checked' : '') + ' style="width:20px;height:20px;accent-color:var(--brand)">';
    } else if (isNumber) {
        control = '<input type="number" class="input" style="max-width:160px;padding:8px 12px" data-setting="' + escapeHtml(key) + '" value="' + escapeHtml(str) + '">';
    } else {
        control = '<input type="text" class="input" style="max-width:280px;padding:8px 12px" data-setting="' + escapeHtml(key) + '" value="' + escapeHtml(str) + '">';
    }
    return '<div class="info-row"><span class="k mono">' + escapeHtml(key) + '</span><span class="v">' + control + '</span></div>';
}

function saveSettings() {
    var dirty = state.settingsDirty || {};
    var keys = Object.keys(dirty);
    if (!keys.length) { showToast('Nothing changed', 'ok'); return; }
    api('/api/settings', { method: 'POST', body: { properties: dirty } }).then(function () {
        keys.forEach(function (k) { state.settings[k] = dirty[k]; });
        state.settingsDirty = {};
        $('settings-restart-notice').classList.add('hidden');
        showToast('Saved ' + keys.length + ' setting(s) - restart required', 'ok');
    }).catch(function (e) { showToast(e.message, 'err'); });
}

/* ----------------------------------------------------------------- dialogs */

function openModal(id) { $(id).classList.add('open'); }
function closeModal(id) { $(id).classList.remove('open'); }

/**
 * Confirm/prompt dialog.
 * Resolves to false when cancelled, true when confirmed without input,
 * or the entered string when an input field is shown.
 */
function confirmDialog(title, message, needsInput, inputLabel, defaultValue) {
    return new Promise(function (resolve) {
        $('dialog-title').textContent = title;
        $('dialog-message').textContent = message;
        var group = $('dialog-input-group');
        var input = $('dialog-input');
        group.classList.toggle('hidden', !needsInput);
        $('dialog-input-label').textContent = inputLabel || 'Value';
        input.value = defaultValue || '';

        function cleanup() {
            $('dialog-confirm').onclick = null;
            $('dialog-cancel').onclick = null;
            closeModal('dialog-overlay');
        }

        $('dialog-confirm').onclick = function () {
            var value = needsInput ? input.value : true;
            cleanup();
            resolve(value);
        };
        $('dialog-cancel').onclick = function () { cleanup(); resolve(false); };
        openModal('dialog-overlay');
        if (needsInput) setTimeout(function () { input.focus(); }, 120);
    });
}

function handleUnauthorized() {
    if (!state.connected) return;
    showToast('Session expired - please sign in again', 'err');
    signOut();
}

/* --------------------------------------------------------------- bootstrap */

function init() {
    state.baseUrl = '';
    var params = new URLSearchParams(window.location.search);
    if (params.get('server')) {
        state.baseUrl = params.get('server').replace(/\/$/, '');
    }

    try {
        var saved = localStorage.getItem('kodaDashHistory');
        if (saved) state.history = JSON.parse(saved) || [];
    } catch (e) {}
    state.historyPos = state.history.length;

    /* navigation */
    var navItems = document.querySelectorAll('.nav-item[data-tab]');
    for (var n = 0; n < navItems.length; n++) {
        navItems[n].addEventListener('click', function () { switchTab(this.getAttribute('data-tab')); });
    }
    $('logout-btn').addEventListener('click', signOut);
    $('mobile-menu-btn').addEventListener('click', openSidebar);
    $('mobile-menu-close').addEventListener('click', closeSidebar);
    $('scrim').addEventListener('click', closeSidebar);

    /* login */
    $('login-form').addEventListener('submit', function (e) { e.preventDefault(); doLogin(); });

    /* overview */
    $('btn-restart').addEventListener('click', function () { triggerServerAction('restart'); });
    $('btn-stop').addEventListener('click', function () { triggerServerAction('stop'); });
    $('btn-cancel-action').addEventListener('click', function () {
        api('/api/server-action', { method: 'POST', body: { action: 'cancel' } })
            .catch(function () {})
            .then(function () { loadServerActionState(); });
    });

    /* console */
    $('console-form').addEventListener('submit', function (e) {
        e.preventDefault();
        var input = $('console-input');
        var value = input.value;
        input.value = '';
        sendCommand(value);
    });
    var chips = document.querySelectorAll('.chip[data-level]');
    for (var c = 0; c < chips.length; c++) {
        chips[c].addEventListener('click', function () { setConsoleLevel(this.getAttribute('data-level')); });
    }
    $('follow-toggle').addEventListener('click', function () {
        state.console.follow = !state.console.follow;
        this.classList.toggle('selected', state.console.follow);
    });
    $('console-clear').addEventListener('click', function () {
        $('console-output').innerHTML = '';
        state.console.lines = {};
        state.console.matches = [];
        $('search-count').textContent = '0/0';
    });
    $('console-copy').addEventListener('click', copyConsole);
    $('console-download').addEventListener('click', downloadLog);
    $('console-search').addEventListener('input', function () {
        state.console.search = this.value;
        runSearch();
    });
    $('search-next').addEventListener('click', function () { jumpToMatch(1); });
    $('search-prev').addEventListener('click', function () { jumpToMatch(-1); });

    var consoleInput = $('console-input');
    consoleInput.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
            // Explicit submit: implicit form submission is not reliable across browsers
            e.preventDefault();
            var pending = consoleInput.value;
            consoleInput.value = '';
            state.historyPos = state.history.length;
            state.completion = { base: '', list: [], index: -1 };
            sendCommand(pending);
        } else if (e.key === 'Tab') {
            e.preventDefault();
            completeConsoleInput(consoleInput, e.shiftKey);
        } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            if (state.historyPos > 0) {
                state.historyPos--;
                consoleInput.value = state.history[state.historyPos] || '';
            }
        } else if (e.key === 'ArrowDown') {
            e.preventDefault();
            if (state.historyPos < state.history.length - 1) {
                state.historyPos++;
                consoleInput.value = state.history[state.historyPos] || '';
            } else {
                state.historyPos = state.history.length;
                consoleInput.value = '';
            }
        } else if (e.ctrlKey && (e.key === 'l' || e.key === 'L')) {
            // Ctrl+L clears the view, Ctrl+U clears the line (like a terminal)
            e.preventDefault();
            $('console-output').innerHTML = '';
            state.console.lines = {};
            state.console.matches = [];
            $('search-count').textContent = '0/0';
        } else if (e.ctrlKey && (e.key === 'u' || e.key === 'U')) {
            e.preventDefault();
            consoleInput.value = '';
        } else if (e.key === 'Escape') {
            consoleInput.value = '';
        }
    });

    // Any other key starts a new completion cycle
    consoleInput.addEventListener('input', function () {
        state.completion = { base: '', list: [], index: -1 };
    });

    bindConsoleFollowPause();

    /* players */
    $('players-refresh').addEventListener('click', loadPlayers);
    $('player-modal-close').addEventListener('click', function () { closeModal('player-overlay'); });

    /* files */
    $('new-file-btn').addEventListener('click', function () { createEntry(false); });
    $('new-folder-btn').addEventListener('click', function () { createEntry(true); });
    $('refresh-files-btn').addEventListener('click', function () { loadFiles(state.currentPath); });
    $('upload-btn').addEventListener('click', function () { $('upload-input').click(); });
    $('upload-input').addEventListener('change', function () {
        var file = this.files && this.files[0];
        this.value = '';
        uploadFile(file);
    });

    /* editor */
    $('editor-save-btn').addEventListener('click', saveFile);
    $('editor-close-btn').addEventListener('click', function () { closeModal('editor-overlay'); });
    $('editor-download').addEventListener('click', function () { if (state.editor.file) downloadFile(state.editor.file); });

    /* plugins */
    $('plugins-refresh').addEventListener('click', loadPlugins);
    $('open-modrinth').addEventListener('click', function () {
        openModal('modrinth-overlay');
        setTimeout(function () { $('modrinth-query').focus(); }, 120);
    });
    $('modrinth-close').addEventListener('click', function () { closeModal('modrinth-overlay'); });
    $('modrinth-search-btn').addEventListener('click', searchModrinth);
    $('modrinth-query').addEventListener('keydown', function (e) {
        if (e.key === 'Enter') { e.preventDefault(); searchModrinth(); }
    });

    /* settings */
    $('settings-save').addEventListener('click', saveSettings);
    $('settings-search').addEventListener('input', renderSettings);

    /* overlay click closes a modal (the editor is closed with its own button) */
    var overlays = document.querySelectorAll('.modal-overlay');
    for (var o = 0; o < overlays.length; o++) {
        overlays[o].addEventListener('click', function (e) {
            if (e.target === this && this.id !== 'editor-overlay') this.classList.remove('open');
        });
    }

    /* footer: switch tabs and show the plugin version */
    var footerLinks = document.querySelectorAll('[data-footer-tab]');
    for (var f = 0; f < footerLinks.length; f++) {
        footerLinks[f].addEventListener('click', function () {
            switchTab(this.getAttribute('data-footer-tab'));
            window.scrollTo({ top: 0, behavior: 'smooth' });
        });
    }

    /* auto login via ?token= or a stored session */
    var urlToken = params.get('token');
    var savedToken = sessionStorage.getItem('kodaDashToken');
    var savedPassword = sessionStorage.getItem('kodaDashPassword');
    if (urlToken) {
        $('token-input').value = urlToken;
        if (savedPassword) $('password-input').value = savedPassword;
        doLogin();
    } else if (savedToken) {
        $('token-input').value = savedToken;
        if (savedPassword) $('password-input').value = savedPassword;
        doLogin();
    }
}

document.addEventListener('DOMContentLoaded', init);
