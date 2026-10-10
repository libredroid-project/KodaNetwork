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
// forgot-password mail. two things were wrong: an unknown address answered with
// "User with this email not found" (a free account-enumeration oracle) and there
// was no throttle, so anyone could pump reset mails through the Resend account.
// every outcome now answers the same generic body, and one address can only get
// one mail per cooldown window.
import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2.38.4'

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

const COOLDOWN_SECONDS = 300

function genericOk() {
  return new Response(
    JSON.stringify({ ok: true, message: 'If an account exists for this address, a reset mail is on its way.' }),
    { headers: { ...corsHeaders, 'Content-Type': 'application/json' }, status: 200 },
  )
}

serve(async (req) => {
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders })
  }

  try {
    const { email } = await req.json()
    if (!email || typeof email !== 'string') {
      return genericOk()
    }
    const clean = email.trim().toLowerCase()
    if (!clean.includes('@')) {
      return genericOk()
    }

    const supabaseUrl = Deno.env.get('SUPABASE_URL')!
    const supabaseServiceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!

    // supabase admin client, needed to mint the recovery link
    const supabaseAdmin = createClient(supabaseUrl, supabaseServiceKey)

    // one mail per address per window, the table is service-role only
    const { data: last } = await supabaseAdmin
      .from('password_reset_requests')
      .select('last_sent_at')
      .eq('email', clean)
      .maybeSingle()
    const lastSent = last?.last_sent_at ? new Date(last.last_sent_at).getTime() : 0
    if (lastSent > 0 && Date.now() - lastSent < COOLDOWN_SECONDS * 1000) {
      return genericOk()
    }
    await supabaseAdmin
      .from('password_reset_requests')
      .upsert({ email: clean, last_sent_at: new Date().toISOString() })

    // generate the recovery link. an unknown address fails HERE and nobody may
    // learn that from the answer, so the error is swallowed and the same generic
    // body goes back
    const { data: linkData, error: linkError } = await supabaseAdmin.auth.admin.generateLink({
      type: 'recovery',
      email: clean,
    })

    if (!linkError && linkData) {
      // swap the default localhost:3000 url for the custom Koda one
      let actionLink = linkData.properties.action_link;
      actionLink = actionLink.replace('http://localhost:3000', 'https://host.kodanetwork.eu/reset');

      // fallback for when the redirect is already customised or localhost is gone
      if (!actionLink.includes('host.kodanetwork.eu')) {
        const urlObj = new URL(actionLink);
        actionLink = `https://host.kodanetwork.eu/reset${urlObj.hash}`;
      }

      // now send it through Resend
      const resendApiKey = Deno.env.get('RESEND_API_KEY')!
      await fetch('https://api.resend.com/emails', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${resendApiKey}`
        },
        body: JSON.stringify({
          from: 'KodaNetwork <noreply@kodanetwork.eu>',
          to: clean,
          subject: 'Password Reset - KodaNetwork',
          html: `
          <div style="background-color: #0d0d12; color: white; font-family: sans-serif; padding: 40px; text-align: center;">
            <h1 style="color: #FF6B00; letter-spacing: 2px;">P.R.A.E.T.O.R.</h1>
            <p style="color: #8A8A9A; margin-bottom: 30px;">SECURITY OVERRIDE PROTOCOL INITIATED</p>
            <p>You have requested a password reset for your KodaHosting account.</p>
            <a href="${actionLink}" style="display: inline-block; background-color: #FF6B00; color: white; padding: 15px 30px; text-decoration: none; font-weight: bold; border-radius: 5px; margin: 20px 0;">RESET PASSWORD</a>
            <p style="color: #555; font-size: 12px; margin-top: 40px;">If you did not request this, you can safely ignore this email.</p>
          </div>
        `
        })
      })
    }

    return genericOk()
  } catch (error) {
    // never leak details here either
    return genericOk()
  }
})
