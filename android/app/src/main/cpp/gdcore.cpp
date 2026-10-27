#include <jni.h>
#include <string.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

namespace {

static const uint8_t K_MASK[32] = {
    43, 239, 15, 147, 211, 128, 21, 222, 162, 34, 60, 216, 251, 176, 179, 255,
    53, 90, 70, 172, 161, 163, 69, 17, 29, 77, 34, 38, 62, 106, 135, 32
};

static const uint8_t V_A[51] = {
    67, 162, 137, 248, 188, 221, 100, 34, 7, 140, 144, 254, 242, 30, 0, 7,
    203, 181, 233, 100, 80, 100, 198, 21, 22, 18, 249, 8, 32, 214, 189, 26,
    57, 130, 106, 213, 173, 179, 46, 1, 237, 108, 116, 224, 210, 253, 2, 26,
    161, 150, 142
};

static const uint8_t V_B[5] = { 74, 146, 112, 15, 217 };

static const uint8_t V_C[6] = { 29, 228, 68, 183, 0, 216 };

static const uint8_t V_D[32] = {
    72, 147, 73, 11, 255, 213, 87, 28, 204, 84, 158, 9, 30, 45, 57, 48,
    117, 229, 160, 31, 32, 38, 193, 202, 204, 43, 246, 219, 204, 215, 131, 30
};

// Kernel-level anti-ptrace (TracerPid), anti-Frida port (27042 / 0x69A2), and memory map scan
static bool isKernelTampered() {
    FILE* statusFp = fopen("/proc/self/status", "r");
    if (statusFp) {
        char line[256];
        while (fgets(line, sizeof(line), statusFp)) {
            if (strncmp(line, "TracerPid:", 10) == 0) {
                int pid = atoi(line + 10);
                if (pid != 0) {
                    fclose(statusFp);
                    return true;
                }
                break;
            }
        }
        fclose(statusFp);
    }

    FILE* tcpFp = fopen("/proc/net/tcp", "r");
    if (tcpFp) {
        char line[256];
        while (fgets(line, sizeof(line), tcpFp)) {
            if (strstr(line, ":69A2") || strstr(line, ":69a2")) {
                fclose(tcpFp);
                return true;
            }
        }
        fclose(tcpFp);
    }

    FILE* mapsFp = fopen("/proc/self/maps", "r");
    if (mapsFp) {
        char line[512];
        while (fgets(line, sizeof(line), mapsFp)) {
            if (strstr(line, "frida") || strstr(line, "gum-js-loop") || strstr(line, "xposed")) {
                fclose(mapsFp);
                return true;
            }
        }
        fclose(mapsFp);
    }

    return false;
}

static jstring nativeDspRead(JNIEnv* env, jobject /*thiz*/, jint slot, jint sigSeed) {
    if (isKernelTampered()) {
        return env->NewStringUTF("");
    }

    if (slot == 5) {
        // Slot 5: Native Cloudflare Auth Token (X-GD-Shield)
        uint8_t dynamicDelta = static_cast<uint8_t>((sigSeed & 0xFF) ^ 0x71);
        if (dynamicDelta != 0) return env->NewStringUTF("");
        char tokenBuf[20] = {
            'G','D','S','-','9','X','7','A','-','K','2','M','8','-','V','1','P','R','\0'
        };
        jstring tok = env->NewStringUTF(tokenBuf);
        volatile char* wp = tokenBuf;
        for (int i = 0; i < (int)sizeof(tokenBuf); ++i) wp[i] = 0;
        return tok;
    }

    const uint8_t* src = nullptr;
    int len = 0;

    switch (slot) {
        case 1: src = V_A; len = 51; break;
        case 2: src = V_B; len = 5;  break;
        case 3: src = V_C; len = 6;  break;
        case 4: src = V_D; len = 32; break;
        default: return env->NewStringUTF("");
    }

    uint8_t dynamicDelta = static_cast<uint8_t>((sigSeed & 0xFF) ^ 0x71);
    char stackBuf[64];

    for (int i = 0; i < len; ++i) {
        uint8_t k = K_MASK[i & 31] ^ dynamicDelta;
        uint8_t v = static_cast<uint8_t>((src[i] - (i * 7)) & 0xFF);
        stackBuf[i] = static_cast<char>(k ^ v);
    }
    stackBuf[len] = '\0';

    jstring result = env->NewStringUTF(stackBuf);

    volatile char* wipePtr = stackBuf;
    for (int i = 0; i < (int)sizeof(stackBuf); ++i) {
        wipePtr[i] = 0;
    }

    return result;
}

} // anonymous namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass cls = env->FindClass("com/gdstream/SecurityVaultModule");
    if (!cls) return JNI_ERR;

    static const JNINativeMethod methods[] = {
        { "nSync", "(II)Ljava/lang/String;", reinterpret_cast<void*>(nativeDspRead) }
    };

    if (env->RegisterNatives(cls, methods, 1) < 0) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
