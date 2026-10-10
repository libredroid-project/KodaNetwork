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
package eu.kodanetwork.mchost.extension;

import java.util.Locale;

/**
 * the JavaScript bootstrap that defines window.koda inside an extension page.
 *
 * the host injects it into every HTML file it serves, so an extension never has
 * to load it and window.koda is there before any of its own scripts run. two
 * transports exist: the WebMessageListener ("kodaTransport", modern WebViews)
 * and the addJavascriptInterface fallback ("kodaBridgeJava", old WebViews).
 * both go through the same promise API.
 */
public final class ExtensionShim {

    private ExtensionShim() {}

    public static final String JS =
            "(function () {\n" +
            "  if (window.koda && window.koda.api) return;\n" +
            "  var pending = {}, next = 0;\n" +
            "  function reply(id, result) {\n" +
            "    var r = pending[id]; if (!r) return; delete pending[id];\n" +
            "    r(result === undefined ? null : result);\n" +
            "  }\n" +
            "  window.__kodaReply = reply;\n" +
            "  if (typeof kodaTransport !== 'undefined' && kodaTransport && !kodaTransport.onmessage) {\n" +
            "    kodaTransport.onmessage = function (e) {\n" +
            "      try { var d = JSON.parse(e.data); reply(d.id, JSON.parse(d.result)); } catch (err) {}\n" +
            "    };\n" +
            "  }\n" +
            "  function send(method, args) {\n" +
            "    return new Promise(function (resolve) {\n" +
            "      var id = ++next;\n" +
            "      pending[id] = resolve;\n" +
            "      var payload = JSON.stringify({ id: id, method: method, args: args || [] });\n" +
            "      try {\n" +
            "        if (typeof kodaTransport !== 'undefined' && kodaTransport && kodaTransport.postMessage) {\n" +
            "          kodaTransport.postMessage(payload);\n" +
            "        } else if (typeof kodaBridgeJava !== 'undefined' && kodaBridgeJava) {\n" +
            "          kodaBridgeJava.call(payload);\n" +
            "        } else { delete pending[id]; resolve({ error: 'no bridge available' }); }\n" +
            "      } catch (err) { delete pending[id]; resolve({ error: String(err) }); }\n" +
            "    });\n" +
            "  }\n" +
            "  window.koda = {\n" +
            "    api: 1,\n" +
            "    version: '1.0',\n" +
            "    locale: function () { return send('locale', []); },\n" +
            "    theme: function () { return send('theme', []); },\n" +
            "    ui: {\n" +
            "      toast: function (msg) { return send('ui.toast', [String(msg)]); },\n" +
            "      confirm: function (title, msg) { return send('ui.confirm', [String(title), String(msg)]); }\n" +
            "    },\n" +
            "    storage: {\n" +
            "      get: function (key) { return send('storage.get', [String(key)]); },\n" +
            "      set: function (key, value) { return send('storage.set', [String(key), value]); },\n" +
            "      remove: function (key) { return send('storage.remove', [String(key)]); },\n" +
            "      keys: function () { return send('storage.keys', []); }\n" +
            "    },\n" +
            "    servers: {\n" +
            "      list: function () { return send('servers.list', []); },\n" +
            "      get: function (id) { return send('servers.get', [String(id)]); }\n" +
            "    },\n" +
            "    console: {\n" +
            "      tail: function (id, lines) { return send('console.tail', [String(id), Number(lines) || 200]); }\n" +
            "    }\n" +
            "  };\n" +
            "})();";

    /** puts the shim in as the first script of the document. */
    public static String inject(String html) {
        String script = "<script>" + JS + "</script>";
        String lower = html.toLowerCase(Locale.ROOT);
        int head = lower.indexOf("<head");
        if (head >= 0) {
            int end = html.indexOf('>', head);
            if (end > 0) return html.substring(0, end + 1) + script + html.substring(end + 1);
        }
        if (lower.startsWith("<!doctype")) {
            int end = html.indexOf('>');
            if (end > 0) return html.substring(0, end + 1) + script + html.substring(end + 1);
        }
        return script + html;
    }
}
