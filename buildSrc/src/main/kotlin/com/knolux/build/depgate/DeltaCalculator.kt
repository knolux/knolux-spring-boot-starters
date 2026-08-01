package com.knolux.build.depgate

/**
 * 比較兩個 [DependencySet]，產出全部差異。
 *
 * 純函式：不知道 git、不知道 Gradle、不做任何 I/O。整個閘門最需要被信任的判斷
 * 都集中在這裡，因此它必須是能用一張表寫完測試的東西（憲章 I）。
 */
object DeltaCalculator {

    /**
     * 計算 [baseline] 到 [current] 的全部差異，依座標排序。
     *
     * **一次算完全部**，不在遇到第一筆阻擋性變更時中止（FR-012）：只回報第一筆會讓
     * 維護者陷入「修一筆、重跑 CI、又冒出一筆」的迴圈，而 CI 一輪要數分鐘。
     *
     * 排序是硬性要求而非美觀考量——報告會被貼進 CHANGELOG，順序浮動會讓
     * 兩次執行的輸出無從比對。
     */
    fun calculate(baseline: DependencySet, current: DependencySet): List<DependencyDelta> {
        require(baseline.moduleName == current.moduleName) {
            "只能比較同一模組的依賴集合，實際為『${baseline.moduleName}』與『${current.moduleName}』"
        }

        val moduleName = baseline.moduleName
        val coordinates = (baseline.entries.keys + current.entries.keys).sorted()

        return coordinates.mapNotNull { coordinate ->
            val from = baseline.entries[coordinate]
            val to = current.entries[coordinate]

            when {
                from == null && to != null ->
                    DependencyDelta(moduleName, coordinate, from = null, to = to, kind = DeltaKind.ADDED)

                from != null && to == null ->
                    DependencyDelta(moduleName, coordinate, from = from, to = null, kind = DeltaKind.REMOVED)

                from == to -> null // 版本字串完全相同：無變動，不產生噪音

                else -> classifyVersionChange(moduleName, coordinate, from!!, to!!)
            }
        }
    }

    /**
     * 判定版本變動的類別。
     *
     * 判斷順序有意義：**降版先於升版**。`3.0.0 → 2.9.9` 若先看 major 段不變、
     * minor 段沒前進，就會落到 `PATCH` 這種資訊性類別而被放行——但基準線曾提供的
     * API 可能已經消失，風險其實等同 major 跳動。
     */
    private fun classifyVersionChange(
        moduleName: String,
        coordinate: DependencyCoordinate,
        from: String,
        to: String,
    ): DependencyDelta {
        val fromParsed = ArtifactVersion.parse(from)
        val toParsed = ArtifactVersion.parse(to)

        val unparseableReason = listOfNotNull(
            (fromParsed as? ArtifactVersion.ParseResult.Unparseable)?.reason,
            (toParsed as? ArtifactVersion.ParseResult.Unparseable)?.reason,
        ).joinToString("；")

        if (unparseableReason.isNotEmpty()) {
            // 無法解析代表「無從判斷是否為破壞性變更」，而那與「沒有問題」是兩件事。
            return DependencyDelta(
                moduleName = moduleName,
                coordinate = coordinate,
                from = from,
                to = to,
                kind = DeltaKind.UNPARSEABLE,
                unparseableReason = unparseableReason,
            )
        }

        val before = (fromParsed as ArtifactVersion.ParseResult.Parsed).version
        val after = (toParsed as ArtifactVersion.ParseResult.Parsed).version

        val kind = when {
            after < before -> DeltaKind.DOWNGRADE
            after.major > before.major -> DeltaKind.MAJOR
            // FR-007a：SemVer 對 0.x 不保證相容，0.5 → 0.6 的破壞性等同 1.x → 2.x
            before.major == 0 && after.minor > before.minor -> DeltaKind.MAJOR
            after.minor > before.minor -> DeltaKind.MINOR
            // 數字段完全相同時只可能是 qualifier 變動；數字沒跨越相容性邊界，歸為 PATCH
            else -> DeltaKind.PATCH
        }

        return DependencyDelta(moduleName, coordinate, from = from, to = to, kind = kind)
    }
}
