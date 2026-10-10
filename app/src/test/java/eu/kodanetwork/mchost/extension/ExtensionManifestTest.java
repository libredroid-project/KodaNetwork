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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** Validation rules of manifest.json inside a .kodaext package. */
public class ExtensionManifestTest {

    private static final int APP_VERSION = 8515;

    private static final String VALID = "{\n"
            + "  \"schema\": 1,\n"
            + "  \"id\": \"eu.example.live-log\",\n"
            + "  \"name\": \"Live Log\",\n"
            + "  \"version\": \"1.0.0\",\n"
            + "  \"versionCode\": 1,\n"
            + "  \"author\": \"Example\",\n"
            + "  \"description\": \"Shows the console.\",\n"
            + "  \"license\": \"GPL-3.0-or-later\",\n"
            + "  \"source_url\": \"https://github.com/example/live-log\",\n"
            + "  \"minAppVersion\": 8515,\n"
            + "  \"permissions\": [\"servers.read\", \"console.read\", \"storage\", \"http:discord.com\"],\n"
            + "  \"ui\": { \"entry\": \"ui/index.html\", \"title\": \"Live Log\" }\n"
            + "}";

    private static ExtensionManifest parse(String json) throws Exception {
        return ExtensionManifest.parse(json, APP_VERSION);
    }

    /** VALID plus one more top level field. */
    private static String withExtra(String field) {
        int lastBrace = VALID.lastIndexOf("}");
        return VALID.substring(0, lastBrace) + ",\n  " + field + "\n}";
    }

    private static void assertRejected(String json) {
        try {
            ExtensionManifest.parse(json, APP_VERSION);
            fail("expected InvalidManifestException");
        } catch (ExtensionManifest.InvalidManifestException expected) {
            // fine
        }
    }

    @Test
    public void parsesValidManifest() throws Exception {
        ExtensionManifest m = parse(VALID);
        assertEquals("eu.example.live-log", m.id);
        assertEquals("Live Log", m.name);
        assertEquals("1.0.0", m.version);
        assertEquals(1, m.versionCode);
        assertEquals("GPL-3.0-or-later", m.license);
        assertEquals("https://github.com/example/live-log", m.sourceUrl);
        assertEquals("ui/index.html", m.uiEntry);
        assertEquals("Live Log", m.uiTitle);
        assertTrue(m.hasUi());
        assertFalse(m.hasAutomations());
        assertNull(m.automationsFile);
        assertTrue(m.hasPermission("console.read"));
        assertTrue(m.hasPermission("http:discord.com"));
        assertFalse(m.hasPermission("console.send"));
        assertEquals(4, m.permissions.size());
    }

    @Test
    public void defaultsUiTitleToName() throws Exception {
        ExtensionManifest m = parse(VALID.replace(", \"title\": \"Live Log\"", ""));
        assertEquals("Live Log", m.uiTitle);
    }

    @Test
    public void acceptsAutomationsFile() throws Exception {
        ExtensionManifest m = parse(withExtra("\"automations\": \"automations.json\""));
        assertTrue(m.hasAutomations());
        assertEquals("automations.json", m.automationsFile);
    }

    @Test
    public void rejectsMissingLicense() {
        assertRejected(VALID.replace("\"license\": \"GPL-3.0-or-later\",", ""));
    }

    @Test
    public void rejectsUnsupportedSchema() {
        assertRejected(VALID.replace("\"schema\": 1", "\"schema\": 2"));
    }

    @Test
    public void rejectsInvalidId() {
        assertRejected(VALID.replace("eu.example.live-log", "Live Log"));
        assertRejected(VALID.replace("eu.example.live-log", "nodots"));
    }

    @Test
    public void rejectsInvalidVersionAndVersionCode() {
        assertRejected(VALID.replace("\"version\": \"1.0.0\"", "\"version\": \"v1\""));
        assertRejected(VALID.replace("\"versionCode\": 1", "\"versionCode\": 0"));
    }

    @Test
    public void rejectsNewerMinAppVersion() {
        assertRejected(VALID.replace("\"minAppVersion\": 8515", "\"minAppVersion\": 9999"));
    }

    @Test
    public void rejectsUnknownPermission() {
        assertRejected(VALID.replace("\"storage\"", "\"root.access\""));
    }

    @Test
    public void rejectsInvalidHttpPermission() {
        assertRejected(VALID.replace("http:discord.com", "http:localhost"));
        assertRejected(VALID.replace("http:discord.com", "http:https://example.com"));
    }

    @Test
    public void rejectsPathTraversal() {
        assertRejected(VALID.replace("ui/index.html", "../index.html"));
        assertRejected(VALID.replace("ui/index.html", "/etc/passwd"));
        assertRejected(VALID.replace("ui/index.html", "ui\\index.html"));
    }

    @Test
    public void rejectsAutomationsWithoutJsonSuffix() {
        assertRejected(withExtra("\"automations\": \"automations.txt\""));
    }

    @Test
    public void rejectsBrokenJson() {
        assertRejected("not json");
        assertRejected("[]");
    }

