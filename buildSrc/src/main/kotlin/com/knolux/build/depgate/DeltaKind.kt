package com.knolux.build.depgate

/**
 * 依賴差異的類別（data-model.md §4）。
 *
 * [blocking] 是**類別自身的固有屬性**，MUST NOT 由呼叫端各自判斷。閘門任務、
 * 報告產生、核准比對三處都會問「這筆會不會擋建置」；規則一旦散落，遲早出現
 * 「報告說會擋、任務卻放行」的分歧，而這種分歧的方向永遠是漏放而非誤擋。
 *
 * @property blocking true 代表會讓 `checkDependencyCompatibility` 失敗（除非有對應核准）
 * @property displayName 報告中呈現的中文標題
 */
enum class DeltaKind(val blocking: Boolean, val displayName: String) {

    /** major 段前進；或 0.x 版本線內的 minor 段前進（research.md R5、FR-007a）。 */
    MAJOR(blocking = true, displayName = "major 版本跳動"),

    /** minor 段前進（1.x 以上）。資訊性——擋住每週的 Dependabot PR 只會讓人關掉閘門。 */
    MINOR(blocking = false, displayName = "minor 版本更新"),

    /** patch 段或僅 qualifier 變動。 */
    PATCH(blocking = false, displayName = "patch 版本更新"),

    /** 基準線無、當前有。FR-011 明訂不阻擋：新增依賴不會讓既有下游程式碼編不過。 */
    ADDED(blocking = false, displayName = "新增依賴"),

    /**
     * 基準線有、當前無。
     *
     * 2026-08-01 的 Netty 4.1.x 整線消失即為此類：下游若直接 import 了該依賴的型別，
     * 升級後會在編譯期或執行期直接壞掉，且錯誤訊息完全指不到本專案。
     */
    REMOVED(blocking = true, displayName = "依賴移除"),

    /**
     * 版本後退。基準線曾提供的 API 可能消失，風險等同 major 跳動。
     *
     * 未在 spec 中列出，但 BOM 調整導致版本後退是真實可能；歸為阻擋性以維持
     * 「零漏報」的假設（data-model.md §4 已列入對 spec 的回饋）。
     */
    DOWNGRADE(blocking = true, displayName = "版本後退"),

    /**
     * 任一側版本字串無法解析（FR-009）。
     *
     * 阻擋而非略過：無法解析代表**無從判斷**是否為破壞性變更，
     * 而「無從判斷」與「沒有問題」是兩件事。靜默放行正是本功能要根除的模式。
     */
    UNPARSEABLE(blocking = true, displayName = "版本無法解析"),
    ;

    companion object {

        /** 可被核准的類別＝阻擋性的類別（contracts/file-formats.md §2）。 */
        val APPROVABLE: Set<DeltaKind> = entries.filter { it.blocking }.toSet()

        /**
         * 由核准檔的 `kind` 欄位值解析。
         *
         * 值不合法（打錯字、或填了 `PATCH` 這類資訊性類別）時 fail-fast 並列出
         * 全部合法值——靜默忽略會讓打錯字的核准變成「沒有核准」，
         * 而維護者仍以為擋得住的東西已被放行。
         */
        fun parseApprovable(raw: String): DeltaKind {
            val kind = entries.firstOrNull { it.name == raw.trim() }
            require(kind != null && kind in APPROVABLE) {
                "核准項的 kind『$raw』不合法，可用值為 ${APPROVABLE.joinToString(" / ") { it.name }}" +
                    "（資訊性類別本來就不會阻擋建置，無需核准）"
            }
            return kind
        }
    }
}
