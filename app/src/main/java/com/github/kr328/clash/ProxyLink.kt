package com.github.kr328.clash

import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Mengubah tautan vmess:// dan trojan:// menjadi entri proxy Clash (YAML),
 * serta membungkus daftar proxy menjadi config lengkap bila perlu.
 */
object ProxyLink {
    private const val BEGIN = "# kentang-begin"
    private const val END = "# kentang-end"

    private fun quote(v: String): String =
        "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun dec(v: String): String = try {
        URLDecoder.decode(v, "UTF-8")
    } catch (e: Exception) {
        v
    }

    /** Satu tautan menjadi blok YAML, atau null bila tidak dikenali / tidak valid. */
    fun convert(link: String): String? {
        val l = link.trim()

        return try {
            when {
                l.startsWith("vmess://", ignoreCase = true) -> vmess(l.substring(8))
                l.startsWith("trojan://", ignoreCase = true) -> trojan(l.substring(9))
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun vmess(payload: String): String {
        var s = payload.trim().replace('-', '+').replace('_', '/')
        while (s.length % 4 != 0) s += "="

        val json = JSONObject(String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8))

        val server = json.getString("add")
        val name = json.optString("ps").ifBlank { server }
        val port = json.get("port").toString().trim().toInt()
        val uuid = json.getString("id")
        val aid = json.optString("aid", "0").ifBlank { "0" }.toIntOrNull() ?: 0
        val cipher = json.optString("scy").ifBlank { "auto" }
        val net = json.optString("net").ifBlank { "tcp" }
        val host = json.optString("host")
        val path = json.optString("path")
        val tls = json.optString("tls").equals("tls", ignoreCase = true)
        val sni = json.optString("sni")

        val sb = StringBuilder()

        sb.appendLine("- name: ${quote(name)}")
        sb.appendLine("  server: ${quote(server)}")
        sb.appendLine("  type: vmess")
        sb.appendLine("  port: $port")
        sb.appendLine("  uuid: ${quote(uuid)}")
        sb.appendLine("  alterId: $aid")
        sb.appendLine("  cipher: $cipher")

        if (tls) {
            sb.appendLine("  tls: true")

            val serverName = sni.ifBlank { host }

            if (serverName.isNotBlank()) {
                sb.appendLine("  servername: ${quote(serverName)}")
            }
        } else {
            sb.appendLine("  tls: false")
        }

        transport(sb, net, host, path, path)

        sb.appendLine("  udp: true")

        return sb.toString()
    }

    private fun trojan(rest: String): String {
        val fragIdx = rest.indexOf('#')
        val name = if (fragIdx >= 0) dec(rest.substring(fragIdx + 1)) else ""
        val main = if (fragIdx >= 0) rest.substring(0, fragIdx) else rest

        val qIdx = main.indexOf('?')
        val query = if (qIdx >= 0) main.substring(qIdx + 1) else ""
        val authority = (if (qIdx >= 0) main.substring(0, qIdx) else main).trimEnd('/')

        val at = authority.lastIndexOf('@')
        require(at > 0)

        val password = dec(authority.substring(0, at))
        val hostPort = authority.substring(at + 1)
        val colon = hostPort.lastIndexOf(':')
        require(colon > 0)

        val server = hostPort.substring(0, colon).trim('[', ']')
        val port = hostPort.substring(colon + 1).trim().toInt()

        val params = query.split('&')
            .filter { it.contains('=') }
            .associate {
                val i = it.indexOf('=')
                dec(it.substring(0, i)) to dec(it.substring(i + 1))
            }

        val sni = params["sni"] ?: params["peer"] ?: ""
        val net = params["type"] ?: "tcp"
        val host = params["host"] ?: ""
        val path = params["path"] ?: ""
        val serviceName = params["serviceName"] ?: path
        val insecure = params["allowInsecure"] == "1" || params["allowInsecure"] == "true"

        val sb = StringBuilder()

        sb.appendLine("- name: ${quote(name.ifBlank { server })}")
        sb.appendLine("  server: ${quote(server)}")
        sb.appendLine("  type: trojan")
        sb.appendLine("  port: $port")
        sb.appendLine("  password: ${quote(password)}")

        if (sni.isNotBlank()) {
            sb.appendLine("  sni: ${quote(sni)}")
        }

        if (insecure) {
            sb.appendLine("  skip-cert-verify: true")
        }

        transport(sb, net, host, path, serviceName)

        sb.appendLine("  udp: true")

        return sb.toString()
    }

    private fun transport(sb: StringBuilder, net: String, host: String, path: String, serviceName: String) {
        when (net.lowercase()) {
            "ws" -> {
                sb.appendLine("  network: ws")
                sb.appendLine("  ws-opts:")
                sb.appendLine("    path: ${quote(path.ifBlank { "/" })}")

                if (host.isNotBlank()) {
                    sb.appendLine("    headers:")
                    sb.appendLine("      Host: ${quote(host)}")
                }
            }
            "grpc" -> {
                sb.appendLine("  network: grpc")
                sb.appendLine("  grpc-opts:")
                sb.appendLine("    grpc-service-name: ${quote(serviceName)}")
            }
            "h2" -> {
                sb.appendLine("  network: h2")
                sb.appendLine("  h2-opts:")

                if (host.isNotBlank()) {
                    sb.appendLine("    host:")
                    sb.appendLine("    - ${quote(host)}")
                }

                sb.appendLine("    path: ${quote(path.ifBlank { "/" })}")
            }
        }
    }

    /** Teks hanya berisi daftar proxy -> dibungkus menjadi config lengkap. */
    fun wrap(text: String): String {
        if (Regex("(?m)^(proxies|proxy-providers):").containsMatchIn(text)) return text

        val names = Regex("(?m)^-\\s*name:\\s*(.+?)\\s*$")
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()

        if (names.isEmpty()) return text

        val sb = StringBuilder()

        sb.appendLine("mixed-port: 7890")
        sb.appendLine("allow-lan: false")
        sb.appendLine("mode: rule")
        sb.appendLine("log-level: info")
        sb.appendLine("ipv6: false")
        sb.appendLine("proxies:")
        sb.appendLine(BEGIN)
        sb.appendLine(text.trim('\n', '\r'))
        sb.appendLine(END)
        sb.appendLine("proxy-groups:")
        sb.appendLine("- name: PROXY")
        sb.appendLine("  type: select")
        sb.appendLine("  proxies:")
        names.forEach { sb.appendLine("  - $it") }
        sb.appendLine("  - DIRECT")
        sb.appendLine("rules:")
        sb.appendLine("- MATCH,PROXY")

        return sb.toString()
    }

    /** Kebalikan wrap: tampilkan hanya daftar proxy bila config dibuat oleh aplikasi ini. */
    fun unwrap(text: String): String {
        val b = text.indexOf(BEGIN)
        val e = text.indexOf(END)

        if (b < 0 || e < b) return text

        return text.substring(b + BEGIN.length, e).trim('\n', '\r')
    }
}
