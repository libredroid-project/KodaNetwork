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
// Copyright (c) 2026 KodaHosting
import leoProfanity from "https://esm.sh/leo-profanity@1.7.0";

// load the english dictionary, then add the usual german/international words by hand
leoProfanity.loadDictionary('en');
leoProfanity.add(['hurensohn', 'wichser', 'fotze', 'schlampe', 'missgeburt', 'bastard', 'arschloch', 'fick', 'hitler', 'nazi']);

/**
 * checks a hostname (subdomain), returns an error code when it is bad, null when it is fine.
 */
export function validateHost(host: string): string | null {
  if (!host) return "missing_parameters";
  
  if (host.length < 3 || host.length > 63) {
    return "length_invalid";
  }
  
  const hostRegex = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;
  if (!hostRegex.test(host)) {
    return "invalid_characters";
  }
  
  // scan the host for bad words
  if (leoProfanity.check(host)) {
    return "profanity_detected";
  }
  
  return null;
}
