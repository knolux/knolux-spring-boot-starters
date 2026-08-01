package com.knolux.build.depgate

/** 單一模組的閘門結論（data-model.md §7）。 */
enum class GateStatus {
    /** 沒有阻擋性差異，或全部阻擋性差異都已核准。 */
    PASSED,

    /** 存在未核准的阻擋性差異——建置必須失敗。 */
    BLOCKED,

    /** 找不到可比對的基準線（FR-005）。不失敗，但必須記入報告。 */
    SKIPPED_NO_BASELINE,
}

/**
 * 單一模組的判定結果（data-model.md §7）。
 *
 * 「什麼情況算失敗」只在 [evaluate] 定義一次。刻意不讓 Gradle 任務自行判斷——
 * 判斷邏輯一旦寫進 `@TaskAction`，就再也無法被單元測試涵蓋，而這正是憲章
 * 「composition root 不放判斷邏輯」要避免的情形。
 */
data class GateVerdict(
    val moduleName: String,
    val status: GateStatus,
    val deltas: List<DependencyDelta>,
    val blockedDeltas: List<DependencyDelta>,
    val approvedDeltas: List<Pair<DependencyDelta, Approval>>,
    val skipReason: String?,
) {
    companion object {

        /**
         * 由差異清單推導判定結果。
         *
         * @param findApproval 對每筆阻擋性 delta 查詢核准紀錄；預設不查（US1 階段尚無核准途徑，
         *   US4 完成後由 `ApprovalMatcher` 接入）。
         */
        fun evaluate(
            moduleName: String,
            deltas: List<DependencyDelta>,
            findApproval: (DependencyDelta) -> Approval? = { null },
        ): GateVerdict {
            val blocked = mutableListOf<DependencyDelta>()
            val approved = mutableListOf<Pair<DependencyDelta, Approval>>()

            // 一次走完全部 delta 而非遇到第一筆就回傳（FR-012）：只回報第一項會讓維護者
            // 陷入「修一筆、重跑 CI、又冒一筆」的迴圈，一次升級可能要來回好幾輪。
            deltas.filter { it.blocking }.forEach { delta ->
                when (val approval = findApproval(delta)) {
                    null -> blocked += delta
                    else -> approved += delta to approval
                }
            }

            return GateVerdict(
                moduleName = moduleName,
                status = if (blocked.isEmpty()) GateStatus.PASSED else GateStatus.BLOCKED,
                deltas = deltas,
                blockedDeltas = blocked,
                approvedDeltas = approved,
                skipReason = null,
            )
        }

        /**
         * 建立「無基準線可比」的判定（FR-005）。
         *
         * 新模組首次納入閘門、或閘門導入前的舊 tag，本來就沒有基準線檔，這不是錯誤。
         * 但 [reason] 為必填——靜默跳過等同「這個模組沒被保護，而沒有人知道」。
         */
        fun skipped(moduleName: String, reason: String): GateVerdict {
            require(reason.isNotBlank()) { "模組 $moduleName 被跳過時必須說明原因，否則報告讀者無從得知它為何未受保護" }
            return GateVerdict(
                moduleName = moduleName,
                status = GateStatus.SKIPPED_NO_BASELINE,
                deltas = emptyList(),
                blockedDeltas = emptyList(),
                approvedDeltas = emptyList(),
                skipReason = reason,
            )
        }
    }
}
