package com.knolux.build.depgate

/**
 * 單一依賴在基準線與當前解析結果之間的差異（data-model.md §5）。
 *
 * [from] / [to] 存**版本原字串**而非 [ArtifactVersion]：報告與核准比對都要求逐字相符，
 * 而 `UNPARSEABLE` 這一類根本不存在解析後的版本可存。
 *
 * 不變條件在 `init` 就強制檢查，而不是留給呼叫端自律。一個 `from` 與 `to` 同時為 null
 * 的 delta 會在報告中呈現成「從 null 升到 null」，看起來像雜訊而非缺陷，
 * 很可能被當成顯示問題而長期忽略。
 *
 * @property moduleName 模組歸屬（FR-013：報告須指出是哪個模組）
 * @property from 基準線版本原字串；[DeltaKind.ADDED] 時為 null
 * @property to 當前版本原字串；[DeltaKind.REMOVED] 時為 null
 * @property unparseableReason 僅 [DeltaKind.UNPARSEABLE] 時填寫，且**必填**
 */
data class DependencyDelta(
    val moduleName: String,
    val coordinate: DependencyCoordinate,
    val from: String?,
    val to: String?,
    val kind: DeltaKind,
    val unparseableReason: String? = null,
) {

    init {
        when (kind) {
            DeltaKind.ADDED -> {
                require(from == null && to != null) {
                    "$coordinate 標為 ADDED，from 必須為 null 且 to 必須存在（實際 from=$from、to=$to）"
                }
            }

            DeltaKind.REMOVED -> {
                require(from != null && to == null) {
                    "$coordinate 標為 REMOVED，from 必須存在且 to 必須為 null（實際 from=$from、to=$to）"
                }
            }

            else -> {
                require(from != null && to != null) {
                    "$coordinate 標為 $kind，from 與 to 皆須存在（實際 from=$from、to=$to）"
                }
            }
        }

        if (kind == DeltaKind.UNPARSEABLE) {
            require(!unparseableReason.isNullOrBlank()) {
                "$coordinate 標為 UNPARSEABLE 但未說明理由；缺了理由，維護者只會看到「被擋下來」" +
                    "卻不知道該修版本字串還是該核准"
            }
        } else {
            require(unparseableReason == null) {
                "$coordinate 標為 $kind 卻帶有 unparseableReason『$unparseableReason』；" +
                    "此欄位僅 UNPARSEABLE 適用"
            }
        }
    }

    /** 是否會讓閘門失敗（尚未計入核准）。委派給 [DeltaKind.blocking]，不自行判斷。 */
    val blocking: Boolean get() = kind.blocking
}
