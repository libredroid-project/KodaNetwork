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
package eu.kodanetwork.mchost.transfer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM crypto for the device transfer, in fixed size FRAMES.
 *
 * why frames: Android's Conscrypt buffers the whole GCM stream internally and
 * runs one giant doFinal() at close(), so a few hundred MB server ZIP dies with
 * OutOfMemoryError (seen on a real device). encrypting in 512 KB frames like TLS
 * records keeps memory flat whatever the server size: every frame gets its own
 * IV (8 random bytes of the per-server base IV plus a 4 byte counter) and its
 * own authentication tag.
 *
 * wire format: ([int32 ciphertext length][ciphertext+tag])* [int32 0]
 * the SHA-256 of the PLAINTEXT travels in the manifest and is computed while
 * streaming, so the file is never read a second time.
 */
public final class TransferCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** plaintext bytes per frame, the ciphertext is frame + 16 byte GCM tag. */
    public static final int FRAME = 512 * 1024;

    public interface ProgressListener {
        void onProgress(long done, long total);
    }

    private TransferCrypto() {
    }

    public static byte[] randomBytes(int n) {
        byte[] out = new byte[n];
        RANDOM.nextBytes(out);
        return out;
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public static byte[] fromHex(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** frame IV: 8 random bytes of the base IV plus a 4 byte frame counter. */
    private static byte[] frameIv(byte[] baseIv, long index) {
        byte[] iv = new byte[12];
        System.arraycopy(baseIv, 0, iv, 0, 8);
        iv[8] = (byte) (index >>> 24);
        iv[9] = (byte) (index >>> 16);
        iv[10] = (byte) (index >>> 8);
        iv[11] = (byte) index;
        return iv;
    }

    private static Cipher aead() throws IOException {
        try {
            return Cipher.getInstance("AES/GCM/NoPadding");
        } catch (Exception e) {
            throw new IOException("cipher unavailable", e);
        }
    }

    /**
     * sender side: an OutputStream that encrypts on the fly. wrap the
     * ZipOutputStream in it and the archive never exists as plaintext anywhere,
     * one pass from the server folder to the encrypted blob.
     */
    public static final class FramedEncryptOutputStream extends OutputStream {

        private final OutputStream sink;
        private final SecretKeySpec key;
        private final byte[] baseIv;
        private final MessageDigest digest;
        private final byte[] buf = new byte[FRAME];
        private int fill = 0;
        private long frames = 0;
        private long plainBytes = 0;
        private boolean closed = false;

        public FramedEncryptOutputStream(OutputStream sink, byte[] keyBytes, byte[] baseIv) throws IOException {
            this.sink = sink;
            this.key = new SecretKeySpec(keyBytes, "AES");
            this.baseIv = baseIv;
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                throw new IOException("digest unavailable", e);
            }
        }

        @Override
        public void write(int b) throws IOException {
            buf[fill++] = (byte) b;
            if (fill == FRAME) emit();
        }

        @Override
        public void write(byte[] src, int off, int len) throws IOException {
            plainBytes += len;
            while (len > 0) {
                int space = FRAME - fill;
                int take = Math.min(space, len);
                System.arraycopy(src, off, buf, fill, take);
                fill += take;
                off += take;
                len -= take;
                if (fill == FRAME) emit();
            }
        }

        private void emit() throws IOException {
            if (fill == 0) return;
            try {
                Cipher cipher = aead();
                cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, frameIv(baseIv, frames)));
                byte[] ct = cipher.doFinal(buf, 0, fill);
                writeInt(sink, ct.length);
                sink.write(ct);
                digest.update(buf, 0, fill);
                frames++;
                fill = 0;
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("encrypt frame failed", e);
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            emit();
            writeInt(sink, 0); // end marker
            sink.close();
        }

        /** SHA-256 of the plaintext (hex), only meaningful after close(). */
        public String digestHex() {
            return toHex(digest.digest());
        }

        public long plainSize() {
            return plainBytes;
        }
    }

    /**
     * receiver side: pulls frames off the (network) stream, decrypts each one and
     * writes the plaintext out while hashing it. a broken frame or a bad
     * authentication tag throws.
     *
     * @return SHA-256 of the plaintext (hex)
     */
    public static String decryptFramed(InputStream src, OutputStream plainOut, byte[] keyBytes,
                                       byte[] baseIv, ProgressListener progress) throws IOException {
        try {
            SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Cipher cipher = aead();
            byte[] header = new byte[4];
            long done = 0;
            long frameIndex = 0;
            while (readFully(src, header, 4)) {
                int len = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16)
                        | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
                done += 4;
                if (len == 0) break; // end marker
                if (len < 16 || len > FRAME + 16) {
                    throw new IOException("frame out of bounds: " + len);
                }
                byte[] ct = new byte[len];
                if (!readFully(src, ct, len)) {
                    throw new IOException("connection closed inside a frame");
                }
                done += len;
                byte[] plain;
                try {
                    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, frameIv(baseIv, frameIndex++)));
                    plain = cipher.doFinal(ct);
                } catch (Exception e) {
                    throw new IOException("frame failed to authenticate", e);
                }
                plainOut.write(plain);
                digest.update(plain);
                if (progress != null) progress.onProgress(done, -1);
            }
            return toHex(digest.digest());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("decrypt failed", e);
        }
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static boolean readFully(InputStream in, byte[] target, int count) throws IOException {
        int off = 0;
        while (off < count) {
            int read = in.read(target, off, count - off);
            if (read == -1) return false;
            off += read;
        }
        return true;
    }

    public static String humanSpeed(long bytes, long elapsedMs) {
        if (elapsedMs <= 0) return "";
        double mb = bytes / 1048576.0;
        double s = elapsedMs / 1000.0;
        return String.format(Locale.US, "%.1f MB/s", mb / s);
    }
}
