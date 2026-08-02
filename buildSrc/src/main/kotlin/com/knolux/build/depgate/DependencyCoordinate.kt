package com.knolux.build.depgate

/**
 * 依賴座標（不含版本），例如 `io.lettuce:lettuce-core`。
 *
 * [toString] 的輸出同時是基準線檔的鍵、核准檔 `coordinate` 欄位的值、以及報告中的
 * 顯示文字。因此 [toString] 與 [parse] **必須互為反函數**——一旦兩者不一致，
 * 核准就會對不上 delta，形成「看起來有核准、實際上擋不住」的失效模式。
 */
data class DependencyCoordinate(
    val group: String,
    val artifact: String,
) : Comparable<DependencyCoordinate> {

    override fun toString(): String = "$group:$artifact"

    override fun compareTo(other: DependencyCoordinate): Int =
        compareValuesBy(this, other, { it.group }, { it.artifact })

    companion object {

        private const val EXPECTED_FORMAT = "group:artifact"

        /**
         * 由 `group:artifact` 字串解析座標，前後空白會被去除。
         *
         * 格式錯誤時拋出 [IllegalArgumentException]。這裡刻意與 [ArtifactVersion.parse]
         * 的 sealed `ParseResult` 做法不同：無法解析的**版本**是要被記進報告的一種
         * 差異（FR-009），而無法解析的**座標**代表基準線檔或核准檔的格式壞了，
         * 除了修檔案沒有第二條路——靜默略過會讓打錯字的核准變成「沒有核准」，
         * 而維護者仍以為擋得住的東西已被放行。
         */
        fun parse(raw: String): DependencyCoordinate {
            val segments = raw.split(':')
            require(segments.size == 2) {
                "依賴座標『$raw』格式不符，預期為 `$EXPECTED_FORMAT`（剛好一個冒號）"
            }

            val group = segments[0].trim()
            val artifact = segments[1].trim()
            require(group.isNotEmpty() && artifact.isNotEmpty()) {
                "依賴座標『$raw』的 group 與 artifact 皆不得為空，預期為 `$EXPECTED_FORMAT`"
            }

            return DependencyCoordinate(group, artifact)
        }
    }
}
