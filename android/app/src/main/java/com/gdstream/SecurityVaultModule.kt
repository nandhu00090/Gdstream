package com.gdstream

import android.content.pm.ApplicationInfo
import android.os.Debug
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.io.BufferedReader
import java.io.FileReader

class SecurityVaultModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "SecurityVault"

    private val kMask = intArrayOf(43, 239, 15, 147, 211, 128, 21, 222, 162, 34, 60, 216, 251, 176, 179, 255, 53, 90, 70, 172, 161, 163, 69, 17, 29, 77, 34, 38, 62, 106, 135, 32)
    private val vUrl = intArrayOf(67, 162, 137, 248, 188, 221, 100, 34, 7, 140, 144, 254, 242, 30, 0, 7, 203, 181, 233, 100, 80, 100, 198, 21, 22, 18, 249, 8, 32, 214, 189, 26, 57, 130, 106, 213, 173, 179, 46, 1, 237, 108, 116, 224, 210, 253, 2, 26, 161, 150, 142)
    private val vUser = intArrayOf(74, 146, 112, 15, 217)
    private val vPass = intArrayOf(29, 228, 68, 183, 0, 216)
    private val vTmdb = intArrayOf(72, 147, 73, 11, 255, 213, 87, 28, 204, 84, 158, 9, 30, 45, 57, 48, 117, 229, 160, 31, 32, 38, 193, 202, 204, 43, 246, 219, 204, 215, 131, 30)

    private fun unlockBytes(encoded: IntArray): String {
        if (encoded.isEmpty()) return ""
        val out = ByteArray(encoded.size)
        for (i in encoded.indices) {
            val k = kMask[i % kMask.size]
            val raw = ((encoded[i] - (i * 7)) and 0xFF) xor k
            out[i] = raw.toByte()
        }
        return String(out, Charsets.UTF_8)
    }

    private fun isEnvironmentTampered(): Boolean {
        try {
            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
                return true
            }
            val suspiciousClasses = listOf(
                "de.robv.android.xposed.XposedBridge",
                "de.robv.android.xposed.XC_MethodHook"
            )
            for (cls in suspiciousClasses) {
                try {
                    Class.forName(cls)
                    return true
                } catch (_: Throwable) {}
            }
            BufferedReader(FileReader("/proc/self/maps")).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line!!.lowercase()
                    if (l.contains("frida-agent") || l.contains("frida-gadget") || l.contains("xposedbridge.jar")) {
                        return true
                    }
                }
            }
        } catch (_: Throwable) {}
        return false
    }

    @ReactMethod
    fun getVaultConfig(promise: Promise) {
        try {
            val tampered = isEnvironmentTampered()
            if (tampered) {
                val blocked = Arguments.createMap().apply {
                    putBoolean("compromised", true)
                    putString("baseUrl", "")
                    putString("username", "")
                    putString("password", "")
                    putString("tmdbKey", "")
                }
                promise.resolve(blocked)
                return
            }

            val map = Arguments.createMap().apply {
                putBoolean("compromised", false)
                putString("baseUrl", unlockBytes(vUrl))
                putString("username", unlockBytes(vUser))
                putString("password", unlockBytes(vPass))
                putString("tmdbKey", unlockBytes(vTmdb))
            }
            promise.resolve(map)
        } catch (e: Exception) {
            promise.reject("VAULT_ERR", "Security check failed")
        }
    }
}
