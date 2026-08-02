package com.knolux.build.depgate

/**
 * 一次閘門執行的完整結果（data-model.md §8）。
 *
 * **FR-012（不得遇到第一項就中止）**：所有模組、所有 delta 一次算完才決定成敗。
 * 任務的 `@TaskAction` 只在**最後**依 [blocked] 拋出 `GradleException`。
 *
 * **FR-019（成敗皆須產出報告）**：寫檔動作必須在拋例外**之前**完成。此順序與專案既有
 * 的資源管理慣例同源——`KnoluxS3ClientFactory` 也是先把 HTTP client 入快取再 build，
 * 確保失敗路徑仍留得下可用的產物。
 *
 * @param comparisonBase 比較基準的人類可讀描述（例如 `origin/dev` 或
 *   `knolux-redis-spring-boot-starter/v1.3.0`）。data-model.md §8 未列此欄位，但
 *   report-format.md 的報告標頭需要它——一份沒寫明「跟什麼比」的差異報告無法被驗證。
 * @param staleApprovals 本輪未配對到任何差異的核准（data-model.md §6）。純資訊性，
 *   不影響 [blocked]——過期核准代表「該清理了」，而不是「有問題」。
 */
data class GateReport(
    val comparisonBase: String,
    val verdicts: List<GateVerdict>,
    val staleApprovals: List<Approval> = emptyList(),
) {
    /** 任一模組被阻擋，整體即為阻擋。 */
    val blocked: Boolean get() = verdicts.any { it.status == GateStatus.BLOCKED }

    /** 跨模組的全部未核准阻擋項，順序與 [verdicts] 一致。 */
    val blockedDeltas: List<DependencyDelta> get() = verdicts.flatMap { it.blockedDeltas }

    /** 本輪實際算過差異的模組（跳過者不計）——過期核准的判定範圍。 */
    val evaluatedModules: Set<String>
        get() = verdicts.filterNot { it.status == GateStatus.SKIPPED_NO_BASELINE }
            .map { it.moduleName }
            .toSet()

    companion object {

        /**
         * 建立報告並一併算出過期核准。
         *
         * 過期判定要同時知道「本輪全部差異」與「哪些模組真的被評估過」，兩者都只在
         * 蒐集完 [verdicts] 之後才成立。放在這裡而非任務的 `@TaskAction`，是為了讓它
         * 留在可單元測試的範圍內（憲章 III：composition root 不放判斷邏輯）。
         */
        fun of(comparisonBase: String, verdicts: List<GateVerdict>, approvals: ApprovalMatcher): GateReport {
            val report = GateReport(comparisonBase, verdicts)
            return report.copy(
                staleApprovals = approvals.staleApprovals(
                    deltas = verdicts.flatMap { it.deltas },
                    evaluatedModules = report.evaluatedModules,
                ),
            )
        }
    }
}
