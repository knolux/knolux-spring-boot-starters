package com.knolux.build.depgate

/**
 * 把核准清單套到差異上（data-model.md §6）。
 *
 * 純函式，不持有「已用過哪些核准」之類的狀態：過期判定改成由呼叫端把**整輪執行的
 * 全部差異**一次餵進 [staleApprovals]。這是刻意的取捨——若改用「find 時順手記錄用過的核准」，
 * 過期清單就會隨呼叫順序與呼叫次數而變，而它最終要驅動的是 `updateDependencyBaseline`
 * 的刪除行為（FR-017）；一個會依呼叫方式改變答案的函式，不該拿來決定刪什麼。
 *
 * 比對本身委派給 [Approval.matches]，此處不重新定義「什麼叫相符」。
 */
class ApprovalMatcher(private val approvals: List<Approval>) {

    /**
     * 找出正好核准 [delta] 的紀錄；沒有則回傳 null（代表這筆差異未獲放行）。
     *
     * 多筆相符時取第一筆——五個欄位全等的核准本來就是重複項，取哪一筆結果相同。
     */
    fun find(delta: DependencyDelta): Approval? = approvals.firstOrNull { it.matches(delta) }

    /**
     * 找出檔案中存在、但 [deltas] 裡沒有任何一筆與之相符的核准（過期核准）。
     *
     * 不影響閘門判定，只列入報告的資訊性區塊並供 `updateDependencyBaseline` 清除（FR-017）。
     *
     * 兩項前提缺一不可，因為此函式的結論最終會驅動**刪除**行為：
     * - **[deltas] 必須涵蓋 [evaluatedModules] 的所有差異**；少餵一個模組，
     *   該模組正在生效的核准會被誤判為過期而遭清除。
     * - **[evaluatedModules] 只列本輪真的算過差異的模組**；模組因無基準線被跳過時
     *   根本沒有 delta 可配，把它的核准當成過期只是同義反覆。
     *
     * 兩者任一出錯，後果都是「核准被刪掉，而那筆破壞性變更下次無聲通過」。
     */
    fun staleApprovals(
        deltas: Collection<DependencyDelta>,
        evaluatedModules: Set<String>,
    ): List<Approval> = approvals
        .filter { it.module in evaluatedModules }
        .filterNot { approval -> deltas.any(approval::matches) }

    companion object {

        /** 「沒有任何核准」的中性元。具名是為了讓呼叫端寫得出意圖，而不是省略參數。 */
        val NONE = ApprovalMatcher(emptyList())
    }
}
