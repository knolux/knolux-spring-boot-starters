package com.knolux.build.depgate

/**
 * 依賴圖走訪後得到的單一元件（data-model.md §4）。
 *
 * 刻意不直接使用 Gradle 的 `ResolvedComponentResult`：那個型別綁死了 Gradle API，
 * 會讓「哪些依賴該收進基準線」這個**領域決策**變得無法單元測試。走訪 Gradle 圖是
 * [DependencyGraphReader] 的職責，判斷則留在 [DependencySetBuilder]。
 *
 * @property isProject 是否為同 repo 的模組（Gradle 的 project component）
 */
data class ResolvedEntry(
    val group: String,
    val artifact: String,
    val version: String,
    val isProject: Boolean,
)

/**
 * 把解析出來的元件收斂成「會外溢給下游消費者的依賴集合」。
 *
 * 純函式：不碰 Gradle、不碰檔案系統。這一層決定了基準線檔的內容，而基準線一旦
 * 收錯東西，往後每一次比對都建立在錯的前提上——因此它必須能被一張表寫完測試（憲章 I）。
 */
object DependencySetBuilder {

    /**
     * @throws IllegalStateException 同座標出現不同版本，或欄位為空
     */
    fun build(moduleName: String, entries: Collection<ResolvedEntry>): DependencySet {
        val collected = sortedMapOf<DependencyCoordinate, String>()

        entries.asSequence()
            // 兄弟模組會與本模組一起發版，不是「外溢的第三方依賴」；
            // 收進來只會讓基準線隨自家版號跳動而產生整檔變更。
            .filterNot { it.isProject }
            .forEach { entry ->
                val coordinate = entry.toCoordinate(moduleName)
                val version = entry.version.trim()
                check(version.isNotEmpty()) {
                    "模組 $moduleName 的依賴 $coordinate 解析不出版本；" +
                        "寫入空版本會讓下次讀取基準線時才炸開，屆時已離成因很遠"
                }

                // 依賴圖是 DAG，同一元件可由多條路徑抵達——重複是正常的，版本不一致才是問題。
                val previous = collected.put(coordinate, version)
                check(previous == null || previous == version) {
                    "模組 $moduleName 的依賴 $coordinate 在解析結果中出現兩個版本（$previous 與 $version）。" +
                        "Gradle 衝突解析後理應唯一，代表本工具對解析結果的假設有誤；" +
                        "靜默取其一會讓基準線在兩次執行間跳動而無人察覺"
                }
            }

        return DependencySet(moduleName, collected)
    }

    private fun ResolvedEntry.toCoordinate(moduleName: String): DependencyCoordinate {
        check(group.isNotBlank() && artifact.isNotBlank()) {
            "模組 $moduleName 的解析結果含有不完整的座標（group=『$group』、artifact=『$artifact』）"
        }
        return DependencyCoordinate(group.trim(), artifact.trim())
    }
}
