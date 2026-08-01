package com.knolux.build.depgate

/**
 * Console 輸出（contracts/report-format.md §3）。
 *
 * 與 [ReportRenderer] 分開的理由：兩者的讀者不同。檔案報告是給坐下來讀的人看的；
 * console 是給「在 CI log 裡快速掃過」的人看的，所以要對齊、要短、要能直接照做。
 *
 * 失敗時 console **必須**含完整的阻擋清單，MUST NOT 只寫「請見報告檔」——
 * CI 上點開 artifact 的成本高到讓人選擇忽略，而被忽略的閘門等於不存在的閘門。
 */
object ConsoleRenderer {

    private const val APPROVALS_PATH = "gradle/dependency-approvals.toml"
    private const val REMOVED_CELL = "（已移除）"

    /** 阻擋時的完整摘要。 */
    fun renderBlockedSummary(report: GateReport, reportPath: String): String {
        val blocked = report.blockedDeltas
        // 類別標籤等寬，讓多筆項目的模組名落在同一欄——掃視時眼睛不必左右來回
        val labelWidth = blocked.maxOfOrNull { it.kind.name.length + 2 } ?: 0

        return buildString {
            appendLine("依賴相容性閘門：❌ 阻擋（${blocked.size} 項未核准的破壞性變更）")
            appendLine()
            blocked.forEach { delta ->
                val label = "[${delta.kind.name}]".padEnd(labelWidth)
                appendLine("  $label ${delta.moduleName}  ${delta.coordinate}")
                appendLine("  ${" ".repeat(labelWidth)} ${delta.from} -> ${delta.to ?: REMOVED_CELL}")
            }
            appendLine()
            appendLine("  完整報告：$reportPath")
            append("  核准方式：於 $APPROVALS_PATH 逐筆加入 [[approval]]")
        }
    }

    /**
     * 簽入的基準線與當前解析結果不一致（research.md R2 機制 M1）。
     *
     * 這種情況下閘門的比較對象本身就是錯的，因此必須先失敗；訊息要能讓人直接照做，
     * 而不是先去讀文件才知道要跑哪一個任務。
     */
    fun renderStaleBaseline(moduleName: String, deltas: List<DependencyDelta>, baselinePath: String): String =
        buildString {
            appendLine("模組 $moduleName 的基準線檔已過期：$baselinePath")
            appendLine("與當前 runtimeClasspath 解析結果有 ${deltas.size} 處不一致：")
            appendLine()
            deltas.forEach { delta ->
                delta.from?.let { appendLine("  - ${delta.coordinate}:$it") }
                delta.to?.let { appendLine("  + ${delta.coordinate}:$it") }
            }
            appendLine()
            append("請執行 ./gradlew updateDependencyBaseline 重新產生後一併提交。")
        }
}
