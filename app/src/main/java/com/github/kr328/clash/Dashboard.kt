package com.github.kr328.clash

import android.content.Context
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.service.store.ServiceStore
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

/**
 * Dashboard YACD: file web disalin ke folder profil aktif (`ui`), lalu external-controller
 * diaktifkan lewat override persist di 127.0.0.1 (core menyajikan UI di /ui).
 */
object Dashboard {
    private const val HOST = "127.0.0.1"
    private const val PORT = 9090
    const val PROXY_PORT = 7890
    private const val ASSET_DIR = "yacd"

    /** Naikkan angka ini setiap file YACD di assets berubah, agar profil lama ikut diperbarui. */
    private const val UI_VERSION = 2
    private const val UI_VERSION_FILE = ".nusa_ui_version"

    /** Salin file YACD ke folder profil bila belum ada atau versinya lebih lama. */
    fun install(context: Context, uuid: UUID) {
        val target = context.importedDir.resolve(uuid.toString()).resolve("ui")
        val marker = target.resolve(UI_VERSION_FILE)

        val upToDate = target.resolve("index.html").exists() &&
                marker.exists() &&
                marker.readText().trim() == UI_VERSION.toString()

        if (upToDate) return

        copyAsset(context, ASSET_DIR, target)

        marker.writeText(UI_VERSION.toString())
    }

    private fun copyAsset(context: Context, path: String, target: File) {
        val children = context.assets.list(path) ?: emptyArray()

        if (children.isEmpty()) {
            target.parentFile?.mkdirs()

            context.assets.open(path).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            target.mkdirs()

            children.forEach { copyAsset(context, "$path/$it", target.resolve(it)) }
        }
    }

    /** Aktifkan external-controller + secret acak (disimpan di override persist). Mengembalikan secret. */
    suspend fun ensureController(): String {
        return withClash {
            val override = queryOverride(Clash.OverrideSlot.Persist)

            var changed = false

            if (override.externalController != "$HOST:$PORT") {
                override.externalController = "$HOST:$PORT"
                changed = true
            }

            val secret: String = override.secret?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString().replace("-", "").take(24).also {
                    override.secret = it
                    changed = true
                }

            if (changed) {
                patchOverride(Clash.OverrideSlot.Persist, override)
            }

            secret
        }
    }

    /** Terapkan opsi "Share via hotspot": proxy campuran dibuka untuk perangkat lain di jaringan lokal. */
    suspend fun applyHotspot(context: Context) {
        val enabled = ServiceStore(context).hotspotShare

        withClash {
            val override = queryOverride(Clash.OverrideSlot.Persist)

            if (enabled) {
                override.allowLan = true
                override.bindAddress = "*"
                override.mixedPort = PROXY_PORT

                patchOverride(Clash.OverrideSlot.Persist, override)
            } else if (override.allowLan == true && override.bindAddress == "*") {
                override.allowLan = null
                override.bindAddress = null
                override.mixedPort = null

                patchOverride(Clash.OverrideSlot.Persist, override)
            }
        }
    }

    /** Alamat IPv4 perangkat ini yang bisa dipakai perangkat lain (hotspot atau Wi-Fi). */
    fun localAddresses(): List<String> {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter {
                    it.isUp && !it.isLoopback &&
                            listOf("tun", "dummy", "rmnet", "ccmni").none { prefix -> it.name.startsWith(prefix) }
                }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Dipanggil sebelum layanan dimulai agar core memuat controller dan UI. */
    suspend fun prepare(context: Context, uuid: UUID) {
        try {
            install(context, uuid)
            ensureController()
            applyHotspot(context)
        } catch (e: Exception) {
            // dashboard bersifat tambahan, jangan menghalangi koneksi
        }
    }

    suspend fun url(): String {
        val secret = ensureController()

        return "http://$HOST:$PORT/ui/?hostname=$HOST&port=$PORT&secret=$secret"
    }
}
