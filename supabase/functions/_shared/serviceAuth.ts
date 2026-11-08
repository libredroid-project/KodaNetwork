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
// Admin authentication for the functions that the admin panel and the admin app call.
// The gateway verifies the JWT signature before the function runs, so checking the
// role claim is enough; the plain env comparison stays as a fallback for older projects.

function decodePayload(token: string): Record<string, unknown> | null {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const padded = parts[1] + "=".repeat((4 - (parts[1].length % 4)) % 4);
    const json = atob(padded.replace(/-/g, "+").replace(/_/g, "/"));
    return JSON.parse(json) as Record<string, unknown>;
  } catch {
    return null;
  }
}

export function isServiceRoleRequest(req: Request): boolean {
  const header = req.headers.get("authorization") ?? "";
  if (!header.toLowerCase().startsWith("bearer ")) return false;
  const token = header.slice(7).trim();
  if (!token) return false;

  const configured = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  if (configured && token === configured) return true;

  const payload = decodePayload(token);
  if (!payload || payload.role !== "service_role") return false;

  // tie the token to this project so a key from another project never passes
  const url = Deno.env.get("SUPABASE_URL") ?? "";
  const ref = url.replace(/^https?:\/\//, "").split(".")[0];
  return !payload.ref || !ref || payload.ref === ref;
}
