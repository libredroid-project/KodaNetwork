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
package eu.kodanetwork.mchost.orchestration;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import eu.kodanetwork.mchost.model.ServerInstance;
import eu.kodanetwork.mchost.network.supabase.SupabaseFunctionsClient;

public class StartOrchestrator {
    private static final String TAG = "Orchestrator";
    
    public enum Step {
        PREPARE,
        JAVA_DOWNLOAD,
        JAVA_INSTALL,
        SERVER_START,
        INJECTING,
        TUNNEL_START,
        DNS_LINK,
        READY
    }

    public interface Callback {
        void onStep(Step step, String message);
        void onCompleted(String playitAddress, String domainLink);
        void onError(Step step, String message);
        void requestServerStartIntent();
    }

    private final Context context;
    private final SupabaseFunctionsClient supabaseClient;
    private final Callback callback;

    public StartOrchestrator(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.supabaseClient = new SupabaseFunctionsClient(context);
    }

    public static void extractJavaIfMissing(Context context, Callback callback) {
        try {
            File jreDir = new File(context.getFilesDir(), "jre25");
            File javaBin = new File(jreDir, "bin/java");
            
            if (callback != null) callback.onStep(Step.PREPARE, "Setting up...");
            logStatic("JRE target dir: " + jreDir.getAbsolutePath());
            logStatic("Java binary path: " + javaBin.getAbsolutePath());
            logStatic("Java binary exists: " + javaBin.exists());
            
            if (!javaBin.exists()) {
                if (callback != null) callback.onStep(Step.JAVA_INSTALL, "Setting up...");
                
                // clear the leftovers of the old PRoot and native root setup
                File nativeRoot = new File(context.getFilesDir(), "native_root");
                File fakeRoot = new File(context.getFilesDir(), "fake_root");
                logStatic("Cleaning old dirs: native_root=" + nativeRoot.exists() + ", fake_root=" + fakeRoot.exists() + ", jre25=" + jreDir.exists());
                
                deleteRecursiveStatic(nativeRoot);
                deleteRecursiveStatic(fakeRoot);
                deleteRecursiveStatic(jreDir);
                
                jreDir.mkdirs();
                logStatic("JRE dir created: " + jreDir.exists());
                
                // list the assets, handy when the tar goes missing again
                try {
                    String[] assets = context.getAssets().list("");
                    logStatic("Available assets: " + (assets != null ? Arrays.toString(assets) : "null"));
                } catch (IOException e) {
                    logStatic("Could not list assets: " + e.getMessage());
                }
                
                // pull the tar.xz in from the assets
                logStatic("Opening asset: jre25-android-arm64.tar.xz ...");
                long startTime = System.currentTimeMillis();
                int fileCount = 0;
                long totalBytes = 0;
                
                try (java.io.InputStream is = context.getAssets().open("jre25-android-arm64.tar.xz");
                     org.apache.commons.compress.compressors.xz.XZCompressorInputStream xzIn = new org.apache.commons.compress.compressors.xz.XZCompressorInputStream(is);
                     org.apache.commons.compress.archivers.tar.TarArchiveInputStream tarIn = new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(xzIn)) {
                    
                    logStatic("XZ/Tar streams opened successfully");
                    
                    org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
                    while ((entry = tarIn.getNextTarEntry()) != null) {
                        File dest = new File(jreDir, entry.getName());
                        if (entry.isDirectory()) {
                            dest.mkdirs();
                        } else if (entry.isSymbolicLink()) {
                            dest.getParentFile().mkdirs();
                            try {
                                java.nio.file.Path link = dest.toPath();
                                java.nio.file.Path target = java.nio.file.Paths.get(entry.getLinkName());
                                if (java.nio.file.Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                                    java.nio.file.Files.delete(link);
                                }
                                java.nio.file.Files.createSymbolicLink(link, target);
                                fileCount++;
                            } catch (Exception e) {
                                logStatic("Symlink failed: " + e.getMessage());
                            }
                        } else {
                            dest.getParentFile().mkdirs();
                            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
                                byte[] b = new byte[8192];
                                int len;
                                while ((len = tarIn.read(b)) != -1) {
                                    fos.write(b, 0, len);
                                    totalBytes += len;
                                }
                            }
                            fileCount++;
                        }
                        
                        // a progress ping every 100 files, this takes a while
                        if (fileCount % 100 == 0 && fileCount > 0) {
                            if (callback != null) callback.onStep(Step.JAVA_INSTALL, "Setting up...");
                        }
                    }
                }
                
                long elapsed = System.currentTimeMillis() - startTime;
                logStatic("Extraction complete: " + fileCount + " files, " + (totalBytes / 1024 / 1024) + "MB in " + elapsed + "ms");
                
                // make bin and lib executable
                logStatic("Setting executable permissions...");
                setExecutableRecursiveStatic(new File(jreDir, "bin"));
                setExecutableRecursiveStatic(new File(jreDir, "lib"));
                
                // and check that the binary really landed and can run
                logStatic("Java binary exists after extraction: " + javaBin.exists());
                logStatic("Java binary executable: " + javaBin.canExecute());
                logStatic("Java binary size: " + javaBin.length() + " bytes");
                
                if (callback != null) callback.onStep(Step.JAVA_INSTALL, "JDK 25 extracted (" + fileCount + " files)");
            } else {
                logStatic("Java binary already exists, skipping extraction");
                logStatic("Java binary size: " + javaBin.length() + ", executable: " + javaBin.canExecute());
            }
            
            // pull the other Java runtimes (8, 17, 21) now, they are ready when asked
            int[] additionalVersions = {8, 17, 21};
            for (int ver : additionalVersions) {
                if (!eu.kodanetwork.mchost.util.RuntimeManager.isRuntimeInstalled(context, ver)) {
                    logStatic("Downloading Java " + ver + " runtime...");
                    if (callback != null) callback.onStep(Step.JAVA_DOWNLOAD, "Downloading Java " + ver + "...");
                    eu.kodanetwork.mchost.util.RuntimeManager.Result res = 
                        eu.kodanetwork.mchost.util.RuntimeManager.ensureRuntimeSync(context, ver,
                            (pct, msg) -> {
                                if (callback != null) callback.onStep(Step.JAVA_DOWNLOAD, "Java " + ver + ": " + pct + "% (" + msg + ")");
                            });
                    if (res.success) {
                        logStatic("Java " + ver + " installed successfully");
                    } else {
                        logStatic("Java " + ver + " download failed: " + res.failReason + " (will retry on-demand)");
                    }
                } else {
                    logStatic("Java " + ver + " already installed, skipping");
                }
            }
        } catch (java.io.FileNotFoundException e) {
            logStatic("CRITICAL: Asset file not found! " + e.getMessage());
            logStatic("Make sure jre25-android-arm64.tar.xz is in app/src/main/assets/");
            android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            if (callback != null) mainHandler.post(() -> callback.onError(Step.JAVA_INSTALL, 
                "JDK asset file missing!\nPlace jre25-android-arm64.tar.xz in app/src/main/assets/"));
        } catch (Exception e) {
            logStatic("CRITICAL: JDK Extraction failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (e.getCause() != null) {
                logStatic("  Caused by: " + e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
            }
            android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            if (callback != null) mainHandler.post(() -> callback.onError(Step.JAVA_INSTALL, "JDK Extraction failed: " + e.getMessage()));
        }
    }

    public void run(ServerInstance server, String userJwt) throws Exception {
        log("Starting sequence in Native mode...");
        log("Server ID: " + server.getId());
        log("Server Dir: " + server.getServerDir());
        log("RAM: " + server.getRamMB() + "MB, Type: " + server.getType());
        
        new Thread(() -> {
            extractJavaIfMissing(context, callback);
            android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            mainHandler.post(() -> {
                log("Launching server via service intent...");
                if (callback != null) {
                    callback.onStep(Step.SERVER_START, "Launching server...");
                    callback.requestServerStartIntent();
                    callback.onStep(Step.READY, "Sequence finished.");
                }
            });
        }).start();
    }

    private void log(String msg) {
        logStatic(msg);
    }

    private static void logStatic(String msg) {
        eu.kodanetwork.mchost.util.AppLogger.log(TAG, msg);
        Log.d(TAG, msg);
    }

    private static void deleteRecursiveStatic(File file) {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursiveStatic(child);
                }
            }
        }
        file.delete();
    }
    
    private static void setExecutableRecursiveStatic(File file) {
        if (!file.exists()) return;
        file.setExecutable(true, false);
        file.setReadable(true, false);
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    setExecutableRecursiveStatic(child);
                }
            }
        }
    }
}
