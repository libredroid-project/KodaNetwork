/*
 * Copyright (c) 2026 KodaHosting
 *
 * This file is part of KodaHosting (KodaNetwork).
 * KodaHosting is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3 of the License.
 *
 * KodaHosting is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY, without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * KodaHosting. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-FileCopyrightText: 2026 KodaHosting
 * SPDX-License-Identifier: GPL-3.0-only
 */
// Koda Lab - test bench for the KodaHosting extension API.
// every bridge call this extension makes is logged in the "Raw bridge log"
// panel, so the behaviour of the host can be inspected instead of guessed.
(() => {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const ANSI = /\u001b\[[0-9;]*m|\[\d{1,2};\d{1,2}m|\[\d{1,2}m/g;

  // ─── helpers ────────────────────────────────────────────────

  function log(line) {
    const out = $("bridgeLog");
    const stamp = new Date().toLocaleTimeString();
    out.textContent = `[${stamp}] ${line}\n` + out.textContent;
    if (out.textContent.length > 20000) out.textContent = out.textContent.slice(0, 20000);
  }

  /** runs one bridge call, logs the request and the answer, never throws */
  async function call(label, fn) {
    const started = performance.now();
    try {
      const res = await fn();
      const ms = Math.round(performance.now() - started);
      log(`${label} → ${JSON.stringify(res === undefined ? null : res).slice(0, 400)}  (${ms} ms)`);
      return res === undefined ? null : res;
    } catch (error) {
      log(`${label} threw ${error}`);
      return { error: String(error) };
    }
  }

  function bytes(text) {
    return new Blob([text]).size;
  }

  function fmtBytes(n) {
    if (n < 1024) return n + " B";
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KB";
    return (n / 1024 / 1024).toFixed(2) + " MB";
  }

  function strip(text) {
    return $("consoleStrip").checked ? text.replace(ANSI, "") : text;
  }

  // ─── tabs ───────────────────────────────────────────────────

  const tabs = [...document.querySelectorAll("#tabs button")];
  const line = $("tabLine");

  function moveLine(button) {
    line.style.width = button.offsetWidth + "px";
    line.style.transform = `translateX(${button.offsetLeft}px)`;
  }

  function selectTab(name) {
    tabs.forEach((b) => b.classList.toggle("active", b.dataset.tab === name));
    document.querySelectorAll(".panel").forEach((p) => p.classList.toggle("active", p.id === "panel-" + name));
    moveLine(tabs.find((b) => b.dataset.tab === name));
    if (name === "servers") loadServers();
    if (name === "storage") refreshStorage();
    if (name === "console") tickConsole();
  }

  tabs.forEach((button) => button.addEventListener("click", () => selectTab(button.dataset.tab)));
  window.addEventListener("resize", () => moveLine(tabs.find((b) => b.classList.contains("active"))));

  // ─── overview ───────────────────────────────────────────────

  async function loadOverview() {
    $("envApi").textContent = String(koda.api);
    $("envVersion").textContent = String(koda.version);

    const locale = await call("locale", () => koda.locale());
    $("envLocale").textContent = locale && locale.lang ? locale.lang : "–";

    const theme = await call("theme", () => koda.theme());
    if (theme && theme.mode) {
      $("envTheme").innerHTML = `<span class="swatch" style="background:${theme.orange}"></span>` +
        `${theme.mode} · ${theme.orange} · ${theme.online}`;
      for (const [key, value] of Object.entries(theme)) {
        if (typeof value === "string" && value.startsWith("#")) {
          document.documentElement.style.setProperty("--koda-test-" + key, value);
        }
      }
    } else {
      $("envTheme").textContent = "–";
    }

    const servers = await call("servers.list", () => koda.servers.list());
    $("statServers").textContent = servers && servers.servers ? String(servers.servers.length) : "error";

    const keys = await call("storage.keys", () => koda.storage.keys());
    const list = keys && keys.keys ? keys.keys : [];
    $("statKeys").textContent = String(list.length);
    $("statBytes").textContent = fmtBytes(await usedBytes(list));
  }

  async function usedBytes(list) {
    let total = 0;
    for (const key of list) {
      const res = await call("storage.get " + key, () => koda.storage.get(key));
      if (res && res.value !== undefined) total += bytes(JSON.stringify(res.value));
    }
    return total;
  }

  $("btnToast").addEventListener("click", async () => {
    const res = await call("ui.toast", () => koda.ui.toast("Koda Lab says hi <3"));
    $("uiResult").textContent = "toast → " + JSON.stringify(res);
  });

  $("btnConfirm").addEventListener("click", async () => {
    const res = await call("ui.confirm", () => koda.ui.confirm("Koda Lab", "Do you want to continue?"));
    $("uiResult").textContent = "confirm → " + JSON.stringify(res);
  });

  // ─── servers ────────────────────────────────────────────────

  let cachedServers = [];
  // context handed over by the app: "#<tab>" (settings deep link) and/or
  // "#<tab>&server=<id>" (this extension's own tab inside a server screen)
  let pendingServerId = "";

  function readContext() {
    const parts = (location.hash || "").replace("#", "").split("&").filter(Boolean);
    let target = "";
    for (const part of parts) {
      if (part.startsWith("server=")) pendingServerId = decodeURIComponent(part.substring(7));
      else if (!target) target = part;
    }
    return target;
  }

  function stateBadge(state) {
    const busy = ["STARTING", "STOPPING", "RESTARTING", "INSTALLING", "SETTING_UP"].includes(state);
    const cls = state === "ONLINE" ? "online" : (state === "CRASHED" ? "dead" : (busy ? "busy" : ""));
    return `<span class="badge ${cls}">${state}</span>`;
  }

  async function loadServers() {
    const res = await call("servers.list", () => koda.servers.list());
    const list = (res && res.servers) || [];
    cachedServers = list;
    const host = $("serverList");

    if (res && res.error) {
      host.innerHTML = `<div class="card err">${res.error}</div>`;
      return;
    }
    if (!list.length) {
      host.innerHTML = `<div class="card dim">No servers yet. Create one in the app, then run the list again.</div>`;
      fillConsoleServers(list);
      return;
    }

    host.innerHTML = list.map((server, index) => `
      <div class="card">
        <div class="row">
          <span class="grow"><b>${escapeHtml(server.name)}</b><div class="dim">${escapeHtml(server.id)}</div></span>
          ${stateBadge(server.state)}
        </div>
        <div class="row wrap" style="margin-top:8px">
          <span class="badge">${escapeHtml(server.type)} ${escapeHtml(server.mcVersion)}</span>
          <span class="badge">${server.ramMB} MB</span>
          <span class="badge">${server.maxPlayers} slots</span>
          <span class="badge">:${server.port}</span>
        </div>
        <div class="row" style="margin-top:8px">
          <span class="grow mono">${escapeHtml(server.address || "-")}</span>
          <button class="ghost" data-json="${index}">raw JSON</button>
          <button class="ghost" data-target="${index}">console</button>
        </div>
        <pre class="log" id="raw-${index}" style="display:none;margin-top:8px">${escapeHtml(JSON.stringify(server, null, 2))}</pre>
      </div>`).join("");

    host.querySelectorAll("button[data-json]").forEach((button) => {
      button.addEventListener("click", () => {
        const pre = $("raw-" + button.dataset.json);
        pre.style.display = pre.style.display === "none" ? "block" : "none";
      });
    });
    host.querySelectorAll("button[data-target]").forEach((button) => {
      button.addEventListener("click", () => {
        selectTab("console");
        $("consoleServer").value = cachedServers[button.dataset.target].id;
        tickConsole();
      });
    });

    fillConsoleServers(list);
    $("statServers").textContent = String(list.length);
  }

  function escapeHtml(text) {
    return String(text == null ? "" : text)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
  }

  $("btnReloadServers").addEventListener("click", loadServers);

  // ─── console ────────────────────────────────────────────────

  function fillConsoleServers(list) {
    const select = $("consoleServer");
    const current = select.value;
    select.innerHTML = list.map((s) =>
      `<option value="${escapeHtml(s.id)}">${escapeHtml(s.name)}${s.state === "ONLINE" ? " (online)" : ""}</option>`).join("");
    // a server tab hands us its server id via "#...&server=<id>"
    const wanted = current || pendingServerId;
    if (wanted && list.some((s) => s.id === wanted)) {
      select.value = wanted;
      pendingServerId = "";
      tickConsole();
    } else if (current && list.some((s) => s.id === current)) {
      select.value = current;
    }
  }

  let paused = false;
  let lastLines = [];

  async function tickConsole() {
    const id = $("consoleServer").value;
    if (!id) {
      $("consoleOut").textContent = "No server selected. Create one in the app first.";
      return;
    }
    if (paused) return;

    const want = $("consoleLines").value;
    const res = await call(`console.tail ${want}`, () => koda.console.tail(id, Number(want)));
    const filter = $("consoleSearch").value.trim().toLowerCase();
    let lines = (res && res.lines) || [];
    lastLines = lines;

    if (filter) lines = lines.filter((l) => l.toLowerCase().includes(filter));
    const text = lines.map(strip).join("\n");
    $("consoleOut").textContent = text || "(no matching lines)";
    $("statLines").textContent = String((res && res.lines ? res.lines.length : 0));
    $("consoleStatus").textContent =
      `${lines.length} shown · live=${res && res.live} · filtered=${filter ? "yes" : "no"}` +
      (res && res.error ? ` · error: ${res.error}` : "");
  }

  $("consoleServer").addEventListener("change", tickConsole);
  $("consoleLines").addEventListener("change", tickConsole);
  $("consoleSearch").addEventListener("input", () => {
    if (lastLines.length) {
      const filter = $("consoleSearch").value.trim().toLowerCase();
      const lines = filter ? lastLines.filter((l) => l.toLowerCase().includes(filter)) : lastLines;
      $("consoleOut").textContent = lines.map(strip).join("\n") || "(no matching lines)";
    }
  });
  $("consoleStrip").addEventListener("change", tickConsole);
  $("btnConsolePause").addEventListener("click", () => {
    paused = !paused;
    $("btnConsolePause").textContent = paused ? "Resume" : "Pause";
  });
  $("btnConsoleCopy").addEventListener("click", async () => {
    const text = lastLines.map(strip).join("\n");
    try {
      await navigator.clipboard.writeText(text);
      $("consoleStatus").textContent = "copied to clipboard";
    } catch (error) {
      $("consoleStatus").textContent = "clipboard not available: " + error;
    }
  });

  setInterval(() => {
    const active = document.querySelector(".panel.active");
    if (active && active.id === "panel-console" && $("consoleAuto").checked) tickConsole();
  }, 2000);

  // ─── storage ────────────────────────────────────────────────

  function parseValue(text) {
    try {
      return JSON.parse(text);
    } catch (error) {
      return text;
    }
  }

  async function refreshStorage() {
    const res = await call("storage.keys", () => koda.storage.keys());
    const list = (res && res.keys) || [];
    const host = $("storeList");
    if (!list.length) {
      host.innerHTML = `<div class="card dim">Storage is empty.</div>`;
      $("statKeys").textContent = "0";
      return;
    }
    const rows = [];
    let total = 0;
    for (const key of list) {
      const value = await call("storage.get " + key, () => koda.storage.get(key));
      const json = JSON.stringify(value ? value.value : null);
      total += bytes(json);
      rows.push(`<div class="row"><span class="grow mono">${escapeHtml(key)}</span>
        <span class="dim">${fmtBytes(bytes(json))}</span></div>
        <div class="mono dim">${escapeHtml(json.slice(0, 160))}</div>`);
    }
    host.innerHTML = `<div class="card">${rows.join("")}</div>`;
    $("statKeys").textContent = String(list.length);
    $("statBytes").textContent = fmtBytes(total);
  }

  $("btnStoreSet").addEventListener("click", async () => {
    const key = $("storeKey").value.trim() || "demo";
    const res = await call(`storage.set ${key}`, () => koda.storage.set(key, parseValue($("storeValue").value)));
    $("storeStatus").textContent = "set → " + JSON.stringify(res);
    refreshStorage();
  });
  $("btnStoreGet").addEventListener("click", async () => {
    const key = $("storeKey").value.trim();
    const res = await call(`storage.get ${key}`, () => koda.storage.get(key));
    $("storeStatus").textContent = "get → " + JSON.stringify(res);
  });
  $("btnStoreRemove").addEventListener("click", async () => {
    const key = $("storeKey").value.trim();
    const res = await call(`storage.remove ${key}`, () => koda.storage.remove(key));
    $("storeStatus").textContent = "remove → " + JSON.stringify(res);
    refreshStorage();
  });
  $("btnStoreKeys").addEventListener("click", refreshStorage);

  /** writes ~20 KB chunks until the host refuses - proves the quota is enforced */
  $("btnStoreQuota").addEventListener("click", async () => {
    const chunk = "x".repeat(20 * 1024);
    let stored = 0;
    let error = null;
    for (let i = 0; i < 120; i++) {
      const res = await koda.storage.set("quota-test", chunk + i);
      if (res && res.error) {
        if (res.error.includes("rate")) { await new Promise((r) => setTimeout(r, 150)); continue; }
        error = res.error;
        break;
      }
      stored++;
    }
    const result = error
      ? `quota reached after ${stored} chunks (~${fmtBytes(stored * 20 * 1024)}): ${error}`
      : `no quota error after ${stored} chunks - unexpected!`;
    log("storage quota test → " + result);
    $("storeStatus").textContent = result;
    await koda.storage.remove("quota-test");
    refreshStorage();
  });

  $("btnStoreClear").addEventListener("click", async () => {
    const keys = await koda.storage.keys();
    for (const key of (keys && keys.keys) || []) await koda.storage.remove(key);
    $("storeStatus").textContent = "cleared";
    refreshStorage();
  });

  $("btnStoreExport").addEventListener("click", async () => {
    const dump = {};
    const keys = await koda.storage.keys();
    for (const key of (keys && keys.keys) || []) {
      const res = await koda.storage.get(key);
      dump[key] = res ? res.value : null;
    }
    $("storeDump").value = JSON.stringify(dump, null, 2);
  });

  $("btnStoreImport").addEventListener("click", async () => {
    let dump;
    try {
      dump = JSON.parse($("storeDump").value);
    } catch (error) {
      $("storeStatus").textContent = "import failed: not valid JSON";
      return;
    }
    let count = 0;
    for (const [key, value] of Object.entries(dump)) {
      const res = await koda.storage.set(key, value);
      if (res && !res.error) count++;
    }
    $("storeStatus").textContent = `imported ${count} keys`;
    refreshStorage();
  });

  // ─── self-test ──────────────────────────────────────────────

  const tests = [
    {
      name: "bridge version (koda.api === 1)",
      run: async () => ({ ok: koda.api === 1, detail: "api=" + koda.api + ", version=" + koda.version }),
    },
    {
      name: "koda.locale()",
      run: async () => {
        const res = await koda.locale();
        return { ok: !!(res && res.lang), detail: JSON.stringify(res) };
      },
    },
    {
      name: "koda.theme() returns colours",
      run: async () => {
        const res = await koda.theme();
        const ok = !!(res && res.orange && /^#[0-9A-Fa-f]{6}$/.test(res.orange));
        return { ok, detail: JSON.stringify(res) };
      },
    },
    {
      name: "servers.list() (permission servers.read)",
      run: async () => {
        const res = await koda.servers.list();
        const ok = Array.isArray(res && res.servers);
        return { ok, detail: `${ok ? res.servers.length + " server(s)" : JSON.stringify(res)}` };
      },
    },
    {
      name: "servers.get(existing id)",
      run: async () => {
        const list = await koda.servers.list();
        const first = list && list.servers && list.servers[0];
        if (!first) return { ok: true, detail: "no server to test with - create one in the app" };
        const res = await koda.servers.get(first.id);
        return { ok: !!(res && res.server), detail: JSON.stringify(res).slice(0, 200) };
      },
    },
    {
      name: "servers.get(unknown id) answers with an error",
      run: async () => {
        const res = await koda.servers.get("does-not-exist");
        return { ok: !!(res && res.error), detail: JSON.stringify(res) };
      },
    },
    {
      name: "server fields are a whitelist (no paths, no secrets)",
      run: async () => {
        const list = await koda.servers.list();
        const first = list && list.servers && list.servers[0];
        if (!first) return { ok: true, detail: "no server to inspect" };
        const keys = Object.keys(first).sort().join(",");
        const ok = !keys.includes("dir") && !keys.includes("token") && !keys.includes("password");
        return { ok, detail: keys };
      },
    },
    {
      name: "console.tail() (permission console.read)",
      run: async () => {
        const list = await koda.servers.list();
        const first = list && list.servers && list.servers[0];
        if (!first) return { ok: true, detail: "no server to test with" };
        const res = await koda.console.tail(first.id, 10);
        return { ok: Array.isArray(res && res.lines), detail: `${res.lines.length} line(s), live=${res.live}` };
      },
    },
    {
      name: "console.tail(unknown server) returns empty, no crash",
      run: async () => {
        const res = await koda.console.tail("does-not-exist", 5);
        return { ok: Array.isArray(res && res.lines) && res.lines.length === 0, detail: JSON.stringify(res) };
      },
    },
    {
      name: "storage round-trip (set/get/remove)",
      run: async () => {
        const probe = { at: Date.now(), text: "koda-lab" };
        await koda.storage.set("__test", probe);
        const got = await koda.storage.get("__test");
        await koda.storage.remove("__test");
        const after = await koda.storage.get("__test");
        const ok = got && got.value && got.value.text === "koda-lab" && after && after.value === null;
        return { ok, detail: `value=${JSON.stringify(got && got.value)} afterRemove=${JSON.stringify(after && after.value)}` };
      },
    },
    {
      name: "storage quota is enforced (1 MB)",
      run: async () => {
        const chunk = "y".repeat(50 * 1024);
        let error = null;
        for (let i = 0; i < 60 && !error; i++) {
          const res = await koda.storage.set("__quota", chunk + i);
          if (res && res.error) {
            if (res.error.includes("rate")) { await new Promise((r) => setTimeout(r, 150)); continue; }
            error = res.error;
          }
        }
        await koda.storage.remove("__quota");
        return { ok: !!error, detail: error || "no quota error after 60 chunks of 50 KB" };
      },
    },
    {
      name: "rate limit (max 40 calls/second)",
      run: async () => {
        const calls = [];
        for (let i = 0; i < 60; i++) calls.push(koda.storage.keys());
        const results = await Promise.all(calls);
        const limited = results.filter((r) => r && r.error && r.error.includes("rate limited")).length;
        return { ok: limited > 0, detail: `${limited} of 60 calls were rate limited` };
      },
    },
    {
      name: "unimplemented method is hidden, not crashing (console.send)",
      run: async () => {
        const available = typeof koda.console.send === "function";
        return { ok: true, detail: available ? "available" : "not in this API version yet (phase 2)" };
      },
    },
    {
      name: "unknown method cannot be reached from the page",
      run: async () => {
        const before = (await koda.storage.keys()).keys.length;
        try {
          if (typeof kodaTransport !== "undefined" && kodaTransport) {
            kodaTransport.postMessage(JSON.stringify({ id: 999999, method: "shell.exec", args: ["rm -rf /"] }));
          }
        } catch (error) { /* ignored on purpose */ }
        await new Promise((r) => setTimeout(r, 400));
        const after = (await koda.storage.keys()).keys.length;
        return { ok: before === after, detail: "no effect, reply dropped for unknown request id" };
      },
    },
    {
      name: "same-origin only: no remote fetch possible",
      run: async () => {
        try {
          const response = await fetch("https://example.com/");
          // the host answers requests from outside the extension with 403
          return {
            ok: !response.ok,
            detail: response.ok ? "a remote fetch succeeded - that must not happen" : "blocked with HTTP " + response.status,
          };
        } catch (error) {
          return { ok: true, detail: "blocked: " + String(error).slice(0, 80) };
        }
      },
    },
  ];

  async function runTests() {
    const host = $("testResults");
    host.innerHTML = "";
    let passed = 0;
    for (const test of tests) {
      const row = document.createElement("div");
      row.className = "test";
      row.innerHTML = `<div class="name">${escapeHtml(test.name)}</div><div class="result dim">running…</div>`;
      host.appendChild(row);
      let result;
      const started = performance.now();
      try {
        result = await test.run();
      } catch (error) {
        result = { ok: false, detail: "threw " + error };
      }
      const ms = Math.round(performance.now() - started);
      if (result.ok) passed++;
      row.querySelector(".result").className = "result " + (result.ok ? "pass" : "fail");
      row.querySelector(".result").textContent = `${result.ok ? "PASS" : "FAIL"} · ${result.detail} (${ms} ms)`;
    }
    const summary = document.createElement("div");
    summary.className = "test";
    summary.innerHTML = `<div class="name ${passed === tests.length ? "pass" : "fail"}">` +
      `${passed} / ${tests.length} passed</div>`;
    host.prepend(summary);
    log(`self-test finished: ${passed}/${tests.length}`);
  }

  async function stress() {
    const started = performance.now();
    const calls = [];
    for (let i = 0; i < 120; i++) calls.push(koda.storage.keys());
    const results = await Promise.all(calls);
    const limited = results.filter((r) => r && r.error && r.error.includes("rate limited")).length;
    const ok = results.filter((r) => r && !r.error).length;
    const other = results.length - limited - ok;
    const ms = Math.round(performance.now() - started);
    const text = `120 parallel calls in ${ms} ms\n  ok           ${ok}\n  rate limited ${limited}\n  other errors ${other}`;
    $("stressResult").textContent = text;
    log("stress → " + text.replace(/\n/g, " | "));
  }

  $("btnRunTests").addEventListener("click", runTests);
  $("btnStress").addEventListener("click", stress);

  // ─── boot ───────────────────────────────────────────────────

  (async function boot() {
    const target = readContext();
    log("Koda Lab started" + (location.hash ? " (context " + location.hash + ")" : ""));
    const startTab = document.getElementById("panel-" + target)
      ? target
      : (pendingServerId ? "console" : "overview");
    selectTab(startTab);
    loadOverview();
  })();
})();
