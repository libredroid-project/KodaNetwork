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
import android.util.Base64;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * end-to-end encryption for the personal (mental support) chat.
 *
 * every device holds an ECDH P-256 key pair and the private key never leaves it. both
 * sides derive the same AES-256 key from their own private key and the peer's public
 * key (ECDH + SHA-256), so the server only ever stores "E2E1:" ciphertext. standard JCE
 * only, so it runs on every supported android version.
 *
 * message format: "E2E1:" + base64(12-byte IV + AES-GCM ciphertext).
 */
public final class E2EE {

    public static final String PREFIX = "E2E1:";
    private static final String PREFS = "koda_e2ee";
    private static final String KEY_PRIV = "priv_pkcs8_b64";
    private static final String KEY_PUB = "pub_x509_b64";
    private static final String KEY_UPLOADED = "uploaded_v1";

    private E2EE() {
    }

    /** the public key (base64 X.509), the pair is created on the first call. */
    public static synchronized String ensureKeyPair(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String pub = prefs.getString(KEY_PUB, null);
        if (pub != null) return pub;
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            prefs.edit()
                    .putString(KEY_PRIV, Base64.encodeToString(pair.getPrivate().getEncoded(), Base64.NO_WRAP))
                    .putString(KEY_PUB, Base64.encodeToString(pair.getPublic().getEncoded(), Base64.NO_WRAP))
                    .apply();
            return prefs.getString(KEY_PUB, null);
        } catch (Exception e) {
            return null;
        }
    }

    /** true after the public key went up to the account. */
    public static boolean isUploaded(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_UPLOADED, false);
    }

    public static void markUploaded(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_UPLOADED, true).apply();
    }

    /** the shared AES key, built from our private key and theirs. */
    public static byte[] sharedKey(Context context, String peerPublicKeyBase64) {
        if (peerPublicKeyBase64 == null || peerPublicKeyBase64.isEmpty()) return null;
        try {
            ensureKeyPair(context);
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            byte[] privBytes = Base64.decode(prefs.getString(KEY_PRIV, ""), Base64.NO_WRAP);
            PrivateKey priv = KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(privBytes));
            PublicKey peer = KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(Base64.decode(peerPublicKeyBase64, Base64.NO_WRAP)));

            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(priv);
            agreement.doPhase(peer, true);
            byte[] secret = agreement.generateSecret();
            // SHA-256 is the KDF, nothing fancier
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return digest.digest(secret);
        } catch (Exception e) {
            return null;
        }
    }

    /** encrypts into the wire format, null means the caller sends plaintext. */
    public static String encrypt(byte[] key, String plaintext) {
        if (key == null || plaintext == null) return null;
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes("UTF-8"));
            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    /** decrypts a wire message, null when the key does not fit it. */
    public static String decrypt(byte[] key, String message) {
        if (key == null || message == null || !message.startsWith(PREFIX)) return null;
        try {
            byte[] all = Base64.decode(message.substring(PREFIX.length()), Base64.NO_WRAP);
            byte[] iv = new byte[12];
            System.arraycopy(all, 0, iv, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(all, 12, all.length - 12);
            return new String(plain, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** @return true when what is stored is ciphertext. */
    public static boolean isEncrypted(String message) {
        return message != null && message.startsWith(PREFIX);
    }
}
