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
package eu.kodanetwork.mchost.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import java.util.Locale;

public class LocaleHelper {
    public static Context onAttach(Context context) {
        SharedPreferences prefs = eu.kodanetwork.mchost.App.getPrefs(context);
        String defaultLang = Locale.getDefault().getLanguage();
        String lang = prefs.getString("language", defaultLang);
        return setLocale(context, lang);
    }

    private static Context setLocale(Context context, String language) {
        Locale locale = new Locale(language);
        Locale.setDefault(locale);

        Resources resources = context.getResources();
        Configuration configuration = new Configuration(resources.getConfiguration());
        configuration.setLocale(locale);

        return context.createConfigurationContext(configuration);
    }
    
    // fallback for strings built in code, the XML resources do not cover those
    public static String t(Context c, String en, String de) {
        SharedPreferences prefs = eu.kodanetwork.mchost.App.getPrefs(c);
        String defaultLang = Locale.getDefault().getLanguage();
        String lang = prefs.getString("language", defaultLang);
        if ("de".equals(lang)) return de;
        return en;
    }
}
