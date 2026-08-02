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
     * 已清除的過期核准（FR-017）。
     *
     * 這裡刪掉的是**維護者手寫的內容**，因此必須逐筆列出被刪的是什麼，而不是只報筆數：
     * 清除若刪錯，唯一還原得回來的時機就是維護者看見這段輸出的當下；
     * 只印「已清除 2 筆」等於要求對方事後翻 git diff 才知道發生了什麼事。
     */
    fun renderPurgedApprovals(removed: List<Approval>, approvalsPath: String): String = buildString {
        appendLine("已自 $approvalsPath 清除 ${removed.size} 筆過期核准（對應的差異已納入新基準線）：")
        appendLine()
        removed.forEach { approval ->
            appendLine("  [${approval.kind.name}] ${approval.module}  ${approval.coordinate}")
            appendLine("           ${approval.from} -> ${approval.to ?: REMOVED_CELL}")
            appendLine("           理由：${approval.reason}")
        }
        append("如有誤刪，請自 git diff 還原後回報——核准比對過鬆或過嚴都會讓閘門失去意義。")
    }

    /**
     * 簽入的基準線與當前解析結果不一致，且落差含阻擋性項目（research.md R2 機制 M1）。
     *
     * 訊息要能讓人直接照做，而不是先去讀文件才知道要跑哪一個任務。
     */
    fun renderStaleBaseline(staleness: BaselineStaleness, baselinePath: String): String = buildString {
        appendLine("模組 ${staleness.moduleName} 的基準線檔已過期：$baselinePath")
        append(staleBaselineBody(staleness))
        append("請執行 ./gradlew updateDependencyBaseline 重新產生後一併提交。")
    }

    /**
     * 落差全屬非阻擋性——依 FR-011 只警告不失敗。
     *
     * 警告很容易被無視，所以除了差異行與修正指令，還必須寫出「這次為何沒擋下來」與
     * 「不補會在哪裡爆」。少了這兩句，讀者學到的會是「這行黃字每週都出現，不用理」，
     * 等到發版前 `checkDependencyBaseline` 紅燈才回頭找原因——而那是最不該被擋住的時刻。
     */
    fun renderToleratedStaleBaseline(staleness: BaselineStaleness, baselinePath: String): String = buildString {
        appendLine("模組 ${staleness.moduleName} 的基準線檔尚未跟上：$baselinePath")
        append(staleBaselineBody(staleness))
        appendLine("以上皆非阻擋性變動，依 FR-011 不讓建置失敗；")
        append("但發版前的 checkDependencyBaseline 會逐字比對，屆時仍須執行 ./gradlew updateDependencyBaseline 補上。")
    }

    private fun staleBaselineBody(staleness: BaselineStaleness): String = buildString {
        appendLine("與當前 runtimeClasspath 解析結果有 ${staleness.deltas.size} 處不一致：")
        appendLine()
        staleness.deltas.forEach { delta ->
            delta.from?.let { appendLine("  - ${delta.coordinate}:$it") }
            delta.to?.let { appendLine("  + ${delta.coordinate}:$it") }
        }
        appendLine()
    }
}
