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
package de.kodahosting.kodadash.auth;

import com.sun.net.httpserver.HttpExchange;
import de.kodahosting.kodadash.KodaDash;
import org.mindrot.jbcrypt.BCrypt;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * checks the API token, the optional password and the failure limits.
 */
public class AuthManager {
    private final KodaDash plugin;
    private final ConcurrentHashMap<String, FailedAttempt> failedAttempts = new ConcurrentHashMap<>();

    private static class FailedAttempt {
        int count;
        long lastAttempt;
    }

    public AuthManager(KodaDash plugin) {
        this.plugin = plugin;
    }

    /**
     * checks a request's token and, when one is configured, its password.
     * the token may arrive as Bearer, as X-API-Token or as the ?token= query parameter.
     */
    public boolean authenticate(HttpExchange exchange) {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        cleanupOldAttempts();

        FailedAttempt attempt = failedAttempts.get(ip);
        int maxAttempts = plugin.getConfig().getInt("rate-limit.max-auth-attempts", 10);
        if (attempt != null && attempt.count >= maxAttempts
                && System.currentTimeMillis() - attempt.lastAttempt < 60000) {
            return false;
        }

        // the token can come in through three different doors
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        String tokenHeader = exchange.getRequestHeaders().getFirst("X-API-Token");
        String queryToken = extractQueryParam(exchange.getRequestURI().getQuery(), "token");

        String providedToken = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            providedToken = authHeader.substring(7);
        } else if (tokenHeader != null) {
            providedToken = tokenHeader;
        } else if (queryToken != null) {
            providedToken = queryToken;
        }

        // compared in constant time, so the answer does not leak the token byte by byte
        String expectedToken = getToken();
        if (providedToken == null || expectedToken == null || expectedToken.isEmpty()
                || !MessageDigest.isEqual(providedToken.getBytes(), expectedToken.getBytes())) {
            recordFailure(ip);
            return false;
        }

        // password only when one is configured at all
        String expectedHash = plugin.getConfig().getString("password-hash");
        if (expectedHash != null && !expectedHash.isEmpty()) {
            String providedPass = exchange.getRequestHeaders().getFirst("X-Dashboard-Password");
            if (providedPass == null || providedPass.isEmpty()) {
                providedPass = extractQueryParam(exchange.getRequestURI().getQuery(), "password");
            }
            if (providedPass == null || !BCrypt.checkpw(providedPass, expectedHash)) {
                recordFailure(ip);
                return false;
            }
        }

        // fine, so the failure count for this ip is forgotten
        failedAttempts.remove(ip);
        return true;
    }

    /**
     * only a different name for authenticate(), routes that override handle() call it like this.
     */
    public boolean isAuthenticated(HttpExchange exchange) {
        return authenticate(exchange);
    }

    /**
     * the same check as authenticate(), but on values handed in directly. AuthRoute POST uses it.
     *
     * @param token    the API token
     * @param password the password, null when none is set
     * @param ip       client IP, for the rate limiting
     * @return true if valid
     */
    public boolean validate(String token, String password, String ip) {
        cleanupOldAttempts();

        FailedAttempt attempt = failedAttempts.get(ip);
        int maxAttempts = plugin.getConfig().getInt("rate-limit.max-auth-attempts", 10);
        if (attempt != null && attempt.count >= maxAttempts
                && System.currentTimeMillis() - attempt.lastAttempt < 60000) {
            return false;
        }

        String expectedToken = getToken();
        if (token == null || expectedToken == null || expectedToken.isEmpty()
                || !MessageDigest.isEqual(token.getBytes(), expectedToken.getBytes())) {
            recordFailure(ip);
            return false;
        }

        String expectedHash = plugin.getConfig().getString("password-hash");
        if (expectedHash != null && !expectedHash.isEmpty()) {
            if (password == null || !BCrypt.checkpw(password, expectedHash)) {
                recordFailure(ip);
                return false;
            }
        }

        failedAttempts.remove(ip);
        return true;
    }

    /**
     * is a dashboard password set at all.
     */
    public boolean hasPassword() {
        String hash = plugin.getConfig().getString("password-hash");
        return hash != null && !hash.isEmpty();
    }

    /**
     * sets a new dashboard password, stored as a BCrypt hash.
     */
    public void setPassword(String plaintext) {
        String hash = BCrypt.hashpw(plaintext, BCrypt.gensalt(12));
        plugin.getConfig().set("password-hash", hash);
        plugin.saveConfig();
    }

    /**
     * drops the password requirement again.
     */
    public void removePassword() {
        plugin.getConfig().set("password-hash", "");
        plugin.saveConfig();
    }

    /**
     * makes a new API token and puts it into the config.
     *
     * @return the new token string
     */
    public String regenerateToken() {
        String newToken = generateRandomToken();
        plugin.getConfig().set("api-token", newToken);
        plugin.saveConfig();
        return newToken;
    }

    /**
     * the token that is currently in the config.
     */
    public String getToken() {
        return plugin.getConfig().getString("api-token", "");
    }

    // --- Private helpers ---

    private void recordFailure(String ip) {
        FailedAttempt attempt = failedAttempts.computeIfAbsent(ip, k -> new FailedAttempt());
        attempt.count++;
        attempt.lastAttempt = System.currentTimeMillis();
    }

    private void cleanupOldAttempts() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, FailedAttempt>> it = failedAttempts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, FailedAttempt> entry = it.next();
            if (now - entry.getValue().lastAttempt > 60000) {
                it.remove();
            }
        }
    }

    private String extractQueryParam(String query, String key) {
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length > 1 && key.equals(pair[0])) {
                return pair[1];
            }
        }
        return null;
    }

    private String generateRandomToken() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
