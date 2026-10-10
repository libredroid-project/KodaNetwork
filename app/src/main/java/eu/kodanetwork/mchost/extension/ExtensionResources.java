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

import android.content.res.Resources;

import java.util.Locale;

/**
 * string lookups go through the active extension language pack first and fall
 * back to the app language. installed by
 * {@link ExtensionLanguagePacks#wrap(android.content.Context)}.
 */
public class ExtensionResources extends Resources {

    public ExtensionResources(Resources base) {
        super(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration());
    }

    @Override
    public String getString(int id) throws NotFoundException {
        String translated = translate(id);
        return translated != null ? translated : super.getString(id);
    }

    @Override
    public CharSequence getText(int id) throws NotFoundException {
        String translated = translate(id);
        return translated != null ? translated : super.getText(id);
    }

    @Override
    public String getString(int id, Object... formatArgs) throws NotFoundException {
        String translated = translate(id);
        if (translated == null) return super.getString(id, formatArgs);
        try {
            Locale locale = ExtensionLanguagePacks.formatLocale(this);
            return String.format(locale, translated, formatArgs);
        } catch (Throwable t) {
            return translated;
        }
    }

    private String translate(int id) {
        try {
            if (!ExtensionLanguagePacks.isActive()) return null;
            return ExtensionLanguagePacks.lookup(getResourceEntryName(id));
        } catch (Throwable t) {
            return null;
        }
    }
}
