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
 */
data class GateReport(
    val comparisonBase: String,
    val verdicts: List<GateVerdict>,
) {
    /** 任一模組被阻擋，整體即為阻擋。 */
    val blocked: Boolean get() = verdicts.any { it.status == GateStatus.BLOCKED }

    /** 跨模組的全部未核准阻擋項，順序與 [verdicts] 一致。 */
    val blockedDeltas: List<DependencyDelta> get() = verdicts.flatMap { it.blockedDeltas }
}
