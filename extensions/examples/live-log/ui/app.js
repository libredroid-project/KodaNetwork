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
// live log: shows the console of the picked server and refreshes every two
// seconds. demonstrates the whole read-only bridge API of version 1.

(async function () {
  const serverSelect = document.getElementById("server");
  const logView = document.getElementById("log");

  // match the app's colours (the bundled koda-ui.css already carries the same
  // values, this only proves how to read them at runtime)
  const theme = await koda.theme();
  document.documentElement.style.setProperty("--koda-orange", theme.orange || "#FF6B00");
  document.documentElement.style.setProperty("--koda-bg", theme.bg || "#080808");

  const { servers, error } = await koda.servers.list();
  if (error) {
    logView.textContent = error;
    return;
  }
  if (!servers || servers.length === 0) {
    logView.textContent = "No servers yet. Create one in the app first.";
    return;
  }

  for (const server of servers) {
    const option = document.createElement("option");
    option.value = server.id;
    option.textContent = server.name + (server.state === "ONLINE" ? "  (online)" : "");
    serverSelect.appendChild(option);
  }

  const saved = await koda.storage.get("lastServer");
  if (saved && saved.value && servers.some((s) => s.id === saved.value)) {
    serverSelect.value = saved.value;
  }

  let timer = null;

  async function tick() {
    const id = serverSelect.value;
    if (!id) return;
    const res = await koda.console.tail(id, 300);
    if (res.error) {
      logView.textContent = res.error;
      return;
    }
    const lines = res.lines || [];
    logView.textContent = lines.length ? lines.join("\n") : "Waiting for console output...";
    logView.scrollTop = logView.scrollHeight;
    await koda.storage.set("lastServer", id);
  }

  serverSelect.addEventListener("change", tick);
  await tick();
  timer = setInterval(tick, 2000);
  window.addEventListener("beforeunload", function () {
    if (timer) clearInterval(timer);
  });
})();
