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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * key/JSON-value store of one extension, that is koda.storage in the bridge.
 * sits in the extension's data directory and is capped, so an extension can
 * never stuff the device full with its own data.
 */
public class ExtensionStorage {

    public static final int MAX_BYTES = 1024 * 1024;

    public static final class QuotaException extends Exception {
        public QuotaException(String message) { super(message); }
    }

    private final File file;

    public ExtensionStorage(File dataDir) {
        if (!dataDir.isDirectory()) dataDir.mkdirs();
        this.file = new File(dataDir, "storage.json");
    }

    /** the stored value (JSONObject/JSONArray/String/Number/Boolean), null when unset. */
    public synchronized Object get(String key) {
        JSONObject map = load();
        if (!map.has(key) || map.isNull(key)) return null;
        return map.opt(key);
    }

    public synchronized void set(String key, Object value) throws QuotaException {
        JSONObject map = load();
        try {
            if (value == null) map.remove(key);
            else map.put(key, value);
        } catch (Exception e) {
            throw new QuotaException("value is not valid JSON");
        }
        String json = map.toString();
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new QuotaException("storage limit reached");
        }
        write(json);
    }

    public synchronized void remove(String key) {
        JSONObject map = load();
        map.remove(key);
        write(map.toString());
    }

    public synchronized JSONArray keys() {
        JSONArray arr = new JSONArray();
        JSONObject map = load();
        java.util.Iterator<String> it = map.keys();
        while (it.hasNext()) arr.put(it.next());
        return arr;
    }

    private JSONObject load() {
        try {
            if (!file.isFile()) return new JSONObject();
            byte[] raw = Files.readAllBytes(file.toPath());
            return new JSONObject(new String(raw, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private void write(String json) {
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }
}
