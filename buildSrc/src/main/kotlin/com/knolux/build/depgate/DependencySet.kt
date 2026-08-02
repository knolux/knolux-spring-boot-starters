package com.knolux.build.depgate

/**
 * 單一模組會外溢給下游消費者的依賴集合。
 *
 * [entries] 存的是**版本原字串**而非 [ArtifactVersion]，因為無法解析的版本
 * 同樣必須被完整記錄與呈現（FR-009）；解析只發生在比較差異的那一刻。
 *
 * 序列化（[serialize]）必須是決定性的：固定排序、固定 LF 換行、檔尾固定單一換行。
 * R2 之所以選擇把基準線簽入版控，靠的就是「基準線變動直接出現在 PR diff 供人審閱」；
 * 只要輸出順序或換行不穩定，每次更新都會產生整檔 diff，人便會開始無視它。
 *
 * @property moduleName 模組名，例如 `knolux-redis-spring-boot-starter`
 * @property entries 座標 → 版本原字串
 */
data class DependencySet(
    val moduleName: String,
    val entries: Map<DependencyCoordinate, String>,
) {

    /**
     * 輸出為基準線檔內容（contracts/file-formats.md §1）。
     *
     * 換行一律 LF：本 repo 於 Windows 開發、CI 於 Linux，交給平台決定會導致整檔 diff。
     */
    fun serialize(): String {
        val lines = HEADER_LINES + entries.map { (coordinate, version) -> "$coordinate:$version" }.sorted()
        return lines.joinToString(LF, postfix = LF)
    }

    companion object {

        private const val LF = "\n"
        private const val COMMENT_PREFIX = "#"

        private val HEADER_LINES = listOf(
            "# 由 ./gradlew updateDependencyBaseline 產生，請勿手動編輯。",
            "# 此檔記錄本模組會外溢給下游消費者的依賴集合（runtimeClasspath 解析結果）。",
        )

        /**
         * 由基準線檔內容還原集合。`#` 開頭的行與空行一律忽略。
         *
         * 任何一行格式不符即拋出 [IllegalArgumentException]，訊息帶出模組名、行號與該行
         * 原內容。不容許靜默跳過壞行——那會讓一筆被寫壞的基準線悄悄變成「這個依賴
         * 從來不存在」，接著在下次比對時被誤判為 `ADDED`（資訊性、不阻擋），
         * 真正的破壞性變更就此漏網。
         */
        fun parse(moduleName: String, content: String): DependencySet {
            val entries = LinkedHashMap<DependencyCoordinate, String>()

            content.lines().forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith(COMMENT_PREFIX)) return@forEachIndexed

                val lineNumber = index + 1
                val segments = line.split(':')
                require(segments.size == 3 && segments.none { it.isBlank() }) {
                    "模組 $moduleName 的基準線檔第 $lineNumber 行格式不符：『$line』，預期為 `group:artifact:version`"
                }

                val coordinate = DependencyCoordinate(segments[0].trim(), segments[1].trim())
                val previous = entries.put(coordinate, segments[2].trim())
                require(previous == null) {
                    "模組 $moduleName 的基準線檔第 $lineNumber 行出現重複座標『$coordinate』" +
                        "（先前為 $previous，本行為 ${segments[2].trim()}）；請以 ./gradlew updateDependencyBaseline 重新產生"
                }
            }

            return DependencySet(moduleName, entries)
        }
    }
}
