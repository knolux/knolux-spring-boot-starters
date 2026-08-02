package com.knolux.build.depgate

/**
 * 一筆「這次破壞性變更是刻意的」的書面紀錄（data-model.md §6、spec FR-014 ~ FR-017）。
 *
 * 五個識別欄位（[module]、[coordinate]、[from]、[to]、[kind]）在比對時必須**逐字全等**，
 * 刻意不支援萬用字元或版本區間：
 *
 * > 核准的是「這一次、這個依賴、從這一版到那一版」這個具體事實，不是一類事實。
 * > 版本一動核准即失效並需重新審視——這是 FR-015「不得是全域開關」的最強形式。
 *
 * [reason] 會原樣出現在報告與 CHANGELOG 的「升級前必讀」段落，因此必須寫給人看，
 * 而不是寫給閘門看（「已確認」「暫時放行」這類內容等同沒寫）。
 *
 * @param to 僅 [DeltaKind.REMOVED] 時省略——依賴消失後不存在「升到哪一版」
 */
data class Approval(
    val module: String,
    val coordinate: DependencyCoordinate,
    val from: String,
    val to: String?,
    val kind: DeltaKind,
    val reason: String,
) {
    init {
        require(kind in DeltaKind.APPROVABLE) {
            "核准項 $coordinate 的 kind 為 $kind，但資訊性類別本來就不會阻擋建置，無需核准" +
                "（可核准者：${DeltaKind.APPROVABLE.joinToString(" / ") { it.name }}）"
        }
        if (kind == DeltaKind.REMOVED) {
            require(to == null) { "核准項 $coordinate 標為 REMOVED，不應填寫 to（實際 to=$to）" }
        } else {
            require(to != null) { "核准項 $coordinate 標為 $kind，必須填寫 to 以指明核准的目標版本" }
        }
        require(reason.isNotBlank()) {
            "核准項 $coordinate 必須寫明理由；此文字會直接出現在報告與 Release notes 中"
        }
    }

    /**
     * 判斷本核准是否正好對應 [delta]。
     *
     * 比對放在 [Approval] 上而非呼叫端，是為了讓「什麼叫做相符」只有一個定義；
     * 一旦散落各處，日後放寬其中一處就會在其他路徑留下漏洞。
     */
    fun matches(delta: DependencyDelta): Boolean =
        module == delta.moduleName &&
            coordinate == delta.coordinate &&
            from == delta.from &&
            to == delta.to &&
            kind == delta.kind
}