    @Test
    public void normalizesPermissions() throws Exception {
        ExtensionManifest m = parse(VALID.replace(
                "[\"servers.read\", \"console.read\", \"storage\", \"http:discord.com\"]",
                "[\"Storage\", \"HTTP:Discord.com\", \"storage\"]"));
        assertEquals(2, m.permissions.size());
        assertTrue(m.hasPermission("storage"));
        assertTrue(m.hasPermission("http:discord.com"));
    }

    // ─── contributes (language packs, themes, UI tweaks) ─────────

    private static final String CONTRIBUTES = "{\n"
            + "  \"schema\": 1, \"id\": \"eu.example.packs\", \"name\": \"Packs\",\n"
            + "  \"version\": \"1.0.0\", \"versionCode\": 1, \"license\": \"MIT\",\n"
            + "  \"contributes\": {\n"
            + "    \"languages\": [ { \"code\": \"pl\", \"name\": \"Polski\", \"file\": \"lang/pl.json\" } ],\n"
            + "    \"themes\": [ { \"id\": \"ocean\", \"name\": \"Ocean\", \"color\": \"#006874\" } ],\n"
            + "    \"ui\": { \"hide\": [\"btn_github_main\"], \"console_chips\": [\"/tps\", \"time set day\"] }\n"
            + "  }\n"
            + "}";

    @Test
    public void parsesContributions() throws Exception {
        ExtensionManifest m = parse(CONTRIBUTES);
        assertTrue(m.hasContributions());
        assertEquals(1, m.languages.size());
        assertEquals("pl", m.languages.get(0).code);
        assertEquals("Polski", m.languages.get(0).name);
        assertEquals("lang/pl.json", m.languages.get(0).file);
        assertEquals(1, m.themes.size());
        assertEquals("ocean", m.themes.get(0).id);
        assertEquals(0xFF006874, m.themes.get(0).color);
        assertEquals(1, m.uiTweaks.hideViewIds.size());
        assertEquals("btn_github_main", m.uiTweaks.hideViewIds.get(0));
        assertEquals(2, m.uiTweaks.consoleChips.size());
    }

    private static final String WITH_SETTINGS = "{\n"
            + "  \"schema\": 1, \"id\": \"eu.example.settings\", \"name\": \"Settings\",\n"
            + "  \"version\": \"1.0.0\", \"versionCode\": 1, \"license\": \"MIT\",\n"
            + "  \"contributes\": {\n"
            + "    \"settings\": [\n"
            + "      { \"title\": \"My extension\", \"subtitle\": \"What it does\", \"target\": \"overview\" },\n"
            + "      { \"title\": \"Second entry\" }\n"
            + "    ]\n"
            + "  }\n"
            + "}";

    @Test
    public void parsesSettingsEntries() throws Exception {
        ExtensionManifest m = parse(WITH_SETTINGS);
        assertTrue(m.hasContributions());
        assertEquals(2, m.settingsEntries.size());
        assertEquals("My extension", m.settingsEntries.get(0).title);
        assertEquals("What it does", m.settingsEntries.get(0).subtitle);
        assertEquals("overview", m.settingsEntries.get(0).target);
        assertEquals("Second entry", m.settingsEntries.get(1).title);
        assertEquals("", m.settingsEntries.get(1).subtitle);
        assertNull(m.settingsEntries.get(1).target);
    }

    @Test
    public void rejectsBadSettingsEntries() {
        assertRejected(WITH_SETTINGS.replace("\"My extension\"", "\"\""));
        assertRejected(WITH_SETTINGS.replace("\"overview\"", "\"Overview Tab\""));
        assertRejected(WITH_SETTINGS.replace("\"What it does\"", "\"" + "x".repeat(81) + "\""));
        assertRejected(WITH_SETTINGS.replace(
                "{ \"title\": \"Second entry\" }",
                "{ \"title\": \"2\" }, { \"title\": \"3\" }, { \"title\": \"4\" }, { \"title\": \"5\" }"));
    }

    private static final String WITH_FULL_THEME = "{\n"
            + "  \"schema\": 1, \"id\": \"eu.example.theme\", \"name\": \"Theme\",\n"
            + "  \"version\": \"1.0.0\", \"versionCode\": 1, \"license\": \"MIT\",\n"
            + "  \"ui\": { \"entry\": \"ui/index.html\" },\n"
            + "  \"contributes\": {\n"
            + "    \"themes\": [\n"
            + "      { \"id\": \"midnight\", \"name\": \"Midnight\", \"color\": \"#006874\",\n"
            + "        \"colors\": { \"primary\": \"#00D9FF\", \"surface\": \"#0A0A12\", \"card\": \"#15151F\",\n"
            + "                    \"text\": \"#F2F2F2\", \"text_dim\": \"#8C8C9C\", \"online\": \"#3FA34D\", \"error\": \"#FF4455\" } }\n"
            + "    ],\n"
            + "    \"server_tabs\": [\n"
            + "      { \"id\": \"lab-console\", \"title\": \"Lab\", \"target\": \"console\" },\n"
            + "      { \"id\": \"lab-page\", \"title\": \"Lab Page\", \"entry\": \"ui/index.html\" }\n"
            + "    ]\n"
            + "  }\n"
            + "}";

