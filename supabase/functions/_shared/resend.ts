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
// Koda mail over Resend. one template, one sender, used by the data request flow
// and the delete account confirmation. secrets stay in the function environment.

export const MAIL_FROM = "KodaHosting <no-reply@kodaserv.eu>";
// used when kodaserv.eu is not verified in Resend (yet). the reset mail already sends
// from this domain, so it is the safe fallback.
export const MAIL_FROM_FALLBACK = "KodaHosting <noreply@kodanetwork.eu>";

export interface KodaMail {
  to: string;
  subject: string;
  heading: string;
  paragraphs: string[];
  actionLabel?: string;
  actionUrl?: string;
  footnote?: string;
}

/**
 * only https and never a local, loopback, private or reserved address. the action
 * links are built by us, but a bad value must never be able to point the mail at
 * something internal.
 */
export function assertPublicHttps(raw: string): string {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new Error("invalid action url");
  }
  if (url.protocol !== "https:") throw new Error("action url must be https");

  const host = url.hostname.toLowerCase().replace(/^\[|\]$/g, "");
  const blocked =
    host === "localhost" ||
    host.endsWith(".localhost") ||
    host === "0.0.0.0" ||
    host === "::1" ||
    host === "::" ||
    /^127\./.test(host) ||
    /^10\./.test(host) ||
    /^192\.168\./.test(host) ||
    /^169\.254\./.test(host) ||
    /^172\.(1[6-9]|2\d|3[01])\./.test(host) ||
    /^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\./.test(host);
  if (blocked) throw new Error("action url points to a private address");
  return url.toString();
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

export function renderKodaMail(mail: KodaMail): string {
  const paragraphs = mail.paragraphs
    .map((p) => `<p style="color:#D8D5D2;font-size:15px;line-height:1.6;margin:0 0 14px;">${escapeHtml(p)}</p>`)
    .join("");
  const action = mail.actionUrl && mail.actionLabel
    ? `<a href="${escapeHtml(assertPublicHttps(mail.actionUrl))}"
          style="display:inline-block;background:#FF6B00;color:#1A0F00;font-weight:700;
                 padding:14px 26px;border-radius:12px;text-decoration:none;margin:10px 0 18px;">
          ${escapeHtml(mail.actionLabel)}</a>`
    : "";
  const footnote = mail.footnote
    ? `<p style="color:#8A8A9A;font-size:12px;line-height:1.5;margin:24px 0 0;">${escapeHtml(mail.footnote)}</p>`
    : "";

  return `<!doctype html>
<html><body style="margin:0;background:#141010;">
  <div style="max-width:560px;margin:0 auto;padding:36px 28px;background:#241C18;
              font-family:-apple-system,Segoe UI,Roboto,sans-serif;">
    <div style="font-size:12px;letter-spacing:3px;margin-bottom:26px;">
      <span style="color:#555555;">P.R.</span><span style="color:#AAAAAA;">A.E.T.</span><span style="color:#FFFFFF;">O.R.</span>
    </div>
    <h1 style="color:#F5F5F7;font-size:22px;margin:0 0 18px;">${escapeHtml(mail.heading)}</h1>
    ${paragraphs}
    ${action}
    ${footnote}
    <p style="color:#555555;font-size:11px;margin:28px 0 0;">
      KodaHosting · host.kodanetwork.eu · Diese Mail wurde automatisch erzeugt.
    </p>
  </div>
</body></html>`;
}

export async function sendKodaMail(mail: KodaMail): Promise<{ ok: boolean; status: number; error?: string; from?: string }> {
  const key = Deno.env.get("RESEND_API_KEY");
  if (!key) return { ok: false, status: 0, error: "RESEND_API_KEY missing" };

  const primary = Deno.env.get("KODA_MAIL_FROM") || MAIL_FROM;
  const senders = primary === MAIL_FROM_FALLBACK ? [primary] : [primary, MAIL_FROM_FALLBACK];
  let last = { ok: false, status: 0, error: "not sent" as string | undefined, from: undefined as string | undefined };

  for (const from of senders) {
    const response = await fetch("https://api.resend.com/emails", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${key}`,
      },
      body: JSON.stringify({
        from,
        to: [mail.to],
        subject: mail.subject,
        html: renderKodaMail(mail),
      }),
    });

    if (response.ok) {
      return { ok: true, status: response.status, from };
    }

    const text = (await response.text()).slice(0, 300);
    last = { ok: false, status: response.status, error: text, from };
    // a not-verified sender is the one case where the next sender can help
    if (!(response.status === 403 && text.includes("not verified"))) break;
  }

  return last;
}
