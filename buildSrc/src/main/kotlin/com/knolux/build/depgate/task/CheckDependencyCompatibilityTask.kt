package com.knolux.build.depgate.task

import com.knolux.build.depgate.BaselineLookup
import com.knolux.build.depgate.BaselineStaleness
import com.knolux.build.depgate.ConsoleRenderer
import com.knolux.build.depgate.DeltaCalculator
import com.knolux.build.depgate.DependencySet
import com.knolux.build.depgate.FileBaselineSource
import com.knolux.build.depgate.GateReport
import com.knolux.build.depgate.GateStatus
import com.knolux.build.depgate.GateVerdict
import com.knolux.build.depgate.GitBaselineSource
import com.knolux.build.depgate.GitRefs
import com.knolux.build.depgate.ReportRenderer
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option

/**
 * 閘門本體：比對當前依賴與 merge-base 當時的基準線，發現未核准的破壞性變更即讓建置失敗。
 *
 * 這是 CI 上實際擋 PR 的任務（spec User Story 1 / 3 / 4）。刻意**不**掛在 `check` 之下——
 * `check` 會被 `build` 觸發，而本任務需要 git 歷史與遠端 ref；在淺層 clone 或無 git 的
 * source tarball 中掛上去，會讓一般建置直接失敗。
 *
 * 兩項執行順序是硬性要求：
 * 1. **所有模組所有差異一次算完**才決定成敗，不得遇到第一項阻擋就中止（FR-012）
 * 2. **報告寫檔先於拋出例外**（FR-019）——閘門擋下 PR 的當下，報告正是維護者最需要的東西
 */
abstract class CheckDependencyCompatibilityTask : DependencyGateTask() {

    /** 差異報告輸出位置。 */
    @get:Internal
    abstract val reportFile: RegularFileProperty

    /** `--base=<git-ref>`；未指定時依序嘗試 [DEFAULT_BASES]。 */
    @get:Internal
    @get:Option(option = "base", description = "比較基準的 git ref，預設依序嘗試 origin/dev、origin/main")
    abstract val base: Property<String>

    init {
        description = "比對 merge-base 與當前的傳遞依賴，攔截未核准的破壞性變更"
    }

    @TaskAction
    fun check() {
        val comparisonBase = GitRefs(repoDir.get().asFile)
            .resolveComparisonBase(explicit = base.orNull, defaults = DEFAULT_BASES)

        val fileSource = FileBaselineSource(baselineDir.get().asFile)
        val gitSource = GitBaselineSource(repoDir.get().asFile, comparisonBase.mergeBase, baselineDirPath())
        val approvals = approvalMatcher()

        val staleness = mutableListOf<BaselineStaleness>()
        val verdicts = currentDependencySets().map { (moduleName, current) ->
            staleness += detectStaleBaseline(fileSource, current)

            when (val historic = gitSource.load(moduleName)) {
                // FR-005：基準線在比較基準當時尚不存在（例如首次導入閘門的那支 PR）。
                // 這不是錯誤，但必須記入報告，否則「沒被保護到」會無聲無息地過去。
                is BaselineLookup.Missing -> GateVerdict.skipped(moduleName, historic.reason)

                is BaselineLookup.Found ->
                    GateVerdict.evaluate(moduleName, DeltaCalculator.calculate(historic.dependencySet, current), approvals)
            }
        }

        val report = GateReport.of(comparisonBase.describe, verdicts, approvals)

        // FR-019：先寫檔，再談成敗。反過來的話，最需要報告的那一次剛好沒有報告。
        val reportPath = writeReport(report)

        report.verdicts.filter { it.status == GateStatus.SKIPPED_NO_BASELINE }
            .forEach { logger.lifecycle("依賴相容性閘門：⏭️ 跳過 ${it.moduleName}——${it.skipReason}") }

        reportFailures(report, staleness, reportPath)

        logger.lifecycle(
            "依賴相容性閘門：✅ 通過（比較基準 ${comparisonBase.ref}，" +
                "${report.verdicts.sumOf { it.deltas.size }} 項差異皆非阻擋或已核准）｜報告：$reportPath",
        )
    }

    /**
     * 檢查簽入的基準線是否與當前解析結果一致（research.md R2 機制 M1）。
     *
     * 與閘門比對是兩件事：閘門比的是「merge-base 當時的檔案 vs 現在的實際解析」，
     * 而這裡確保「現在的檔案 vs 現在的實際解析」——後者若不成立，這支 PR 合併後
     * 就會把一份不真實的基準線留給下一個人當比較對象。
     *
     * 成敗的判定交給 [BaselineStaleness.blocking]，此處只負責取資料。
     */
    private fun detectStaleBaseline(source: FileBaselineSource, current: DependencySet): List<BaselineStaleness> =
        when (val lookup = source.load(current.moduleName)) {
            // 工作區缺基準線由 gitSource 那側判為跳過並記入報告，此處不重複報錯。
            is BaselineLookup.Missing -> emptyList()

            is BaselineLookup.Found -> listOfNotNull(BaselineStaleness.detect(lookup.dependencySet, current))
        }

    private fun writeReport(report: GateReport): String {
        val file = reportFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(ReportRenderer.renderGateReport(report), Charsets.UTF_8)
        return file.relativeToRepo()
    }

    /**
     * 一次拋出全部問題。
     *
     * 基準線過期與未核准的破壞性變更會**同時**回報：分兩次讓維護者各跑一輪 CI，
     * 等於把 FR-012 想避免的「修一項、重跑、又冒出一項」迴圈搬到任務之間再上演一次。
     *
     * 基準線過期只在落差含阻擋性項目時失敗。全屬 patch / minor / 新增時警告即可——
     * 一律失敗會讓 Dependabot 的每支 PR 紅燈（違反 FR-011 與 SC-003），
     * 而那正是 research.md R2 否決 Gradle dependency locking 的理由。
     */
    private fun reportFailures(report: GateReport, staleness: List<BaselineStaleness>, reportPath: String) {
        val (blockingStale, tolerated) = staleness.partition { it.blocking }

        tolerated.forEach { logger.warn(ConsoleRenderer.renderToleratedStaleBaseline(it, baselinePathOf(it))) }

        if (blockingStale.isEmpty() && !report.blocked) return

        blockingStale.forEach { logger.error(ConsoleRenderer.renderStaleBaseline(it, baselinePathOf(it))) }
        if (report.blocked) logger.error(ConsoleRenderer.renderBlockedSummary(report, reportPath))

        val reasons = listOfNotNull(
            "${report.blockedDeltas.size} 項未核准的破壞性變更".takeIf { report.blocked },
            "${blockingStale.size} 個模組的基準線已過期且落差具阻擋性".takeIf { blockingStale.isNotEmpty() },
        )
        throw GradleException("依賴相容性閘門失敗：${reasons.joinToString("、")}；詳見上方輸出與 $reportPath。")
    }

    private fun baselinePathOf(staleness: BaselineStaleness) = baselineFile(staleness.moduleName).relativeToRepo()
}
