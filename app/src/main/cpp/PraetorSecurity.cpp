/*
 * Copyright (c) 2026 KodaHosting
 * Triple-Licensed under GPL-3.0 / LOPL v1.0 PREVIEW / Commercial License
 * (see LICENSE, LOPL_v1.0_PREVIEW.md, COMMERCIAL-LICENSE.md)
 */
#include <jni.h>
#include <string>

// Echte Secrets (API-Keys UND Instanz-Endpunkte) liegen in secrets_local.h
// (untracked, .gitignore) - im oeffentlichen Code steht NICHTS Instanz-spezifisches.
// Oeffentliche Builds nutzen die Platzhalter aus secrets_local.h.example.
#include "secrets_local.h"

// P.R.A.E.T.O.R. Security Layer
// Obfuscation: XOR string to prevent simple strings/hex search in binary.
// Key: 'K' = 0x4B
// (Ja, das ist ein Speed-Bump und kein Tresor. Der echte Secret - der frp-Token -
// lebt seit 2026-09-15 sowieso nur noch serverseitig. Rest hier ist public-by-design.)

std::string deobfuscate(const unsigned char* obf, int len) {
    std::string out;
    for (int i = 0; i < len; i++) {
        out += (char)(obf[i] ^ 0x4B);
    }
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_eu_kodanetwork_mchost_security_PraetorSecurity_getSupabaseUrl(
        JNIEnv* env,
        jclass /* clazz */) {
    std::string res = deobfuscate(SUPABASE_URL_OBF, sizeof(SUPABASE_URL_OBF));
    return env->NewStringUTF(res.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_eu_kodanetwork_mchost_security_PraetorSecurity_getSupabaseKey(
        JNIEnv* env,
        jclass /* clazz */) {
    std::string res = deobfuscate(SUPABASE_ANON_OBF, sizeof(SUPABASE_ANON_OBF));
    return env->NewStringUTF(res.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_eu_kodanetwork_mchost_security_PraetorSecurity_getOpenRouterKey(
        JNIEnv* env,
        jclass /* clazz */) {
    std::string res = deobfuscate(OPENROUTER_KEY_OBF, sizeof(OPENROUTER_KEY_OBF));
    return env->NewStringUTF(res.c_str());
}


extern "C" JNIEXPORT jstring JNICALL
Java_eu_kodanetwork_mchost_security_PraetorSecurity_getBoreHost(
        JNIEnv* env,
        jclass /* clazz */) {
    std::string res = deobfuscate(BORE_HOST_OBF, sizeof(BORE_HOST_OBF));
    return env->NewStringUTF(res.c_str());
}

#include <sys/inotify.h>
#include <unistd.h>
#include <thread>
#include <android/log.h>

#define LOG_TAG "PraetorSecurityNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static JavaVM *gJvm = nullptr;
static jclass gAntiTamperClass = nullptr;
static jmethodID gBanMethod = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    gJvm = vm;
    return JNI_VERSION_1_6;
}

void checkTracerPidThread() {
    while (true) {
        FILE *fp = fopen("/proc/self/status", "r");
        if (fp != nullptr) {
            char line[256];
            while (fgets(line, sizeof(line), fp)) {
                if (strncmp(line, "TracerPid:", 10) == 0) {
                    int tracerPid = atoi(&line[10]);
                    if (tracerPid != 0) {
                        LOGE("DEBUGGER ATTACHED! TracerPid: %d", tracerPid);
                        // Trigger Ban
                        JNIEnv *env;
                        int getEnvStat = gJvm->GetEnv((void **)&env, JNI_VERSION_1_6);
                        bool attached = false;
                        if (getEnvStat == JNI_EDETACHED) {
                            if (gJvm->AttachCurrentThread(&env, nullptr) == 0) {
                                attached = true;
                            }
                        }
                        if (gAntiTamperClass != nullptr && gBanMethod != nullptr) {
                            jstring reason = env->NewStringUTF("DEBUGGER_ATTACHED");
                            env->CallStaticVoidMethod(gAntiTamperClass, gBanMethod, reason);
                            env->DeleteLocalRef(reason);
                        }
                        if (attached) {
                            gJvm->DetachCurrentThread();
                        }
                        // Kill self instantly to prevent reverse engineering
                        kill(getpid(), SIGKILL);
                    }
                    break;
                }
            }
            fclose(fp);
        }
        std::this_thread::sleep_for(std::chrono::seconds(2));
    }
}

void inotifyWatcherThread(std::vector<std::string> filesToWatch) {
    LOGI("Inotify watcher started for %zu files", filesToWatch.size());
    int fd = inotify_init();
    if (fd < 0) {
        LOGE("inotify_init failed");
        return;
    }

    for (const std::string& file : filesToWatch) {
        int wd = inotify_add_watch(fd, file.c_str(), IN_OPEN | IN_ACCESS);
        if (wd < 0) {
            LOGE("inotify_add_watch failed for %s", file.c_str());
        } else {
            LOGI("Watching %s", file.c_str());
        }
    }

    char buffer[4096] __attribute__ ((aligned(__alignof__(struct inotify_event))));
    while (true) {
        ssize_t len = read(fd, buffer, sizeof(buffer));
        if (len < 0) {
            LOGE("inotify read failed");
            break;
        }
        
        LOGE("INOTIFY TRIGGERED! A file was accessed.");

        // A file was opened!
        // We trigger the ban callback.
        JNIEnv *env;
        int getEnvStat = gJvm->GetEnv((void **)&env, JNI_VERSION_1_6);
        bool attached = false;
        if (getEnvStat == JNI_EDETACHED) {
            if (gJvm->AttachCurrentThread(&env, nullptr) != 0) {
                LOGE("Failed to attach");
                continue;
            }
            attached = true;
        }
        
        if (gAntiTamperClass != nullptr && gBanMethod != nullptr) {
            jstring reason = env->NewStringUTF("FILE_READ_DETECTED");
            env->CallStaticVoidMethod(gAntiTamperClass, gBanMethod, reason);
            env->DeleteLocalRef(reason);
            LOGI("Called executePermanentBanNative successfully");
        } else {
            LOGE("Global class or method is null!");
        }

        if (attached) {
            gJvm->DetachCurrentThread();
        }
    }
    close(fd);
}

extern "C" JNIEXPORT void JNICALL
Java_eu_kodanetwork_mchost_security_PraetorSecurity_startInotifyWatcher(
        JNIEnv* env,
        jclass clazz,
        jobjectArray filesToWatchArray) {
        
    // Cache the class and method ID using the application classloader
    if (gAntiTamperClass == nullptr) {
        jclass localClass = env->FindClass("eu/kodanetwork/mchost/security/AntiTamperSystem");
        if (localClass != nullptr) {
            gAntiTamperClass = (jclass) env->NewGlobalRef(localClass);
            gBanMethod = env->GetStaticMethodID(gAntiTamperClass, "executePermanentBanNative", "(Ljava/lang/String;)V");
            env->DeleteLocalRef(localClass);
        }
    }

    if (filesToWatchArray == nullptr) return;
    
    std::vector<std::string> filesToWatch;
    int count = env->GetArrayLength(filesToWatchArray);
    for (int i = 0; i < count; i++) {
        jstring jstr = (jstring) env->GetObjectArrayElement(filesToWatchArray, i);
        const char *str = env->GetStringUTFChars(jstr, 0);
        filesToWatch.push_back(std::string(str));
        env->ReleaseStringUTFChars(jstr, str);
        env->DeleteLocalRef(jstr);
    }
    
    std::thread watcher(inotifyWatcherThread, filesToWatch);
    watcher.detach();
    
    std::thread antiDebugger(checkTracerPidThread);
    antiDebugger.detach();
}
