package com.knolux.build.depgate

/**
 * 自 tag 清單挑出某模組最新的發布 tag（FR-004）。
 *
 * 本專案的發布 tag 為 module-scoped 格式 `<模組名>/v<版本>`，例如
 * `knolux-redis-spring-boot-starter/v1.4.0`。發版報告要回答的是「自上一個發布版本以來
 * 改了什麼」，因此必須先決定「上一個發布版本」是哪一個。
 *
 * **排序 MUST 依語意化版本，MUST NOT 用字典序**：字典序會把 `v1.10.0` 排在 `v1.9.0`
 * 之前，於是報告會拿一個更舊的版本當基準，把早已揭露過的變更再列一次。
 * 報告一旦開始講廢話，就沒有人會再讀它——而這份報告存在的唯一理由就是被讀。
 *
 * 純邏輯、無 I/O：tag 清單由呼叫端（`git tag --list`）傳入，因此可完整單元測試。
 * 此型別未列於 data-model.md，是實作 FR-004 時補上的元件，歸屬與 [DeltaCalculator] 同層。
 */
object ReleaseTagSelector {

    /** 模組名與版本之間的分隔：`/v`。含 `/` 是刻意的，見 [tagPrefix]。 */
    private const val VERSION_PREFIX = "/v"

    /**
     * 挑出 [moduleName] 在 [tags] 中版本最高的發布 tag。
     *
     * 規則：
     * - 只看前綴為 `<moduleName>/v` 者；其他模組、非 `v` 開頭的 tag 一律排除。
     * - 版本比較用 [ArtifactVersion]，即語意化的數字段比較。
     * - 數字段相同時，**無 qualifier 者勝出**（`v1.4.0` 勝過 `v1.4.0-rc1`）。
     *   [ArtifactVersion.compareTo] 刻意忽略 qualifier，若不補這條規則，結果會取決於
     *   `git tag` 的輸出順序，同一份 repo 在不同機器上會產出不同基準的報告。
     * - 版本號解析不了的 tag 排除，但列入 [ReleaseTagLookup.ignoredTags]，
     *   由呼叫端 WARN 揭露。靜默丟棄會讓一個打錯字的 tag 看起來像「這個模組從未發布過」。
     *
     * @param tags 完整 tag 名稱清單，順序不拘
     */
    fun select(moduleName: String, tags: List<String>): ReleaseTagLookup {
        val prefix = tagPrefix(moduleName)
        val candidates = tags.filter { it.startsWith(prefix) }

        val ignored = mutableListOf<String>()
        val parsed = candidates.mapNotNull { tag ->
            when (val result = ArtifactVersion.parse(tag.removePrefix(prefix))) {
                is ArtifactVersion.ParseResult.Parsed -> tag to result.version
                is ArtifactVersion.ParseResult.Unparseable -> null.also { ignored += tag }
            }
        }

        val latest = parsed.maxWithOrNull(
            compareBy<Pair<String, ArtifactVersion>> { (_, version) -> version }
                // qualifier 為 null 者排在後面（即勝出）：正式版優先於同號的預發布版
                .thenBy { (_, version) -> if (version.qualifier == null) 1 else 0 },
        ) ?: return ReleaseTagLookup.Missing(missingReason(moduleName, prefix, candidates.size), ignored)

        return ReleaseTagLookup.Found(latest.first, latest.second, ignored)
    }

    /**
     * 前綴 MUST 含 `/`。
     *
     * 只比 `startsWith(moduleName)` 的話，日後新增一個以現有模組名為前綴的模組
     * （例如 `knolux-redis-spring-boot-starter-reactive`），它的 tag 會悄悄成為
     * 別人的比較基準，而報告表面上看起來完全正常。
     */
    private fun tagPrefix(moduleName: String): String = "$moduleName$VERSION_PREFIX"

    /**
     * 「找不到」的理由要能區分兩種處境，因為補救方式完全不同：
     * 從未發布過（照常發第一版即可）vs. tag 命名壞了（要先修 tag）。
     */
    /**
     * 報告標頭用的比較基準描述（contracts/report-format.md §2）。
     *
     * 「（上一個發布版本）」這句是必要的：光看 ref 名稱，讀者無從分辨這份報告比的是
     * 「上次發布以來」還是「某個隨手指定的 ref」，而兩者對 CHANGELOG 的意義完全不同。
     *
     * 以 `--since=` 明確指定時同樣套用此描述：該選項的語意本來就是「比較起點＝上一個
     * 發布版本」，指定其他 ref 屬於使用者自行承擔的用法，不值得為它多一種標頭措辭。
     */
    fun describeBase(ref: String): String = "`$ref`（上一個發布版本）"

    private fun missingReason(moduleName: String, prefix: String, candidateCount: Int): String =
        if (candidateCount == 0) {
            "找不到 $moduleName 的發布 tag（預期格式 `$prefix<版本>`），視為首次發布，無可比較的基準線"
        } else {
            "$moduleName 共有 $candidateCount 個 `$prefix…` tag，但版本號全數無法解析；請檢查 tag 命名"
        }
}

/**
 * [ReleaseTagSelector.select] 的結果。
 *
 * 與 [BaselineLookup] 同樣採 sealed 型別而非可空回傳值：「沒有上一個發布版本」是預期內的
 * 正常狀況（首次發布），但它必須帶著**理由**傳出去，報告才說得出為什麼沒有比較基準。
 */
sealed interface ReleaseTagLookup {

    /**
     * 因版本號無法解析而未納入挑選的 tag。
     *
     * 呼叫端 MUST 以 WARN 揭露：被略過的 tag 可能正是維護者以為會被拿來比較的那一個，
     * 而靜默略過的後果是報告用了更舊的基準卻沒有任何跡象。
     */
    val ignoredTags: List<String>

    /**
     * 找到上一個發布 tag。
     *
     * @property tag 完整 tag 名稱，可直接用於 `git show <tag>:<path>`
     * @property version 去掉 `v` 前綴後解析出的版本
     */
    data class Found(
        val tag: String,
        val version: ArtifactVersion,
        override val ignoredTags: List<String> = emptyList(),
    ) : ReleaseTagLookup {

        /** 給報告標頭用的描述，見 [ReleaseTagSelector.describeBase]。 */
        val describe: String get() = ReleaseTagSelector.describeBase(tag)
    }

    /**
     * 沒有可用的發布 tag。
     *
     * @property reason 原因與補救方向，會原文出現在報告中
     */
    data class Missing(
        val reason: String,
        override val ignoredTags: List<String> = emptyList(),
    ) : ReleaseTagLookup
}
