package com.gdstream

import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.FileReader
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale

class SecurityVaultModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        init {
            try {
                System.loadLibrary("gdcore")
            } catch (_: Throwable) {}
        }
    }

    override fun getName(): String = "SecurityVault"

    // Registered dynamically via JNI_OnLoad in libgdcore.so (No exported C++ symbol name!)
    private external fun nSync(slot: Int, sigSeed: Int): String

    private fun verifyCertificateAndEnv(): Int {
        try {
            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return 0

            // Layer 5: Anti-MITM Proxy Shield (Blocks Reqable / HttpCanary / Burp / Charles)
            val proxyHost = System.getProperty("http.proxyHost")
            val proxyPort = System.getProperty("http.proxyPort")
            if (!proxyHost.isNullOrBlank() || (!proxyPort.isNullOrBlank() && proxyPort != "-1")) {
                return 0
            }

            // Layer 6: Runtime Hook & Memory Map Inspection (Encoded tokens so nothing shows in strings)
            val tokens = arrayOf(
                String(charArrayOf('f', 'r', 'i', 'd', 'a')),
                String(charArrayOf('x', 'p', 'o', 's', 'e', 'd'))
            )
            BufferedReader(FileReader("/proc/self/maps")).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val lower = line!!.lowercase(Locale.ROOT)
                    if (lower.contains(tokens[0]) || lower.contains(tokens[1])) {
                        return 0
                    }
                }
            }

            // Layer 3: APK Signing Certificate & Package Identity Binding
            val pkgName = reactContext.packageName
            if (pkgName != "com.gdstream") return 0

            val pm = reactContext.packageManager
            val certBytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNING_CERTIFICATES)
                val sigs = info.signingInfo?.apkContentsSigners
                if (!sigs.isNullOrEmpty()) sigs[0].toByteArray() else null
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES)
                val sigs = info.signatures
                if (!sigs.isNullOrEmpty()) sigs[0].toByteArray() else null
            }

            if (certBytes != null) {
                val cf = CertificateFactory.getInstance("X.509")
                val cert = cf.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
                val subject = cert.subjectDN.name.uppercase(Locale.ROOT)
                // Must match our official keystore DN (Rejects MT Manager / Apktool test keys)
                if (!subject.contains("GDSTREAM") && !subject.contains("ANDROID DEBUG")) {
                    return 0
                }
            }

            return 0x5A3C9E71
        } catch (_: Throwable) {
            return 0
        }
    }

    @ReactMethod
    fun getVaultConfig(promise: Promise) {
        try {
            val seed = verifyCertificateAndEnv()
            val map = Arguments.createMap()
            if (seed != 0x5A3C9E71) {
                map.putBoolean("s", true)
                map.putString("a", "")
                map.putString("b", "")
                map.putString("c", "")
                map.putString("d", "")
                promise.resolve(map)
                return
            }

            map.putBoolean("s", false)
            map.putString("a", nSync(1, seed))
            map.putString("b", nSync(2, seed))
            map.putString("c", nSync(3, seed))
            map.putString("d", nSync(4, seed))
            promise.resolve(map)
        } catch (_: Throwable) {
            promise.reject("E_SYNC", " Calibration error")
        }
    }
}
