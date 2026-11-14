package com.github.kr328.clash

/**
 * Isi awal untuk berkas YAML baru di folder config.
 * [relativePath] dipakai agar contoh `path:` di komentar langsung sesuai lokasi berkasnya.
 */
object FileTemplates {
    const val EMPTY = 0
    const val PROXY_PROVIDER = 1
    const val RULE_PROVIDER = 2

    fun build(kind: Int, relativePath: String): String {
        return when (kind) {
            PROXY_PROVIDER -> proxyProvider(relativePath)
            RULE_PROVIDER -> ruleProvider(relativePath)
            else -> ""
        }
    }

    private fun proxyProvider(path: String): String = """
        # Proxy provider. Pasang di config utama:
        #
        # proxy-providers:
        #   contoh:
        #     type: file
        #     path: ./$path
        #
        # proxy-groups:
        #   - name: PROXY
        #     type: select
        #     use:
        #       - contoh
        proxies:
          - name: "contoh-trojan"
            type: trojan
            server: example.com
            port: 443
            password: "password"
            sni: example.com
    """.trimIndent() + "\n"

    private fun ruleProvider(path: String): String = """
        # Rule provider, behavior: domain. Pasang di config utama:
        #
        # rule-providers:
        #   contoh:
        #     type: file
        #     behavior: domain
        #     path: ./$path
        #
        # rules:
        #   - RULE-SET,contoh,REJECT
        #
        # "+.contoh.com" berarti contoh.com beserta semua subdomainnya.
        payload:
          - "+.contoh.com"
    """.trimIndent() + "\n"
}
