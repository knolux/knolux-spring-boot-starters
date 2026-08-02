package com.knolux.build.depgate.task

import com.knolux.build.depgate.ApprovalMatcher
import com.knolux.build.depgate.ApprovalStore
import com.knolux.build.depgate.BaselineLookup
import com.knolux.build.depgate.ConsoleRenderer
import com.knolux.build.depgate.DeltaCalculator
import com.knolux.build.depgate.DeltaKind
import com.knolux.build.depgate.DependencySet
import com.knolux.build.depgate.GateReport
import com.knolux.build.depgate.GateVerdict
import com.knolux.build.depgate.GitBaselineSource
import com.knolux.build.depgate.GitRefs
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import java.io.File

/**
 * 以當前 `runtimeClasspath` 解析結果重新產生各模組的基準線檔，並清除已過期的核准（FR-017）。
 *
 * **明確不做的事**：不執行任何 git 寫入操作、不 commit。變更留在工作區由維護者檢視後併入 PR——
 * 這正是 R2 選擇「簽入檔案」而非「執行期重建」的核心價值：基準線變動會直接出現在 PR diff
 * 上供人審閱。CI 不得執行此任務（憲章 Governance 明文禁止直接推送 `main`）。
 */
abstract class UpdateDependencyBaselineTask : DependencyGateTask() {

    /**
     * `--base=<git-ref>`；未指定時依序嘗試 [DEFAULT_BASES]。
     *
     * 與 `checkDependencyCompatibility` 同名同義並非巧合：核准是否過期，取決於閘門
     * 還會不會產生對應的差異。兩者若用了不同的比較基準，這裡就會刪掉那邊還在用的核准。
     */
    @get:Internal
    @get:Option(option = "base", description = "判定核准是否過期時的比較基準，預設依序嘗試 origin/dev、origin/main")
    abstract val base: Property<String>

    init {
        description = "以當前解析結果重新產生依賴基準線檔，並清除已過期的核准"
    }

    @TaskAction
    fun update() {
        baselineDir.get().asFile.mkdirs()

        val current = currentDependencySets()
        current.forEach { (moduleName, set) ->
            val file = baselineFile(moduleName)
            val previous = file.readPreviousOrNull(moduleName)

            // 一律 UTF-8 無 BOM；換行由 DependencySet.serialize() 固定為 LF。
            // 交給平台決定會讓 Windows 開發者每次都產生整檔 diff。
            file.writeText(set.serialize(), Charsets.UTF_8)

            logger.lifecycle("${file.relativeToRepo()}：${summarize(previous, set)}")
        }

        purgeStaleApprovals(current)
    }

    /**
     * 清除「已配不到任何差異」的核准（FR-017）。
     *
     * 判定刻意走 [GateReport.of] 這條與閘門完全相同的路徑，而非另外寫一套比對：
     * 「什麼會被擋」與「什麼會被刪」一旦由兩份程式碼各自決定，遲早會分歧，
     * 而分歧的後果是刪掉閘門那側還在用的核准——下一次 CI 才會發現，且原因難以追查。
     *
     * 比較對象是 **merge-base 當時的基準線**，不是剛剛寫下的那份：後者恆等於當前解析
     * 結果，據以判定會讓每一筆核准在寫檔的瞬間全部變成過期。
     */
    private fun purgeStaleApprovals(current: Map<String, DependencySet>) {
        val approvals = loadApprovals()
        if (approvals.isEmpty()) return

        // 無法解析比較基準時只略過清除，不讓整個任務失敗：基準線更新才是本任務的職責，
        // 為了次要的清潔工作而拒絕產出主要成果，會逼維護者去改 workflow 或手寫基準線。
        // 但必須留下 WARN——靜默不清除會讓維護者以為核准檔已經是最新狀態。
        val comparisonBase = runCatching {
            GitRefs(repoDir.get().asFile).resolveComparisonBase(explicit = base.orNull, defaults = DEFAULT_BASES)
        }.onFailure {
            logger.warn("略過過期核准清除：無法解析比較基準（${it.message}）。基準線已更新，核准檔未更動。")
        }.getOrNull() ?: return

        val source = GitBaselineSource(repoDir.get().asFile, comparisonBase.mergeBase, baselineDirPath())
        val verdicts = current.map { (moduleName, set) ->
            when (val historic = source.load(moduleName)) {
                is BaselineLookup.Missing -> GateVerdict.skipped(moduleName, historic.reason)
                is BaselineLookup.Found ->
                    GateVerdict.evaluate(moduleName, DeltaCalculator.calculate(historic.dependencySet, set))
            }
        }

        val report = GateReport.of(comparisonBase.describe, verdicts, ApprovalMatcher(approvals))
        val removed = ApprovalStore.prune(approvalsFile.get().asFile, report.staleApprovals)
        if (removed.isNotEmpty()) {
            logger.lifecycle(ConsoleRenderer.renderPurgedApprovals(removed, approvalsFile.get().asFile.relativeToRepo()))
        }
    }

    private fun summarize(previous: DependencySet?, current: DependencySet): String {
        if (previous == null) return "新建，共 ${current.entries.size} 筆"

        val deltas = DeltaCalculator.calculate(previous, current)
        if (deltas.isEmpty()) return "無變動，共 ${current.entries.size} 筆"

        val byKind = deltas.groupingBy { it.kind }.eachCount()
        val added = byKind[DeltaKind.ADDED] ?: 0
        val removed = byKind[DeltaKind.REMOVED] ?: 0
        val changed = deltas.size - added - removed

        return "共 ${current.entries.size} 筆（新增 $added、移除 $removed、版本變更 $changed）"
    }

    /**
     * 讀取既有內容以計算變動筆數。
     *
     * 檔案壞掉時**不**中止：這個任務的職責就是把檔案重新寫對，因為舊檔壞了而拒絕修復
     * 會讓維護者無路可走。但必須留下 WARN——靜默當成「新建」會讓一次意外的內容毀損
     * 看起來像正常的首次產生。
     */
    private fun File.readPreviousOrNull(moduleName: String): DependencySet? {
        if (!isFile) return null

        return runCatching { DependencySet.parse(moduleName, readText(Charsets.UTF_8).removePrefix(UTF8_BOM)) }
            .onFailure { logger.warn("既有基準線 ${relativeToRepo()} 無法解析（${it.message}），將直接覆寫。") }
            .getOrNull()
    }

    private companion object {
        const val UTF8_BOM = "﻿"
    }
}
