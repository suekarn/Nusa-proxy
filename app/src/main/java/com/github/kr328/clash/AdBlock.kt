package com.github.kr328.clash

/**
 * Menyisipkan atau menghapus blok iklan pada teks config Clash.
 *
 * Yang disisipkan ada dua bagian, masing-masing dibatasi komentar penanda:
 *  - sebuah rule-provider bernama [PROVIDER_NAME] (daftar domain iklan, diunduh otomatis), dan
 *  - satu aturan `RULE-SET,nusa-adblock,REJECT` di urutan paling atas `rules:`.
 *
 * Karena dibatasi penanda, bagian ini bisa dihapus bersih lagi lewat [remove],
 * dan tidak mengganggu isi config lain milik pengguna.
 */
object AdBlock {
    private const val BEGIN = "# nusa-adblock-begin"
    private const val END = "# nusa-adblock-end"

    const val PROVIDER_NAME = "nusa-adblock"

    /** Lokasi file daftar iklan, relatif terhadap folder profil. */
    const val PROVIDER_PATH = "rule_provider/nusa-adblock.yaml"

    private const val PROVIDER_URL =
        "https://cdn.jsdelivr.net/gh/Loyalsoldier/clash-rules@release/reject.txt"

    fun isEnabled(text: String): Boolean = text.contains(BEGIN)

    /** Hapus semua blok iklan yang pernah disisipkan. Teks tanpa penanda dikembalikan apa adanya. */
    fun remove(text: String): String {
        if (!text.contains(BEGIN)) return text

        val out = ArrayList<String>()
        var skipping = false

        for (line in text.lines()) {
            val trimmed = line.trim()

            when {
                skipping -> if (trimmed == END) skipping = false
                trimmed == BEGIN -> skipping = true
                else -> out.add(line)
            }
        }

        // penanda tidak berpasangan: jangan merusak teks pengguna
        if (skipping) return text

        return out.joinToString("\n")
    }

    /**
     * Sisipkan blok iklan. Mengembalikan null bila config memakai gaya penulisan yang tidak
     * bisa disisipi dengan aman (misalnya `rules: [...]` satu baris).
     */
    fun add(text: String): String? {
        val lines = remove(text).lines().toMutableList()

        while (lines.isNotEmpty() && lines.last().isBlank()) {
            lines.removeAt(lines.size - 1)
        }

        if (!insertProvider(lines)) return null
        if (!insertRule(lines)) return null

        lines.add("")

        return lines.joinToString("\n")
    }

    private fun insertProvider(lines: MutableList<String>): Boolean {
        val index = findKey(lines, "rule-providers")

        if (index == KEY_UNSUPPORTED) return false

        if (index == KEY_MISSING) {
            // bagian belum ada: buat lengkap dengan kuncinya di akhir file
            lines.add(BEGIN)
            lines.add("rule-providers:")
            lines.addAll(providerLinesAt("  ").drop(1).dropLast(1))
            lines.add(END)

            return true
        }

        var indent = childIndent(lines, index)

        // anak mapping harus lebih menjorok dari kuncinya
        if (indent.isEmpty()) indent = "  "

        lines.addAll(index + 1, providerLinesAt(indent))

        return true
    }

    /** Blok provider di bawah `rule-providers:` yang sudah ada; [indent] = indentasi nama provider. */
    private fun providerLinesAt(indent: String): List<String> {
        val field = "$indent  "

        return listOf(
            "$indent$BEGIN",
            "$indent$PROVIDER_NAME:",
            "${field}type: http",
            "${field}behavior: domain",
            "${field}url: \"$PROVIDER_URL\"",
            "${field}path: ./$PROVIDER_PATH",
            "${field}interval: 86400",
            "$indent$END",
        )
    }

    private fun insertRule(lines: MutableList<String>): Boolean {
        val index = findKey(lines, "rules")

        if (index == KEY_UNSUPPORTED) return false

        if (index == KEY_MISSING) {
            lines.add(BEGIN)
            lines.add("rules:")
            lines.add("  - RULE-SET,$PROVIDER_NAME,REJECT")
            lines.add(END)

            return true
        }

        // daftar boleh ditulis sejajar dengan kuncinya ("- ..." tanpa indentasi), jadi indentasi kosong sah
        val indent = childIndent(lines, index, allowZeroForList = true)

        lines.addAll(
            index + 1,
            listOf(
                "$indent$BEGIN",
                "$indent- RULE-SET,$PROVIDER_NAME,REJECT",
                "$indent$END",
            )
        )

        return true
    }

    private const val KEY_MISSING = -1
    private const val KEY_UNSUPPORTED = -2

    /** Cari kunci tingkat atas bergaya blok. Mengembalikan indeks barisnya, [KEY_MISSING], atau [KEY_UNSUPPORTED]. */
    private fun findKey(lines: List<String>, key: String): Int {
        val prefix = "$key:"
        val index = lines.indexOfFirst { it.startsWith(prefix) }

        if (index < 0) return KEY_MISSING

        val rest = lines[index].substring(prefix.length).trim()

        // "rules: []" atau "rule-providers: {}" tidak bisa disisipi per baris
        if (rest.isNotEmpty() && !rest.startsWith("#")) return KEY_UNSUPPORTED

        return index
    }

    /** Indentasi anak pertama dari kunci pada [keyIndex]; "  " bila bagian itu kosong. */
    private fun childIndent(
        lines: List<String>,
        keyIndex: Int,
        allowZeroForList: Boolean = false,
    ): String {
        for (i in keyIndex + 1 until lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val indent = line.takeWhile { it == ' ' || it == '\t' }

            if (indent.isNotEmpty()) return indent

            // tanpa indentasi: daftar "- x" sejajar dengan kunci, atau kunci tingkat atas berikutnya
            return if (allowZeroForList && trimmed.startsWith("-")) "" else "  "
        }

        return "  "
    }
}
