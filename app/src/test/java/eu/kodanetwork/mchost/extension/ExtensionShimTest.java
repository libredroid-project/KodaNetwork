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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The window.koda shim must always end up inside the document. */
public class ExtensionShimTest {

    @Test
    public void injectsAfterHead() {
        String html = "<!DOCTYPE html><html><head><title>x</title></head><body></body></html>";
        String out = ExtensionShim.inject(html);
        assertTrue(out.contains("window.koda"));
        assertTrue(out.contains("__kodaReply"));
        int head = out.indexOf("<head>");
        int script = out.indexOf("<script>");
        assertTrue("shim must sit inside <head>", script > head && script < out.indexOf("<title>"));
    }

    @Test
    public void injectsAfterDoctypeWhenHeadIsMissing() {
        String html = "<!DOCTYPE html><body>hi</body>";
        String out = ExtensionShim.inject(html);
        assertTrue(out.startsWith("<!DOCTYPE html>"));
        assertTrue(out.indexOf("<script>") < out.indexOf("<body>"));
    }

    @Test
    public void fallsBackToPrefix() {
        String out = ExtensionShim.inject("<p>hi</p>");
        assertTrue(out.startsWith("<script>"));
        assertTrue(out.contains("window.koda"));
    }

    @Test
    public void supportsBothTransports() {
        String js = ExtensionShim.JS;
        assertTrue(js.contains("kodaTransport"));
        assertTrue(js.contains("kodaBridgeJava"));
        assertTrue(js.contains("koda.api") || js.contains("api: 1"));
        assertFalse(js.contains("eval("));
    }
}