    @Test
    public void parsesFullThemeAndServerTabs() throws Exception {
        ExtensionManifest m = parse(WITH_FULL_THEME);
        assertEquals(1, m.themes.size());
        ExtensionManifest.ThemePack theme = m.themes.get(0);
        assertEquals("midnight", theme.id);
        assertEquals(0xFF006874, theme.color);
        assertEquals(7, theme.colors.size());
        assertEquals(Integer.valueOf(0xFF00D9FF), theme.colors.get("primary"));
        assertEquals(Integer.valueOf(0xFF0A0A12), theme.colors.get("surface"));

        assertEquals(2, m.serverTabs.size());
        assertEquals("lab-console", m.serverTabs.get(0).id);
        assertEquals("Lab", m.serverTabs.get(0).title);
        assertEquals("console", m.serverTabs.get(0).target);
        assertNull(m.serverTabs.get(0).entry);
        assertEquals("ui/index.html", m.serverTabs.get(1).entry);
        assertNull(m.serverTabs.get(1).target);
    }

    @Test
    public void rejectsBadThemeColors() {
        // unknown role name and a malformed hex value
        assertRejected(WITH_FULL_THEME.replace("\"primary\": \"#00D9FF\"", "\"border\": \"#00D9FF\""));
        assertRejected(WITH_FULL_THEME.replace("\"#0A0A12\"", "\"#0A0A1\""));
    }

    @Test
    public void rejectsTooManyServerTabs() {
        assertRejected(WITH_FULL_THEME.replace("{ \"id\": \"lab-console\", \"title\": \"Lab\", \"target\": \"console\" },",
                "{ \"id\": \"t1\", \"title\": \"T1\" }, { \"id\": \"t2\", \"title\": \"T2\" },"
                        + " { \"id\": \"t3\", \"title\": \"T3\" }, { \"id\": \"t4\", \"title\": \"T4\" },"
                        + " { \"id\": \"t5\", \"title\": \"T5\" },"));
    }

    @Test
    public void rejectsBadServerTabs() {
        assertRejected(WITH_FULL_THEME.replace("\"lab-console\"", "\"Lab Console\""));
        assertRejected(WITH_FULL_THEME.replace("\"Lab Page\"", "\"This title is far too long for a single tab label\""));
        assertRejected(WITH_FULL_THEME.replace("\"ui/index.html\"", "\"ui/index.txt\""));
        assertRejected(WITH_FULL_THEME.replace("\"target\": \"console\"", "\"target\": \"my target\""));
        assertRejected(WITH_FULL_THEME.replace("\"lab-page\"", "\"lab-console\""));
    }

    @Test
    public void withoutContributionsNothingIsDeclared() throws Exception {
        ExtensionManifest m = parse(VALID);
        assertFalse(m.hasContributions());
        assertTrue(m.languages.isEmpty());
        assertTrue(m.themes.isEmpty());
    }

    @Test
    public void rejectsBadContributions() {
        // language code must be a real code, the file must be .json
        assertRejected(CONTRIBUTES.replace("\"pl\"", "\"polish\""));
        assertRejected(CONTRIBUTES.replace("lang/pl.json", "lang/pl.txt"));
        // theme colour must be a hex value
        assertRejected(CONTRIBUTES.replace("#006874", "teal"));
        assertRejected(CONTRIBUTES.replace("\"ocean\"", "\"Ocean Theme\""));
        // view ids are lowercase resource names
        assertRejected(CONTRIBUTES.replace("btn_github_main", "btn-GitHub"));
        assertRejected(CONTRIBUTES.replace("btn_github_main", "Btn_github"));
        // empty console chip
        assertRejected(CONTRIBUTES.replace("\"/tps\"", "\"\""));
    }

    @Test
    public void rejectsDuplicatePacks() {
        assertRejected(CONTRIBUTES.replace(
                "[ { \"code\": \"pl\", \"name\": \"Polski\", \"file\": \"lang/pl.json\" } ]",
                "[ { \"code\": \"pl\", \"name\": \"Polski\", \"file\": \"lang/pl.json\" },"
                        + " { \"code\": \"PL\", \"name\": \"Polski 2\", \"file\": \"lang/pl2.json\" } ]"));
        assertRejected(CONTRIBUTES.replace(
                "[ { \"id\": \"ocean\", \"name\": \"Ocean\", \"color\": \"#006874\" } ]",
                "[ { \"id\": \"ocean\", \"name\": \"Ocean\", \"color\": \"#006874\" },"
                        + " { \"id\": \"ocean\", \"name\": \"Ocean 2\", \"color\": \"#006875\" } ]"));
    }

    @Test
    public void rejectsTooManyConsoleChips() {
        StringBuilder chips = new StringBuilder();
        for (int i = 0; i < 17; i++) {
            if (i > 0) chips.append(", ");
            chips.append("\"/cmd").append(i).append("\"");
        }
        assertRejected(CONTRIBUTES.replace("\"/tps\", \"time set day\"", chips.toString()));
    }
}
