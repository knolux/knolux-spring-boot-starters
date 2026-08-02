package com.knolux.build.depgate

/**
 * 已解析的依賴版本號。
 *
 * 只保留判斷「是否為破壞性變更」所需的最小資訊：三個數字段與 qualifier。
 * [raw] 永遠保存原始字串，因為基準線檔與差異報告都必須呈現與 POM 完全一致的
 * 版本字串（`7.5.2.RELEASE` 不能被正規化成 `7.5.2`，否則基準線再也對不上）。
 *
 * 比較（[compareTo]）**只看數字段、忽略 qualifier**。這是刻意的：qualifier 的
 * 排序規則（`M1` < `RC1` < `RELEASE`？）各家 library 並不一致，猜錯會讓閘門
 * 產生假警報。本功能只需要「數字有沒有往回走」來偵測降版，qualifier 的差異
 * 一律歸類為 PATCH 等級的變動。
 *
 * @see ArtifactVersion.parse
 */
data class ArtifactVersion(
    /** 原始版本字串，未經任何正規化。 */
    val raw: String,
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** 數字段之後的剩餘部分，例如 `RELEASE`、`Final`、`SNAPSHOT`；無則為 null。 */
    val qualifier: String?,
) : Comparable<ArtifactVersion> {

    override fun compareTo(other: ArtifactVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = raw

    /**
     * [parse] 的結果。
     *
     * 刻意用 sealed interface 而非「回傳 null」或「拋例外」：
     * - 回傳 null 會讓呼叫端很容易寫成 `?: return`，把「看不懂的版本」靜默當成
     *   「沒有變動」——這正是本功能要根除的失效模式（FR-009）。
     * - 拋例外則會讓單一筆無法解析的依賴炸掉整份報告，其餘正常的破壞性變更
     *   反而看不到。
     *
     * sealed 型別強迫呼叫端在編譯期就面對 [Unparseable] 分支。
     */
    sealed interface ParseResult {

        /** 解析成功。 */
        data class Parsed(val version: ArtifactVersion) : ParseResult

        /**
         * 無法解析。[reason] MUST 帶出原始字串，否則維護者拿到報告後
         * 還得自己去翻依賴樹才知道是哪一筆出問題。
         */
        data class Unparseable(val raw: String, val reason: String) : ParseResult
    }

    companion object {

        /** 最多取三段數字作為 major / minor / patch，其餘併入 qualifier。 */
        private const val MAX_NUMERIC_SEGMENTS = 3

        /** `.` 與 `-` 都視為分段符：涵蓋 `7.5.2.RELEASE` 與 `1.0.0-SNAPSHOT` 兩種寫法。 */
        private val SEPARATOR = Regex("[.\\-]")

        /**
         * 解析版本字串。永遠回傳 [ParseResult]，不回傳 null、不拋例外。
         *
         * 規則：
         * - 由左往右取連續的數字段，最多三段；缺少的段補 0（`2.6` → `2.6.0`）。
         * - 剩餘段以 `.` 串接成 qualifier；無剩餘則為 null。
         * - 沒有任何前導數字段（`latest.release`、`+`、空字串）→ [ParseResult.Unparseable]。
         * - 數字段超出 [Int] 範圍 → [ParseResult.Unparseable]，而非默默截斷。
         */
        fun parse(raw: String): ParseResult {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                return ParseResult.Unparseable(raw, "版本字串為空白（原始值：『$raw』）")
            }

            val segments = trimmed.split(SEPARATOR)
            val numbers = mutableListOf<Int>()
            var index = 0
            while (index < segments.size && numbers.size < MAX_NUMERIC_SEGMENTS) {
                val segment = segments[index]
                if (!segment.isAsciiDigits()) break
                val value = segment.toIntOrNull()
                    ?: return ParseResult.Unparseable(
                        raw,
                        "版本段『$segment』超出可表示的整數範圍（原始值：『$raw』）",
                    )
                numbers += value
                index++
            }

            if (numbers.isEmpty()) {
                return ParseResult.Unparseable(
                    raw,
                    "找不到前導的數字版本段，無法判斷 major/minor/patch（原始值：『$raw』）",
                )
            }

            val qualifier = segments.drop(index)
                .joinToString(".")
                .takeIf { it.isNotEmpty() }

            return ParseResult.Parsed(
                ArtifactVersion(
                    raw = raw,
                    major = numbers[0],
                    minor = numbers.getOrElse(1) { 0 },
                    patch = numbers.getOrElse(2) { 0 },
                    qualifier = qualifier,
                ),
            )
        }

        /**
         * 刻意不用 [Char.isDigit]：它對 Unicode 數字（如全形、天城體）也回傳 true，
         * 但那些字元 [String.toIntOrNull] 的行為並不直觀。版本號只該是 ASCII 數字。
         */
        private fun String.isAsciiDigits(): Boolean =
            isNotEmpty() && all { it in '0'..'9' }
    }
}
